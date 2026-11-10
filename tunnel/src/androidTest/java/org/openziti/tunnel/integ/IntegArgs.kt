/*
 * Copyright (c) 2026 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel.integ

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume

/**
 * Inputs of the integration tests: the host harness (`:integ:integrationTest`) passes them as `am instrument -e key value`.
 * Without them the test is skipped, so a plain `connectedAndroidTest` stays green with no ziti network around.
 */
object IntegArgs {
    fun require(name: String): String {
        val value = InstrumentationRegistry.getArguments().getString(name)
        Assume.assumeTrue("instrumentation argument '$name' is not set: run via ./gradlew :integ:integrationTest", !value.isNullOrEmpty())
        return value!!
    }
}
