/*
 * Copyright (c) 2021 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import org.openziti.mobile.net.ZitiRouteManager
import org.openziti.util.DebugInfoProvider
import java.io.Writer

class ZitiMobileInfoProvider: DebugInfoProvider {

    override fun dump(name: String, output: Writer) {
        val svc = ZitiMobileEdgeApp.vpnService
        if (svc == null) {
            output.appendLine("ZitiVPNService: not running")
        } else {
            output.appendLine("ZitiVPNService: running")
            svc.dump(output)
        }

        output.appendLine()
        output.appendLine("=== Routing ===")
        output.appendLine("${ZitiRouteManager.defaultRoute} (default)")
        ZitiRouteManager.routes.forEach {
            output.appendLine(it.toString())
        }
    }

    override fun names() = listOf("ziti_mobile.info")
}