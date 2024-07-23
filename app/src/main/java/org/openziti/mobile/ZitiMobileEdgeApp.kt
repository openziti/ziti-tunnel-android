/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.openziti.android.Ziti

import org.openziti.tunnel.Tunnel

/**
 *
 */
class ZitiMobileEdgeApp: Application() {
    init {
        Log.i(this.javaClass.simpleName, "native[tlsuv]: ${Tunnel().tlsuvVersion()}")
        Log.i(this.javaClass.simpleName, "native[ziti]: ${Tunnel().zitiSdkVersion()}")
        Log.i(this.javaClass.simpleName, "native[ziti-tunnel]: ${Tunnel().zitiTunnelVersion()}")
    }
    companion object {
        val ROUTE_CHANGE = "route_change"
        lateinit var app: ZitiMobileEdgeApp
        var vpnService: ZitiVPNService? = null
    }

    override fun onCreate() {
        super.onCreate()
        app = this
        Ziti.setEnrollmentActivity(ZitiEnrollmentActivity::class.java)
        Ziti.init(this, false)
    }

    internal fun notifyRouteChange() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ROUTE_CHANGE))
    }
}