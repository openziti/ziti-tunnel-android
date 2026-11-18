/*
 * Copyright (c) 2026 NetFoundry. All rights reserved.
 */

package org.openziti.integ

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * TOTP enrollment, code submission and recovery codes, with identities on an auth policy that requires TOTP.
 * Needs a ziti CLI and one attached device/emulator: run through `./gradlew :integ:integrationTest`.
 */
@Tag("integration")
@ExtendWith(IntegExtension::class)
class TotpIntegTest {

    @Test
    fun enrollsTotpAndAuthenticates(env: IntegEnv) = run(env, "enrollsTotpAndAuthenticates", "totp-enroll")

    @Test
    fun submitsTotpCodeAtLogin(env: IntegEnv) = run(env, "submitsTotpCodeAtLogin", "totp-submit")

    @Test
    fun readsAndReplacesRecoveryCodes(env: IntegEnv) = run(env, "readsAndReplacesRecoveryCodes", "totp-recovery")

    /** Every device test gets an identity of its own: an enrollment JWT is good once. */
    private fun run(env: IntegEnv, method: String, identity: String) {
        env.device.clearPackage(TEST_PACKAGE)
        val jwt = env.model.newClientJwt(identity, Model.TOTP_POLICY)

        env.runner.runOrFail(
            "org.openziti.tunnel.integ.TotpTest",
            method,
            mapOf("jwt" to jwt, "service" to Model.SERVICE),
            env.outDir.resolve(identity),
        )
    }
}
