/*
 * Copyright (c) 2026 NetFoundry. All rights reserved.
 */

package org.openziti.integ

import java.util.concurrent.CompletableFuture

data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

class CommandFailed(message: String) : RuntimeException(message)

/**
 * Runs [command] to completion with [env] added to the inherited environment and returns what it printed.
 * An executable that cannot be started surfaces as the `IOException` from [ProcessBuilder.start], so each caller can
 * say which setting to fix.
 */
internal fun execute(command: List<String>, env: Map<String, String> = emptyMap()): CommandResult {
    val builder = ProcessBuilder(command)
    builder.environment().putAll(env)
    val process = builder.start()
    process.outputStream.close()
    // drain stderr on another thread so a full pipe cannot block the process while we read stdout
    val stderr = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readText() }
    val stdout = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    return CommandResult(exitCode, stdout, stderr.get())
}
