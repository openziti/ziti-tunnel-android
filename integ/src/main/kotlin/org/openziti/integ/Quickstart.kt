/*
 * Copyright (c) 2026 NetFoundry. All rights reserved.
 */

package org.openziti.integ

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A free TCP port on the loopback interface: bind port 0, read the number, release it. */
fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

/**
 * A `ziti edge quickstart` controller + router running as a child process.
 *
 * Callers build [cli] with a config dir of its own (by convention `home/cli`): quickstart logs that CLI in, and the
 * login must not land in the developer's own CLI config.
 */
class Quickstart(
    private val cli: ZitiCli,
    val home: Path,
    val controllerPort: Int = freePort(),
    val routerPort: Int = freePort(),
    private val readyTimeout: Duration = 300.seconds,
) : AutoCloseable {

    val controllerUrl: String = "https://127.0.0.1:$controllerPort"

    /** Combined stdout and stderr of the quickstart process. */
    val log: Path = home.resolve("qs.log")

    private var process: Process? = null
    private var reader: Thread? = null
    private var shutdownHook: Thread? = null

    /** Starts quickstart and returns once it reports `controller and router started.`; fails (and cleans up) otherwise. */
    fun start(): Quickstart {
        check(process == null) { "quickstart was already started" }
        Files.createDirectories(home)

        val builder = ProcessBuilder(
            cli.executable, "edge", "quickstart",
            "--home", home.toString(),
            "--ctrl-address=127.0.0.1",
            "--router-address=127.0.0.1",
            "--ctrl-port=$controllerPort",
            "--router-port=$routerPort",
        )
        builder.redirectErrorStream(true)
        builder.environment().putAll(cli.environment())
        val started = try {
            builder.start()
        } catch (e: IOException) {
            throw IllegalStateException(
                "cannot run '${cli.executable}': ${e.message}. Set ZITI_CLI to the ziti executable or put ziti on PATH.", e
            )
        }
        process = started
        started.outputStream.close()
        // A JVM that is terminated (Ctrl-C on the Gradle run, a killed test worker) does not kill its child processes:
        // without this the quickstart would keep running, holding its ports.
        val hook = Thread({ terminate(started) }, "quickstart-shutdown")
        Runtime.getRuntime().addShutdownHook(hook)
        shutdownHook = hook

        val ready = CompletableFuture<Unit>()
        val pump = Thread({ pump(started, ready) }, "quickstart-log")
        pump.isDaemon = true
        pump.start()
        reader = pump

        val deadline = System.nanoTime() + readyTimeout.inWholeNanoseconds
        while (!ready.isDone) {
            if (!started.isAlive) {
                pump.join(2_000) // let the last output reach the log and the ready check
                if (ready.isDone) break
                fail("quickstart exited with code ${started.exitValue()} before it was ready; see $log")
            }
            if (System.nanoTime() > deadline) {
                fail("quickstart was not ready within $readyTimeout; see $log")
            }
            started.waitFor(50, TimeUnit.MILLISECONDS)
        }
        return this
    }

    /** Terminates the process (killing it after 10 s). Safe to call repeatedly, or without [start]. */
    override fun close() {
        val running = process ?: return
        terminate(running)
        reader?.join(2_000)
        shutdownHook?.let {
            try {
                Runtime.getRuntime().removeShutdownHook(it)
            } catch (_: IllegalStateException) {
                // the JVM is already shutting down: the hook runs (or has run) and terminate() is idempotent
            }
        }
        shutdownHook = null
    }

    private fun terminate(running: Process) {
        if (running.isAlive) {
            running.destroy()
            if (!running.waitFor(10, TimeUnit.SECONDS)) {
                running.destroyForcibly()
                running.waitFor()
            }
        }
    }

    private fun fail(message: String): Nothing {
        close()
        throw IllegalStateException(message)
    }

    private fun pump(process: Process, ready: CompletableFuture<Unit>) {
        try {
            Files.newBufferedWriter(log).use { out ->
                process.inputStream.bufferedReader().forEachLine { line ->
                    out.write(line)
                    out.newLine()
                    out.flush()
                    if (READY in line) ready.complete(Unit)
                }
            }
        } catch (_: IOException) {
            // the stream closes when the process is terminated
        }
    }

    private companion object {
        const val READY = "controller and router started."
    }
}
