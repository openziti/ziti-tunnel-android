/*
 * Copyright (c) 2025 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import android.Manifest.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.openziti.mobile.model.TunnelModel
import org.openziti.tunnel.toRoute
import java.net.Inet4Address
import java.net.Inet6Address
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.toJavaDuration
import timber.log.Timber as Log


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
        (application as ZitiMobileEdgeApp).model.refreshAll()
    }

    private fun setUpstreamDNS(net: Network) {
        val props = connMgr.getLinkProperties(net)
        if (props == null) {
            Log.e("failed to get network[$net] properties")
            return
        }

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

    private val networkChange = MutableStateFlow(ULong.MIN_VALUE)
    private val metered = AtomicBoolean(false)

    private val networkMonitor = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(net: Network) = Log.d("network[$net] is available")

        override fun onCapabilitiesChanged(net: Network, caps: NetworkCapabilities) {
            Log.d("network[$net] capabilities change, active[${connMgr.activeNetwork}]")
            if (net != connMgr.activeNetwork) return

            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val internetValid = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val notVPN = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            Log.d("network[$net] capabilities: internet[$hasInternet] validated[$internetValid] notVPN[$notVPN]")
            if (hasInternet && internetValid && notVPN) {
                networkChange.update { it.inc() }
            }
        }

        override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) {
            Log.d("network[$network] link change, active[${connMgr.activeNetwork}]")
            if (network == connMgr.activeNetwork) {
                // update in case upstream DNS changes
                networkChange.update { it.inc() }
            }
        }

        override fun onLost(network: Network) = Log.i("network[$network] is lost")
        override fun onUnavailable() = Log.i("no available network")
    }

    internal fun processNetworkChange(network: Network) {
        val caps = connMgr.getNetworkCapabilities(network)
        if (caps == null) {
            Log.e("failed to get network[$network] capabilities")
            return
        }
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "unknown"
        }

        val netMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        Log.d("network[$network] is active: transport[$transport] metered[$metered]")

        setUpstreamDNS(network)

        if (metered.getAndSet(netMetered) != netMetered) {
            Log.i("""switching to ${if (netMetered) "" else "un"}metered network[$transport]""")
            restartTunnel()
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Log.i("onTimeout($startId, $fgsType)")
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

        runCatching { markForeground() }.onFailure { it
            Log.w(it, "failed to mark service foreground")
        }

        monitor = launch {
            launch {
                Log.i("monitoring route updates")
                tun.routes().collect{
                    restartTunnel()
                }
            }.invokeOnCompletion {
                Log.i("stopped route updates")
            }

            launch {
                Log.i("monitoring network changes")
                networkChange.collect {
                    connMgr.activeNetwork?.let {
                        runCatching {
                            processNetworkChange(it)
                        }.onFailure { ex ->
                            Log.w(ex, "failed to process network change")
                        }
                    } ?: Log.w("no active network")
                }
            }

            Log.i("command monitor started")
            tunnelState.collect { cmd ->
                Log.i("received cmd[$cmd]")
                runCatching {
                    when(cmd) {
                        START -> startTunnel()
                        RESTART -> restartTunnel()
                        STOP -> tun.stopNetworkInterface()
                        else -> Log.i("unsupported command[$cmd]")
                    }
                }.onSuccess {
                    Log.i("tunnel $cmd success")
                }.onFailure {
                    Log.w(it, "exception during tunnel $cmd")
                }
            }
        }

        monitor.invokeOnCompletion {
            Log.i(it, "monitor stopped")
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
            STOP -> {
                tunnelState.value = STOP
                return START_NOT_STICKY
            }

            RESTART -> tunnelState.value = RESTART
            else -> tunnelState.value = START
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setMetered(metered)
            }

            val range = runBlocking { model.zitiRange.first().toRoute() }
            val size = if (range.address is Inet6Address) 128 else 32
            addAddress(range.address, size)
            addDnsServer(runBlocking { model.zitiDNS.first() })

            routes.forEach { route ->
                Log.d("adding route $route")
                route.runCatching {
                    addRoute(route.address, route.bits)
                }.onFailure {
                    Log.e(it, "invalid route[$route]")
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

    private fun markForeground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(1, createNotification())
        } else {
            startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        }
    }

    private fun createNotification(): Notification {
        val channelId = "Ziti Mobile Edge"
        val channel = NotificationChannel(
            channelId,
            "Firewall Status",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, ZitiMobileEdgeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Ziti Mobile Edge")
            .setContentText("Ziti is active")
            .setSmallIcon(R.drawable.z)
            .setContentIntent(pendingIntent)
            .build()
    }

}
