/*
 * Copyright (c) 2025 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.openziti.mobile.model.TunnelModel
import org.openziti.tunnel.toRoute
import java.net.Inet4Address
import java.net.Inet6Address
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

    lateinit var model: TunnelModel
    lateinit var tun: org.openziti.tunnel.Tunnel
    val tunnelState = MutableStateFlow("stop")

    private lateinit var connMgr: ConnectivityManager
    lateinit var monitor: Job

    private val netListener = ConnectivityManager.OnNetworkActiveListener {
        (application as ZitiMobileEdgeApp).model.refreshAll()
    }

    private fun setUpstreamDNS(net: Network, props: LinkProperties) {
        val addresses = props.linkAddresses
            .map { it.address }
            .filter { !it.isAnyLocalAddress && !it.isLinkLocalAddress }

        Log.i(TAG, "link[$net] addresses: $addresses")
        Log.i(TAG, "link[$net] nameservers: ${props.dnsServers}")

        val ipv4only = addresses.filterIsInstance<Inet6Address>().isEmpty()

        val dns =
            if (ipv4only) props.dnsServers.filterIsInstance<Inet4Address>()
            else props.dnsServers

        val upstream = dns.map { it.toString().removePrefix("/") }
        val model = (application as ZitiMobileEdgeApp).model
        Log.i(TAG, "set upstream DNS[$upstream]")
        model.setUpstreamDNS(upstream)
    }

    private var metered = false
    private val networkMonitor = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            connMgr.getNetworkCapabilities(network)?.let {
                Log.d(TAG, "network available: $network, caps:$it")

                val transport = when {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    else -> "unknown"
                }

                val netMetered = !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                if (netMetered != metered) {
                    Log.i(TAG, """switching to ${if (netMetered) "" else "un"}metered network[$transport]""")
                    metered = netMetered
                    restartTunnel()
                }
            }

        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            Log.i(TAG, "link change[$network], active[${connMgr.activeNetwork}]")
            if (network == connMgr.activeNetwork) {
                setUpstreamDNS(network, linkProperties)
            }
        }

        override fun onLost(network: Network) {
            Log.i(TAG, "network: $network is lost, active[${connMgr.activeNetwork}]")
        }

        override fun onUnavailable() {
            Log.i(TAG, "no available network")
        }
    }

    override fun onCreate() {
        Log.i(TAG, "onCreate()")
        ZitiMobileEdgeApp.vpnService = this
        tun = (application as ZitiMobileEdgeApp).tunnel
        model = (application as ZitiMobileEdgeApp).model

        connMgr = getSystemService(ConnectivityManager::class.java)
        val netReq = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connMgr.registerNetworkCallback(netReq, networkMonitor)
        connMgr.addDefaultNetworkActiveListener(netListener)

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
                    Log.i(TAG, "tunnel $cmd success")
                }.onFailure {
                    Log.w(TAG, "exception during tunnel $cmd", it)
                }
            }
        }

        monitor.invokeOnCompletion {
            Log.i(TAG, "monitor stopped", it)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        ZitiMobileEdgeApp.vpnService = null
        connMgr.unregisterNetworkCallback(networkMonitor)
        connMgr.removeDefaultNetworkActiveListener(netListener)
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
        return START_STICKY
    }

    private fun restartTunnel() {
        if (tun.isActive()) {
            Log.i(TAG, "restarting tunnel")
            startTunnel()
        }
    }

    private fun createTunFD() = runCatching {
        val metered = connMgr.isActiveNetworkMetered
        val routes = tun.routes().value.filter { !it.address.isAnyLocalAddress }

        val builder = Builder().apply {
            allowFamily(OsConstants.AF_INET)
            allowFamily(OsConstants.AF_INET6)
            allowBypass()
            setMetered(metered)

            val range = runBlocking { model.zitiRange.first().toRoute() }
            val size = if (range.address is Inet6Address) 128 else 32
            addAddress(range.address, size)
            addDnsServer(runBlocking { model.zitiDNS.first() })

            routes.forEach { route ->
                Log.d(TAG, "adding route $route")
                route.runCatching {
                    addRoute(route.address, route.bits)
                }.onFailure {
                    Log.e(TAG, "invalid route[$route]", it)
                }
            }
            setUnderlyingNetworks(null)

            // cannot be bigger than netif buffer, see netif.cpp
            setMtu(32 * 1024)
            setBlocking(true)
            addDisallowedApplication(applicationContext.packageName)

            setConfigureIntent(
                PendingIntent.getActivity(applicationContext, 0,
                    Intent(applicationContext, ZitiMobileEdgeActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }

        Log.i(TAG, "creating tunnel interface")
        builder.establish()!!
    }

    private fun startTunnel() {
        val tun = (application as ZitiMobileEdgeApp).tunnel
        connMgr.activeNetwork?.let { net ->
            connMgr.getLinkProperties(net)?.let { props ->
                setUpstreamDNS(net, props)
            }
        }

        Log.i(TAG, "startTunnel()")
        // Android supports seamless transition to a new
        // interface FD.
        // https://developer.android.com/reference/android/net/VpnService.Builder#establish()
        createTunFD().map {
            Log.i(TAG, "starting tunnel with interface fd=${it.fd}")
            tun.startNetworkInterface(it)
        }.onFailure {
            Log.wtf(TAG, it)
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
}
