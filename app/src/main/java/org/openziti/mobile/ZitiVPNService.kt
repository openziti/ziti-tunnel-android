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
import java.io.Writer
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext


class ZitiVPNService : VpnService(), CoroutineScope {

    companion object {
        private val TAG: String = ZitiVPNService::class.java.simpleName
        private const val RESTART = "restart"
        private const val START = "start"
        private const val STOP = "stop"
    }

    private val exec = Executors.newSingleThreadExecutor{ r ->
        Thread(r, TAG)
    }
    override val coroutineContext = SupervisorJob() + exec.asCoroutineDispatcher()

    val dnsAddr = "100.64.0.2"

    private val peerChannel = Channel<ByteBuffer>(128)
    private var tunnel: Tunnel? = null
    lateinit var packetRouter: PacketRouter
    lateinit var dnsResolver: DNSResolver

    val tunnelState = MutableStateFlow("stop")

    private lateinit var connMgr: ConnectivityManager
    private val allNetworks = mutableSetOf<Network>()
    private fun networks(): Array<Network> = allNetworks.flatMap { net ->
        connMgr.getNetworkCapabilities(net)?.let { listOf(net to it) } ?: emptyList()
    }.toSortedSet{ net1, net2 ->
        val c1 = cost(net1.second)
        val c2 = cost(net2.second)

        if (c1 == c2) (net1.first.networkHandle - net2.first.networkHandle).toInt()
        else c1 - c2
    }.map { it.first }.toTypedArray()

    fun cost(cap: NetworkCapabilities?): Int {
        if (cap == null) return Int.MAX_VALUE

        var c = 0
        if (!cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            c += 100

        if (!cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            c += 10

        return c
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
                tunnelState.value = RESTART
            }
        }
    }

    private val networkMonitor = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val capabilities = connMgr.getNetworkCapabilities(network)
            Log.i(TAG, "network available: $network, caps:$capabilities")
            allNetworks += network

            val nwrks = networks()
            runCatching { setUnderlyingNetworks(nwrks) }.onFailure {
                Log.w(TAG, "failed to set networks", it)
            }
        }

        override fun onLost(network: Network) {
            Log.i(TAG, "network: $network is lost")
            allNetworks -= network
            runCatching {
                setUnderlyingNetworks(networks())
            }.onFailure {
                Log.w(TAG, "failed to set networks", it)
            }
        }
    }

    override fun onCreate() {
        Log.i(TAG, "onCreate()")
        ZitiMobileEdgeApp.vpnService = this

        connMgr = getSystemService(ConnectivityManager::class.java)
        connMgr.registerDefaultNetworkCallback(networkMonitor)

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
            Log.i(TAG, "command monitor started")
            tunnelState.collect { cmd ->
                Log.i(TAG, "received cmd[$cmd]")
                runCatching {
                    when(cmd) {
                        START -> startTunnel()
                        RESTART -> restartTunnel()
                        STOP -> {
                            tunnel?.close()
                            tunnel = null
                        }
                        else -> Log.i(TAG, "unsupported command[$cmd]")
                    }
                }.onSuccess {
                    Log.i(TAG, "tunnel ${cmd} success")
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
        ZitiMobileEdgeApp.vpnService = null

        connMgr.unregisterNetworkCallback(networkMonitor)
        LocalBroadcastManager.getInstance(applicationContext).unregisterReceiver(receiver)
        monitor.cancel()
        tunnel?.close()
        packetRouter.stop()
        coroutineContext.cancel()
        exec.runCatching { shutdownNow() }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand $intent, $startId")

        Log.i(TAG, "monitor=$monitor")
        val action = intent?.action
        when (action) {
            SERVICE_INTERFACE,
            START -> tunnelState.value = "start"

            RESTART -> tunnelState.value = "restart"

            STOP -> tunnelState.value = "stop"

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
                setUnderlyingNetworks(networks())
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

            Log.i(TAG, "starting tunnel for fd=${fd.fileDescriptor}")
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

    fun dump(output: Writer) {
        output.appendLine("""supervisor:  ${coroutineContext.job}""")
        output.appendLine("""monitor:     ${monitor}""")
        output.appendLine("""tunnelState: ${tunnelState.value}""")
        output.appendLine("""tunnelUptime:${tunnel?.uptime?.toString() ?: "not running"}""")
    }

}
