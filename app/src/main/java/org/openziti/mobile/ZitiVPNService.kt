/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.system.OsConstants
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.openziti.android.Ziti
import org.openziti.mobile.net.PacketRouter
import org.openziti.mobile.net.PacketRouterImpl
import org.openziti.mobile.net.TUNNEL_MTU
import org.openziti.net.dns.DNSResolver

class ZitiVPNService : VpnService() {

    companion object {
        const val BUFFER_SIZE: Int = 1 shl 16
    }

    private val TAG: String = javaClass.simpleName
    val dnsAddr = "169.254.0.2"

    private var tunnel: Tunnel? = null
    lateinit var packetRouter: PacketRouter
    lateinit var dnsResolver: DNSResolver

    internal data class Route(val route: String, val prefix: Int) {
        var count = 0
    }

    // prefs
    lateinit var addr: String
    var addrPrefix: Int = 32

    lateinit var defaultRoute: String

    private val allowedApps = mutableSetOf<String>()
    private val routes = mutableSetOf<Route>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                restartTunnel()
            }
        }
    }

    override fun onCreate() {
        Log.i(TAG, "onCreate()")

        val prefs = getSharedPreferences("ziti-vpn", Context.MODE_PRIVATE)
        addr = prefs.getString("address", "169.254.0.1")!!
        addrPrefix = prefs.getInt("addrPrefix", 32)

        defaultRoute = prefs.getString("route", "169.254.0.0")!!
        val routePrefix = prefs.getInt("routePrefix", 16)
        routes.add(Route(defaultRoute, routePrefix))

        prefs.getStringSet("selected-apps", emptySet())?.let {
            allowedApps.addAll(it)
        }

        dnsResolver = Ziti.getDnsResolver()
        packetRouter = PacketRouterImpl(dnsResolver, dnsAddr) {buf ->
            tunnel?.onInbound(buf) ?: Log.w(TAG, "failed to send packet because tunnel was closed")
        }

        LocalBroadcastManager.getInstance(applicationContext).registerReceiver(receiver,
                IntentFilter(ZitiMobileEdgeApp.ROUTE_CHANGE))
    }

    override fun onDestroy() {
        tunnel?.close()
        packetRouter.stop()
        super.onDestroy()
        Log.i(TAG, "onDestroy")
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand $intent, $startId")

        when (intent.action) {
            "start" -> {
                startTunnel()
            }

            "stop" -> {
                tunnel?.close()
                stopSelf()
            }

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
                addRoute(dnsAddr, 32)
                val app = application as ZitiMobileEdgeApp
                app.routes.forEach{
                    Log.d(TAG, "adding route ${it.route}/${it.prefix} ${it.count}")
                    addRoute(it.route, it.prefix)
                }
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
                                PendingIntent.FLAG_UPDATE_CURRENT))
            }

            Log.i(TAG, "creating tunnel interface")
            val fd = builder.establish()!!

            Log.i(TAG, "starting tunnel for fd=$fd")
            return Tunnel(fd, packetRouter).let {
                tunnel = it
                it.start()
            }
        } catch (ex: Throwable) {
            Log.wtf(TAG, ex)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = ZitiVPNBinder()

    inner class ZitiVPNBinder: Binder() {
        fun isVPNActive() = tunnel != null
    }
}
