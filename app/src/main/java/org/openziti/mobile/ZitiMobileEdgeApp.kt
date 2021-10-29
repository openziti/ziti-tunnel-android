/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import android.app.Application
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.openziti.android.Ziti

/**
 *
 */
class ZitiMobileEdgeApp: Application() {
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