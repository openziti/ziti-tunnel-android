/*
 * Copyright (c) 2026 NetFoundry. All rights reserved.
 */

package org.openziti.integ

import java.nio.file.Files
import java.nio.file.Path

/**
 * The ziti network model the tests run against, created through `ziti edge` (as `ziti_model` does in the C SDK's
 * `c/tests/integ/conftest.py`). The identity-role attribute `client` is what the dial policy matches.
 */
class Model(private val cli: ZitiCli, private val jwtDir: Path) {

    /** The service every test identity can dial, plus the policy that lets `#client` identities dial it. */
    fun createBase() {
        cli.edge(
            "create", "service-policy", "clients-dial", "Dial",
            "--identity-roles", "#client",
            "--service-roles", "#all",
        )
        cli.edge("create", "service", SERVICE)
        // certificate login, then a TOTP code: an identity on this policy has to enroll TOTP before it can authenticate
        cli.edge("create", "auth-policy", TOTP_POLICY, "--primary-cert-allowed", "--secondary-req-totp")
    }

    /**
     * Creates identity [name] (attribute `client`) and returns its one-time enrollment JWT.
     * [authPolicy] is the name of the auth policy to put it on, the default policy when null.
     */
    fun newClientJwt(name: String, authPolicy: String? = null): String {
        Files.createDirectories(jwtDir)
        val file = jwtDir.resolve("$name.jwt")
        val policy = if (authPolicy == null) emptyArray() else arrayOf("-P", authPolicy)
        cli.edge("create", "identity", name, "-a", "client", *policy, "-o", file.toString())
        return Files.readString(file).trim()
    }

    companion object {
        const val SERVICE = "test-service"

        /** Auth policy that requires a TOTP code on top of the certificate. */
        const val TOTP_POLICY = "totp-required"
    }
}
