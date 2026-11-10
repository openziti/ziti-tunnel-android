/*
 * Copyright (c) 2026 NetFoundry. All rights reserved.
 */

package org.openziti.integ

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/** Needs a ziti CLI and one attached device/emulator: run through `./gradlew :integ:integrationTest`. */
@Tag("integration")
@ExtendWith(IntegExtension::class)
class EnrollAndLoadIntegTest {

    @Test
    fun enrollsAndLoadsIdentity(env: IntegEnv) {
        env.device.clearPackage(TEST_PACKAGE)
        val jwt = env.model.newClientJwt("enroll-and-load")

        env.runner.runOrFail(
            "org.openziti.tunnel.integ.EnrollAndLoadTest",
            "enrollsAndLoadsIdentity",
            mapOf("jwt" to jwt, "service" to Model.SERVICE),
            env.outDir.resolve("enroll-and-load"),
        )
    }
}
