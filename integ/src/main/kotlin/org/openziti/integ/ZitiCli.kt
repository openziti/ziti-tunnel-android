/*
 * Copyright (c) 2026 NetFoundry. All rights reserved.
 */

package org.openziti.integ

import java.io.IOException
import java.nio.file.Path

/**
 * The `ziti` CLI, with its own config dir.
 *
 * `ziti edge quickstart` logs the CLI in, and the login lives in the CLI config dir (`~/.ziti` by default). Every
 * process started through this class gets `ZITI_CONFIG_DIR=[configDir]`, so a test run never touches the developer's
 * own CLI login.
 */
class ZitiCli(val executable: String, val configDir: Path) {

    fun environment(): Map<String, String> = mapOf("ZITI_CONFIG_DIR" to configDir.toString())

    fun run(vararg args: String): CommandResult =
        try {
            execute(listOf(executable) + args, environment())
        } catch (e: IOException) {
            throw IllegalStateException(
                "cannot run '$executable': ${e.message}. Set ZITI_CLI to the ziti executable or put ziti on PATH.", e
            )
        }

    fun edge(vararg args: String): CommandResult {
        val result = run("edge", *args)
        if (result.exitCode != 0) {
            throw CommandFailed(
                "$executable edge ${args.joinToString(" ")} failed with exit code ${result.exitCode}: " +
                    result.stderr.ifBlank { result.stdout }.trim()
            )
        }
        return result
    }

    fun version(): String {
        val result = run("--version")
        if (result.exitCode != 0) {
            throw CommandFailed("$executable --version failed with exit code ${result.exitCode}: ${result.stderr.trim()}")
        }
        return result.stdout.trim()
    }

    companion object {
        /** The ziti executable: `ZITI_CLI` if set, else `ziti` from `PATH`. */
        fun locate(env: Map<String, String> = System.getenv()): String =
            env["ZITI_CLI"]?.takeIf { it.isNotBlank() } ?: "ziti"
    }
}
