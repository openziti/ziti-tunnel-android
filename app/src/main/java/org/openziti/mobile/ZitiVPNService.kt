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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.openziti.android.Ziti
import org.openziti.mobile.net.TUNNEL_MTU
import org.openziti.mobile.net.ZitiNameserver
import org.openziti.mobile.net.ZitiRouteManager
import org.openziti.net.dns.DNSResolver
import java.io.Writer
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.time.toJavaDuration


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

    lateinit var tun: org.openziti.tunnel.Tunnel
    private val peerChannel = Channel<ByteBuffer>(128)
    lateinit var dnsResolver: DNSResolver
    lateinit var zitiNameserver: ZitiNameserver

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

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let {
                Log.i(TAG, "restarting tunnel due to $intent")
                tunnelState.value = RESTART
            }
        }
    }

    private fun setNetworks() {
//        val nwrks = networks()
        runCatching {
           // setUnderlyingNetworks(nwrks)
        }.onFailure {
            Log.w(TAG, "failed to set networks", it)
        }

    }

    private val networkMonitor = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val capabilities = connMgr.getNetworkCapabilities(network)
            Log.i(TAG, "network available: $network, caps:$capabilities")
            allNetworks += network

            setNetworks()
        }

        override fun onLost(network: Network) {
            Log.i(TAG, "network: $network is lost")
            allNetworks -= network
            setNetworks()
        }
    }

    override fun onCreate() {
        Log.i(TAG, "onCreate()")
        ZitiMobileEdgeApp.vpnService = this
        tun = (application as ZitiMobileEdgeApp).tunnel

        connMgr = getSystemService(ConnectivityManager::class.java)
        connMgr.registerDefaultNetworkCallback(networkMonitor)

        val prefs = getSharedPreferences("ziti-vpn", Context.MODE_PRIVATE)
        addr = prefs.getString("address", "169.254.0.1")!!
        addrPrefix = prefs.getInt("addrPrefix", 32)

        dnsResolver = Ziti.getDnsResolver()

        zitiNameserver = ZitiNameserver(dnsResolver)

        monitor = launch {
            launch {
                Log.i(TAG, "monitoring route updates")
                tun.routes().collect{
                    restartTunnel()
                }
            }.invokeOnCompletion {
                Log.i(TAG, "stopped route updates")
            }

            Log.i(TAG, "command monitor started")
            tunnelState.collect { cmd ->
                Log.i(TAG, "received cmd[$cmd]")
                runCatching {
                    when(cmd) {
                        START -> startTunnel()
                        RESTART -> restartTunnel()
                        STOP -> {
                            tun.stopNetworkInterface()
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
        if (tun.isActive()) {
            Log.i(TAG, "restarting tunnel")
            startTunnel()
        }
    }

    private fun startTunnel() {
        val tun = (application as ZitiMobileEdgeApp).tunnel
        tun.stopNetworkInterface()

        Log.i(TAG, "startTunnel()")
        try {
            val builder = Builder().apply {
                allowFamily(OsConstants.AF_INET)
                allowFamily(OsConstants.AF_INET6)
                allowBypass()

                addAddress(ZitiRouteManager.defaultRoute.ip, ZitiRouteManager.defaultRoute.ip.address.size * 8)
                addAddress(ZitiRouteManager.defaultRoute6.ip, ZitiRouteManager.defaultRoute6.ip.address.size * 8)

                // default route
                addRoute(ZitiRouteManager.defaultRoute.ip, ZitiRouteManager.defaultRoute.bits)


                // DNS
                for (a in zitiNameserver.addresses) {
                    addDnsServer(a)
                }

                val routes = tun.routes().value
                routes.filter { !it.address.isAnyLocalAddress }
                    .forEach { route ->
                        Log.d(TAG, "adding route $route")
                        route.runCatching {
                            addRoute(route.address, route.bits)
                        }.onFailure {
                            Log.e(TAG, "invalid route[$route]", it)
                        }
                    }
                setUnderlyingNetworks(null)

                setMtu(TUNNEL_MTU)
                setBlocking(true)
                addDisallowedApplication(applicationContext.packageName)

                setConfigureIntent(
                        PendingIntent.getActivity(applicationContext, 0,
                                Intent(applicationContext, ZitiMobileEdgeActivity::class.java),
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }

            Log.i(TAG, "creating tunnel interface")
            val fd = builder.establish()!!

            Log.i(TAG, "starting tunnel for fd=${fd.fileDescriptor}")
            tun.startNetworkInterface(fd)
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
        fun isVPNActive() = tun.isActive()
        fun getUptime(): Duration = tun.getUptime().toJavaDuration()
    }

    fun dump(output: Writer) {
        val state = if (tun.isActive()) "Running" else "Stopped"
        output.appendLine("""supervisor:  ${coroutineContext.job}""")
        output.appendLine("""monitor:     ${monitor}""")
        output.appendLine("""tunnelState: ${state}""")
        output.appendLine("""tunnelUptime:${tun.getUptime()}""")
    }

}
