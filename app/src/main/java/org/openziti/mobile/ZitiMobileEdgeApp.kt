/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.openziti.android.Ziti

/**
 *
 */
class ZitiMobileEdgeApp: Application() {
    companion object {
        val ROUTE_CHANGE = "route_change"
    }
    internal val routes = mutableSetOf<ZitiVPNService.Route>()
    override fun onCreate() {
        super.onCreate()
        Ziti.setEnrollmentActivity(ZitiEnrollmentActivity::class.java)
        val dnsResolver = Ziti.getDnsResolver()

        dnsResolver.subscribe { de ->
            Log.i("ziti-app", "dns event $de")
            val existingRt = routes.find { it.route == de.ip.hostAddress && it.prefix == 32 }
            if (existingRt == null) {
                if (!de.removed) {
                    val rt = ZitiVPNService.Route(de.ip.hostAddress, 32)
                    rt.count = 1
                    routes.add(rt)
                    notifyRouteChange()
                }
            } else {
                if (de.removed) {
                    existingRt.count--
                    if (existingRt.count < 1) {
                        routes.remove(existingRt)
                        notifyRouteChange()
                    }
                } else {
                    existingRt.count++
                }
            }
        }

        Ziti.init(this, false)
    }

    internal fun notifyRouteChange() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ROUTE_CHANGE))
    }
}