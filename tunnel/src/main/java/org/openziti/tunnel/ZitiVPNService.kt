/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.system.OsConstants
import android.util.Log
import org.openziti.android.Ziti
import org.openziti.net.dns.DNSResolver
import org.openziti.tunnel.net.TUNNEL_MTU

class ZitiVPNService : VpnService() {

    companion object {
        const val BUFFER_SIZE: Int = 1 shl 16
    }

    private val TAG: String = javaClass.simpleName

    private var tunnel: Tunnel? = null

    override fun onCreate() {
        Log.i(TAG, "onCreate()")
    }

    override fun onDestroy() {
        tunnel?.close()
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

    private fun startTunnel() {
        val dns: DNSResolver = Ziti.getDnsResolver()

        tunnel?.close()
        tunnel = null

        val prefs = getSharedPreferences("ziti-vpn", Context.MODE_PRIVATE)
        val addr = prefs.getString("address", "169.254.0.1")
        val addrPrefix = prefs.getInt("addrPrefix", 32)
        val route = prefs.getString("route", "169.254.0.0")
        val routePrefix = prefs.getInt("routePrefix", 16)
        val apps: Set<String> = prefs.getStringSet("selected-apps", emptySet())!!
        val dnsAddr = "169.254.0.2"


        Log.i(TAG, "startTunnel()")
        try {
            val builder = Builder().apply {
                addAddress(addr, addrPrefix)
                addRoute(route, routePrefix)
                allowFamily(OsConstants.AF_INET)
                addDnsServer(dnsAddr)
                setMtu(TUNNEL_MTU)
                allowBypass()
                setBlocking(true)
                if (apps.isEmpty()) {
                    val thisApp = applicationContext.packageName
                    Log.d(TAG, "excluding $thisApp")
                    addDisallowedApplication(thisApp)
                } else {
                    for (a in apps) {
                        Log.d(TAG, "adding $a")
                        addAllowedApplication(a)
                    }
                }

                setConfigureIntent(
                        PendingIntent.getActivity(applicationContext, 0,
                                Intent(applicationContext, ZitiVPNActivity::class.java),
                                PendingIntent.FLAG_UPDATE_CURRENT))
            }

            Log.i(TAG, "creating tunnel interface")
            val fd = builder.establish()

            Log.i(TAG, "starting tunnel for fd=$fd")
            return Tunnel(fd, dns, dnsAddr).let {
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
