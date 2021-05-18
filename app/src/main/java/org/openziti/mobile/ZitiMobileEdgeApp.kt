/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.openziti.android.Ziti
import java.util.concurrent.ConcurrentHashMap

/**
 *
 */
class ZitiMobileEdgeApp: Application() {
    companion object {
        val ROUTE_CHANGE = "route_change"
    }
    private val routesMap = ConcurrentHashMap<ZitiVPNService.Route, ZitiVPNService.Route>()

    internal val routes: Collection<ZitiVPNService.Route>
        get() = routesMap.values

    override fun onCreate() {
        super.onCreate()
        Ziti.setEnrollmentActivity(ZitiEnrollmentActivity::class.java)
        val dnsResolver = Ziti.getDnsResolver()

        dnsResolver.subscribe { de ->
            Log.i("ziti-app", "dns event $de")
            val rt = ZitiVPNService.Route(de.ip.hostAddress, 32)
            val existingRt = routesMap[rt]
            if (existingRt == null) {
                if (!de.removed) {
                    rt.count = 1
                    routesMap.put(rt, rt)
                    notifyRouteChange()
                }
            } else {
                if (de.removed) {
                    existingRt.count--
                    if (existingRt.count < 1) {
                        routesMap.remove(existingRt)
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