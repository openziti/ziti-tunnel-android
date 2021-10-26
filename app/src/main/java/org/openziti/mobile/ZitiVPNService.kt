/*
 * Copyright (c) 2021 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.system.OsConstants
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import org.openziti.android.Ziti
import org.openziti.mobile.net.PacketRouter
import org.openziti.mobile.net.PacketRouterImpl
import org.openziti.mobile.net.TUNNEL_MTU
import org.openziti.mobile.net.ZitiRouteManager
import org.openziti.net.dns.DNSResolver
import java.nio.ByteBuffer
import java.time.Duration


class ZitiVPNService : VpnService(), CoroutineScope {

    companion object {
        private val TAG: String = ZitiVPNService::class.java.simpleName
    }

    override val coroutineContext = SupervisorJob() + Dispatchers.IO

    val dnsAddr = "100.64.0.2"

    private val peerChannel = Channel<ByteBuffer>(128)
    private var tunnel: Tunnel? = null
    lateinit var packetRouter: PacketRouter
    lateinit var dnsResolver: DNSResolver

    val tunnelState = MutableStateFlow("stop")

    internal data class Route(val route: String, val prefix: Int) {
        var count = 0
    }

    // prefs
    lateinit var addr: String
    var addrPrefix: Int = 32
    lateinit var monitor: Job

    private val allowedApps = mutableSetOf<String>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let {
                Log.i(TAG, "restarting tunnel due to $intent")
                tunnelState.value = it
            }
        }
    }

    private val networkMonitor = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val connMgr = getSystemService(ConnectivityManager::class.java)
            val capabilities = connMgr.getNetworkCapabilities(network)!!
            val hasInternet =
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            Log.i(TAG, "network: $network, hasInternet: $hasInternet")
            if (hasInternet) {
                tunnel?.let {
                    setUnderlyingNetworks(arrayOf(network))
                }
            }
        }
    }

    override fun onCreate() {
        Log.i(TAG, "onCreate()")

        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkMonitor)

        val prefs = getSharedPreferences("ziti-vpn", Context.MODE_PRIVATE)
        addr = prefs.getString("address", "169.254.0.1")!!
        addrPrefix = prefs.getInt("addrPrefix", 32)

        prefs.getStringSet("selected-apps", emptySet())?.let {
            allowedApps.addAll(it)
        }

        dnsResolver = Ziti.getDnsResolver()
        packetRouter = PacketRouterImpl(dnsResolver, dnsAddr) {buf ->
            peerChannel.send(buf)
        }

        monitor = launch {
            tunnelState.collect { cmd ->
                Log.v(TAG, "received signal[$cmd]")
                runCatching {
                    when(cmd) {
                        "start" -> startTunnel()
                        "restart" -> restartTunnel()
                        "stop" -> {
                            tunnel?.close()
                            tunnel = null
                        }
                        else -> Log.i(TAG, "unsupported command[$cmd]")
                    }
                }.onFailure {
                    Log.w(TAG, "exception during tunnel $cmd", it)
                }
            }
        }

        monitor.invokeOnCompletion {
            Log.i(TAG, "monitor stopped", it)
        }

        LocalBroadcastManager.getInstance(applicationContext).registerReceiver(receiver,
                IntentFilter(ZitiMobileEdgeApp.ROUTE_CHANGE))
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkMonitor)
        LocalBroadcastManager.getInstance(applicationContext).unregisterReceiver(receiver)
        tunnel?.close()
        packetRouter.stop()
        monitor.cancel()
        coroutineContext.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand $intent, $startId")

        val action = intent?.action
        when (action) {
            SERVICE_INTERFACE,
            "start" -> tunnelState.value = "start"

            "restart" -> tunnelState.value = "restart"

            "stop" -> tunnelState.value = "stop"

            else -> Log.wtf(TAG, "what is your intent? $intent")

        }
        return Service.START_STICKY
    }

    private fun restartTunnel() {
        if (tunnel != null) {
            Log.i(TAG, "restarting tunnel")
            startTunnel()
        }
    }

    private fun startTunnel() {
        tunnel?.close()
        tunnel = null

        Log.i(TAG, "startTunnel()")
        try {
            val connMgr = getSystemService(ConnectivityManager::class.java)
            val network = connMgr.activeNetwork
            if (network != null) {
                Log.i(TAG, """active network($network):
                    capabilities: ${connMgr.getNetworkCapabilities(network)}
                    link: ${connMgr.getLinkProperties(network)}""".trimIndent())
            } else {
                Log.w(TAG, "no active network")
            }

            val builder = Builder().apply {
                addAddress(addr, addrPrefix)
                addRoute(ZitiRouteManager.defaultRoute.ip, ZitiRouteManager.defaultRoute.bits)
                ZitiRouteManager.routes.forEach { route ->
                    if (route.ip.isAnyLocalAddress)
                        Log.w(TAG, "skipping local route ${route}")
                    else {
                        Log.d(TAG, "adding route $route")
                        route.runCatching {
                            addRoute(route.cidrAddress, route.bits)
                        }.onFailure {
                            Log.e(TAG, "invalid route[$route]", it)
                        }
                    }
                }
                setUnderlyingNetworks(arrayOf(network))
                allowFamily(OsConstants.AF_INET)
                addDnsServer(dnsAddr)
                setMtu(TUNNEL_MTU)
                allowBypass()
                setBlocking(true)
                if (allowedApps.isEmpty()) {
                    val thisApp = applicationContext.packageName
                    Log.d(TAG, "excluding $thisApp")
                    addDisallowedApplication(thisApp)
                } else {
                    for (a in allowedApps) {
                        Log.d(TAG, "adding $a")
                        addAllowedApplication(a)
                    }
                }

                setConfigureIntent(
                        PendingIntent.getActivity(applicationContext, 0,
                                Intent(applicationContext, ZitiMobileEdgeActivity::class.java),
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }

            Log.i(TAG, "creating tunnel interface")
            val fd = builder.establish()!!

            Log.i(TAG, "starting tunnel for fd=$fd")
            return Tunnel(fd, packetRouter, peerChannel).let {
                tunnel = it
            }
        } catch (ex: Throwable) {
            Log.wtf(TAG, ex)
        }
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == SERVICE_INTERFACE)
            super.onBind(intent)
        else
            ZitiVPNBinder()

    inner class ZitiVPNBinder: Binder() {
        fun isVPNActive() = tunnel != null
        fun getUptime(): Duration? = tunnel?.uptime
    }

}
