/*
 * Copyright (c) 2026 NetFoundry. All rights reserved.
 */

package org.openziti.integ

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Picks the device from the output of `adb devices`: [requested] (`ANDROID_SERIAL`) if given, else the only device that
 * is online. Only state `device` counts; `offline` and `unauthorized` devices are ignored.
 */
fun selectSerial(adbDevicesOutput: String, requested: String?): String {
    val devices = adbDevicesOutput.lines()
        .dropWhile { !it.startsWith("List of devices attached") }
        .drop(1)
        .map { it.trim().split(Regex("\\s+")) }
        .filter { it.size >= 2 }
        .map { it[0] to it[1] }
    val online = devices.filter { it.second == "device" }.map { it.first }
    val seen = devices.joinToString { "${it.first} (${it.second})" }.ifEmpty { "none" }

    if (requested != null) {
        check(requested in online) { "device '$requested' (ANDROID_SERIAL) is not online; adb sees: $seen" }
        return requested
    }
    check(online.isNotEmpty()) {
        "no adb device is online: start an emulator or connect a device (or set ANDROID_SERIAL); adb sees: $seen"
    }
    check(online.size == 1) {
        "several adb devices are online (${online.joinToString()}): set ANDROID_SERIAL to choose one"
    }
    return online.single()
}

/**
 * `adb` from the first Android SDK that has one in `platform-tools`: `ANDROID_HOME`, `ANDROID_SDK_ROOT`, then [sdkDir]
 * (the SDK Gradle itself found, through `local.properties`; a developer may have no environment variable at all).
 * Falls back to whatever `adb` is on `PATH`.
 */
internal fun locateAdb(env: Map<String, String>, sdkDir: String? = null): String {
    for (sdk in listOf(env["ANDROID_HOME"], env["ANDROID_SDK_ROOT"], sdkDir)) {
        if (sdk.isNullOrBlank()) continue
        val adb = Path.of(sdk, "platform-tools", "adb")
        if (Files.exists(adb)) return adb.toString()
    }
    return "adb"
}

/** One adb device or emulator, addressed by serial. */
class Device(val adb: String, val serial: String) {

    fun install(apk: Path) {
        exec("install", "-r", "-t", apk.toString())
    }

    /** Makes `127.0.0.1:[port]` on the device reach `127.0.0.1:[port]` on the host. */
    fun reverse(port: Int) {
        exec("reverse", "tcp:$port", "tcp:$port")
    }

    /** Wipes the app data of [pkg] (and its Keystore entries). */
    fun clearPackage(pkg: String) {
        shell("pm clear $pkg")
    }

    fun shell(command: String): String = exec("shell", command)

    /** Like [shell], but hands back what a failing command printed instead of throwing. */
    fun shellResult(command: String): CommandResult = run(listOf(adb, "-s", serial, "shell", command))

    fun clearLogcat() {
        exec("logcat", "-c")
    }

    fun logcat(): String = exec("logcat", "-d")

    private fun run(command: List<String>): CommandResult =
        try {
            execute(command)
        } catch (e: IOException) {
            throw IllegalStateException(adbNotFound(adb, e), e)
        }

    private fun exec(vararg args: String): String {
        val command = listOf(adb, "-s", serial) + args
        val result = run(command)
        if (result.exitCode != 0) {
            throw CommandFailed(
                "${command.joinToString(" ")} failed with exit code ${result.exitCode}: " +
                    result.stderr.ifBlank { result.stdout }.trim()
            )
        }
        return result.stdout
    }

    companion object {
        /**
         * Finds `adb` and the one device to use (`ANDROID_SERIAL` or the only one online); fails loudly otherwise.
         * [sdkDir] is the SDK Gradle found, passed in by the build as `integ.androidSdk`.
         */
        fun connect(
            env: Map<String, String> = System.getenv(),
            sdkDir: String? = System.getProperty("integ.androidSdk"),
        ): Device {
            val adb = locateAdb(env, sdkDir)
            val listing = try {
                execute(listOf(adb, "devices"))
            } catch (e: IOException) {
                throw IllegalStateException(adbNotFound(adb, e), e)
            }
            check(listing.exitCode == 0) { "$adb devices failed with exit code ${listing.exitCode}: ${listing.stderr.trim()}" }
            return Device(adb, selectSerial(listing.stdout, env["ANDROID_SERIAL"]?.takeIf { it.isNotBlank() }))
        }

        private fun adbNotFound(adb: String, cause: IOException) =
            "cannot run adb ('$adb'): ${cause.message}. Set ANDROID_HOME to the Android SDK (adb is taken from " +
                "ANDROID_HOME/platform-tools), or put adb on PATH."
    }
}
