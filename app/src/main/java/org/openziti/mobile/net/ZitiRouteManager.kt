/*
 * Copyright (c) 2021 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.net

import android.util.Log
import org.openziti.api.CIDRBlock
import org.openziti.mobile.ZitiMobileEdgeApp
import org.openziti.net.routing.RouteManager
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

class ZitiRouteManager: RouteManager {

    companion object {
        private val TAG = ZitiRouteManager::class.java.simpleName

        // 100.64.0.0/10
        val defaultRoute by lazy {
            CIDRBlock(
                InetAddress.getByAddress(byteArrayOf(100, 64, 0, 0)),
                10
            )
        }
        val defaultRoute6 by lazy {
            CIDRBlock(
                InetAddress.getByAddress(
                    byteArrayOf(
                        0xfd.toByte(), *("ziti!".toByteArray(Charsets.US_ASCII)),
                        0, 0,
                        0, 0, 0, 0,
                        0, 0, 0, 0
                    )
                ), 48
            )
        }
    }

    private val routesMap =
        ConcurrentHashMap<CIDRBlock, CIDRBlock>()

    internal val routes: Collection<CIDRBlock>
        get() = routesMap.values

    override fun addRoute(cidr: CIDRBlock) {
        if (!defaultRoute.includes(cidr)) {
            Log.i(TAG, "route $cidr added")
            routesMap[cidr] = cidr
            ZitiMobileEdgeApp.app.notifyRouteChange()
        }
    }

    override fun removeRoute(cidr: CIDRBlock) {
        routesMap.remove(cidr)?.let {
            Log.i(TAG, "route $it removed")
            ZitiMobileEdgeApp.app.notifyRouteChange()
        }
    }
}