/*
 * Copyright (c) 2025 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import android.app.Application
import org.openziti.log.NativeLog
import org.openziti.mobile.debug.DebugInfo
import org.openziti.mobile.model.TunnelModel
import org.openziti.tunnel.Tunnel
import timber.log.Timber as Log

/**
 *
 */
class ZitiMobileEdgeApp: Application() {
    lateinit var tunnel: Tunnel
    lateinit var model: TunnelModel

    companion object {
        lateinit var app: ZitiMobileEdgeApp
        var vpnService: ZitiVPNService? = null
    }

    override fun onCreate() {
        super.onCreate()
        tunnel = Tunnel(this)
        Log.plant(Log.DebugTree(), NativeLog)

        Log.i("native[tlsuv]: ${Tunnel.tlsuvVersion()}")
        Log.i("native[ziti]: ${Tunnel.zitiSdkVersion()}")
        Log.i("native[ziti-tunnel]: ${Tunnel.zitiTunnelVersion()}")

        model = TunnelModel(tunnel) { this }

        app = this
        DebugInfo.init(this)
    }
}