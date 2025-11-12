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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import timber.log.Timber as Log
import java.net.Inet4Address
import java.net.Inet6Address
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.time.toJavaDuration


class ZitiVPNService : VpnService(), CoroutineScope {

    companion object {
        private const val RESTART = "restart"
        private const val START = "start"
        private const val STOP = "stop"
    }

    private val exec = Executors.newSingleThreadExecutor{ r ->
        Thread(r, this::class.java.simpleName)
    }
    override val coroutineContext = SupervisorJob() + exec.asCoroutineDispatcher()

    lateinit var model: TunnelModel
    lateinit var tun: org.openziti.tunnel.Tunnel
    val tunnelState = MutableStateFlow("stop")

    private lateinit var connMgr: ConnectivityManager
    lateinit var monitor: Job

    private val netListener = ConnectivityManager.OnNetworkActiveListener {
        connMgr.activeNetwork?.let { setUpstreamDNS(it) }
        (application as ZitiMobileEdgeApp).model.refreshAll()
    }

    private fun setUpstreamDNS(net: Network) {
        val props = connMgr.getLinkProperties(net) ?: return
        val addresses = props.linkAddresses
            .map { it.address }
            .filter { !it.isAnyLocalAddress && !it.isLinkLocalAddress }

        Log.i("link[$net] addresses: $addresses")
        Log.i("link[$net] nameservers: ${props.dnsServers}")

        val ipv4only = addresses.filterIsInstance<Inet6Address>().isEmpty()

        val dns =
            if (ipv4only) props.dnsServers.filterIsInstance<Inet4Address>()
            else props.dnsServers

        val upstream = dns.map { it.toString().removePrefix("/") }
        val model = (application as ZitiMobileEdgeApp).model
        Log.i("set upstream DNS[$upstream]")
        model.setUpstreamDNS(upstream)
    }

    private var metered = false
    private var currentNetwork: Network? = null

    private val networkMonitor = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            connMgr.getNetworkCapabilities(network)?.let {
                Log.d("network available: $network, caps:$it")
                setUpstreamDNS(network)
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            Log.i("link change[$network], active[${connMgr.activeNetwork}]")
            processNetworkChange()
        }

        override fun onLost(network: Network) {
            Log.i("network: $network is lost, active[${connMgr.activeNetwork}]")
            processNetworkChange()
        }

        override fun onUnavailable() {
            Log.i("no available network")
        }
    }

    internal fun processNetworkChange() = launch(Dispatchers.IO) {
        if (currentNetwork == connMgr.activeNetwork) return@launch

        currentNetwork = connMgr.activeNetwork
        val network = currentNetwork

        if (network == null) {
            Log.i("no active network")
            return@launch
        }

        connMgr.getNetworkCapabilities(network)?.let {
            Log.d("network available: $network, caps:$it")

            setUpstreamDNS(network)

            val transport = when {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "unknown"
            }

            val netMetered = !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            if (netMetered != metered) {
                Log.i("""switching to ${if (netMetered) "" else "un"}metered network[$transport]""")
                metered = netMetered
                restartTunnel()
            }
        }
    }

    override fun onCreate() {
        Log.i("onCreate()")
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
                Log.i("monitoring route updates")
                tun.routes().collect{
                    restartTunnel()
                }
            }.invokeOnCompletion {
                Log.i("stopped route updates")
            }

            Log.i("command monitor started")
            tunnelState.collect { cmd ->
                Log.i("received cmd[$cmd]")
                runCatching {
                    when(cmd) {
                        START -> startTunnel()
                        RESTART -> restartTunnel()
                        STOP -> {
                            tun.stopNetworkInterface()
                        }
                        else -> Log.i("unsupported command[$cmd]")
                    }
                }.onSuccess {
                    Log.i("tunnel $cmd success")
                }.onFailure {
                    Log.w("exception during tunnel $cmd", it)
                }
            }
        }

        monitor.invokeOnCompletion {
            Log.i("monitor stopped", it)
        }
    }

    override fun onDestroy() {
        Log.i("onDestroy")
        ZitiMobileEdgeApp.vpnService = null
        connMgr.unregisterNetworkCallback(networkMonitor)
        connMgr.removeDefaultNetworkActiveListener(netListener)
        monitor.cancel()
        coroutineContext.cancel()
        exec.runCatching { shutdownNow() }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("onStartCommand $intent, $startId")

        Log.i("monitor=$monitor")
        val action = intent?.action
        when (action) {
            SERVICE_INTERFACE,
            START -> tunnelState.value = "start"

            RESTART -> tunnelState.value = "restart"

            STOP -> tunnelState.value = "stop"

            else -> Log.wtf("what is your intent? $intent")

        }
        return START_STICKY
    }

    private fun restartTunnel() {
        if (tun.isActive()) {
            Log.i("restarting tunnel")
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
                Log.d("adding route $route")
                route.runCatching {
                    addRoute(route.address, route.bits)
                }.onFailure {
                    Log.e("invalid route[$route]", it)
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

        Log.i("creating tunnel interface")
        builder.establish()!!
    }

    private fun startTunnel() {
        val tun = (application as ZitiMobileEdgeApp).tunnel
        connMgr.activeNetwork?.let { net -> setUpstreamDNS(net) }

        Log.i("startTunnel()")
        // Android supports seamless transition to a new
        // interface FD.
        // https://developer.android.com/reference/android/net/VpnService.Builder#establish()
        createTunFD().map {
            Log.i("starting tunnel with interface fd=${it.fd}")
            tun.startNetworkInterface(it)
        }.onFailure {
            Log.wtf(it)
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
