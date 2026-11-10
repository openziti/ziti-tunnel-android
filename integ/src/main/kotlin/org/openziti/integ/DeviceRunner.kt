/*
 * Copyright (c) 2026 NetFoundry. All rights reserved.
 */

package org.openziti.integ

import java.nio.file.Files
import java.nio.file.Path

/** Single-quotes [value] for the device shell; an embedded `'` becomes `'\''`. */
private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

/** The `am instrument` command that runs one test method with [args] as `-e key value` instrumentation arguments. */
fun instrumentCommand(
    testPackage: String,
    runner: String,
    className: String,
    method: String,
    args: Map<String, String>,
): String = buildString {
    append("am instrument -w -r -e class $className#$method")
    for ((key, value) in args) append(" -e $key ${shellQuote(value)}")
    append(" $testPackage/$runner")
}

/** Runs one device test at a time through `am instrument`, the way the C SDK's pytest runs one Catch2 case. */
class DeviceRunner(
    private val device: Device,
    private val testPackage: String = "org.openziti.tunnel.test",
    private val runner: String = "androidx.test.runner.AndroidJUnitRunner",
) {
    /**
     * Runs `className#method` with [args] and throws an [AssertionError] unless it ran and passed. The raw
     * instrumentation output and the logcat of the run are kept in [outDir] as `instrument.txt` and `logcat.txt`,
     * and a failure names both files (in CI the Gradle failure is read before any artifact).
     */
    fun runOrFail(className: String, method: String, args: Map<String, String>, outDir: Path) {
        Files.createDirectories(outDir)
        device.clearLogcat()

        // adb exits non-zero when the instrumentation itself fails (no such runner): keep what it printed
        val result = device.shellResult(instrumentCommand(testPackage, runner, className, method, args))
        val raw = result.stdout

        val instrumentFile = outDir.resolve("instrument.txt")
        val logcatFile = outDir.resolve("logcat.txt")
        Files.writeString(instrumentFile, raw + result.stderr.takeIf { it.isNotBlank() }?.let { "\n--- stderr ---\n$it" }.orEmpty())
        Files.writeString(logcatFile, device.logcat())

        val results = parseInstrumentation(raw)
        try {
            results.requirePassed(className, method)
            if (result.exitCode != 0) throw AssertionError("adb shell exited with code ${result.exitCode}: ${result.stderr.trim()}")
        } catch (e: AssertionError) {
            // no results at all: the instrumentation's own words (INSTRUMENTATION_RESULT / _FAILED) are the only clue
            val tail = if (results.isEmpty()) "\ninstrumentation output (last lines):\n${raw.lines().takeLast(20).joinToString("\n")}" else ""
            throw AssertionError("${e.message}$tail\nevidence: $instrumentFile and $logcatFile", e)
        }
    }
}
