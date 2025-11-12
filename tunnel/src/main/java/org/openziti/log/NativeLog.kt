/*
 * Copyright (c) 2025 NetFoundry. All rights reserved.
 */

package org.openziti.log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

object NativeLog: Timber.DebugTree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (!isLoggable(tag, priority))
            return

        val logTag = tag ?: "native"
        logNative(priority, logTag, message)
        t?.let {
            logNative(priority, logTag, it.stackTraceToString())
        }
    }

    fun setupLogging(dir: String) {
        setupLogging0(dir)
        CoroutineScope(Dispatchers.IO).launch {
            while(true) {
                cleanupLogs(dir)
                delay(24.hours)
                startNewFile()
            }
        }
    }

    internal fun cleanupLogs(dir: String) {
        val now = System.currentTimeMillis()
        val oldLogs = File(dir).listFiles {
            it.name.endsWith(".log") && (now - it.lastModified()).milliseconds > 7.days
        }

        oldLogs?.forEach {
            it.delete()
        }
    }

    @JvmStatic
    external fun logNative(priority: Int, tag: String, msg: String)

    @JvmStatic
    external fun setLogLevel(level: Int)

    @JvmStatic
    external fun setupLogging0(dir: String)

    @JvmStatic
    external fun startNewFile()
}