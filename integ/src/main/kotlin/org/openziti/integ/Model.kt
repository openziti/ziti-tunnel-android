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
    }

    /** Creates identity [name] (attribute `client`) and returns its one-time enrollment JWT. */
    fun newClientJwt(name: String): String {
        Files.createDirectories(jwtDir)
        val file = jwtDir.resolve("$name.jwt")
        cli.edge("create", "identity", name, "-a", "client", "-o", file.toString())
        return Files.readString(file).trim()
    }

    companion object {
        const val SERVICE = "test-service"
    }
}
