/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel

import android.app.Application
import org.openziti.android.Ziti

/**
 *
 */
class ZitiVPNApp: Application() {
    override fun onCreate() {
        super.onCreate()
        Ziti.setEnrollmentActivity(ZitiEnrollmentActivity::class.java)
        Ziti.init(this, false)
    }
}