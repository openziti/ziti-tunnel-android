/*
 * Copyright (c) 2025 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.debug

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Debug
import androidx.core.content.FileProvider
import org.openziti.log.NativeLog
import org.openziti.mobile.R
import org.openziti.mobile.ZitiMobileEdgeApp
import org.openziti.tunnel.Keychain
import org.openziti.tunnel.Tunnel
import java.io.PrintWriter
import java.io.StringWriter
import java.io.Writer
import java.net.URI
import java.security.KeyStore.PrivateKeyEntry
import java.security.KeyStore.TrustedCertificateEntry
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

sealed class DebugInfo(val wrap: Boolean = false) {
    abstract val names: Iterable<String>
    abstract fun dump(name: String, output: Writer = StringWriter()): Writer

    data object AppInfoProvider: DebugInfo() {
        override val names = listOf("App Info")
        override fun dump(name: String, output: Writer) = output.apply {
            val pkgInfo = zme.packageManager.getPackageInfo(zme.packageName, 0)
            appendLine("App:             ${zme.packageName}")
            appendLine("App Version:     ${pkgInfo.versionName}(${pkgInfo.longVersionCode})")
            appendLine("Device:          ${Build.MODEL} (${Build.MANUFACTURER})")
            appendLine("Android Version: ${Build.VERSION.RELEASE}")
            appendLine("Android-SDK:     ${Build.VERSION.SDK_INT}")
            appendLine("Ziti Tunnel SDK: ${Tunnel.zitiTunnelVersion()}")
            appendLine("Ziti SDK:        ${Tunnel.zitiSdkVersion()}")
        }
    }

    data object LogCatProvider: DebugInfo() {
        override val names = listOf("log")
        override fun dump(name: String, output: Writer) = output.apply {
            val logcat = Runtime.getRuntime().exec("logcat -d -b crash,main")
            val log = CompletableFuture.supplyAsync { logcat.inputStream.bufferedReader().readText() }
            val err = CompletableFuture.supplyAsync { logcat.errorStream.bufferedReader().readText() }
            val logrc = CompletableFuture.supplyAsync { logcat.waitFor() }

            appendLine("logcat result: ${logrc.get()}")
            write(log.get())
            appendLine()
            appendLine("logcat ERROR:")
            appendLine(err.get())
        }
    }

    data object KeystoreInfo: DebugInfo() {
        override val names = listOf("keystore")

        override fun dump(name: String, output: Writer) = output.apply {
            val keyStore = Keychain.store
            val ids = keyStore.aliases().toList().filter { it.startsWith("ziti://") }
            for (id in ids) {
                appendLine("==== $id ===")
                val entry = keyStore.getEntry(id, null)
                if (entry !is PrivateKeyEntry) {
                    appendLine("Unexpected Entry: $entry")
                    continue
                }

                val uri = URI(id)
                val zitiId = uri.userInfo ?: uri.path.removePrefix("/")
                entry.certificateChain.mapIndexed { idx, c ->
                    if (c is X509Certificate) {
                        appendLine("""
                                |[$idx]: Sub: ${c.subjectDN}
                                |        Iss: ${c.issuerDN}
                                |        Exp: ${c.notAfter}
                                |""".trimMargin()
                        )
                    } else {
                        appendLine("[$idx] $c")
                    }
                }

                appendLine("CA Bundle ========")
                val caAliases = keyStore.aliases().toList().filter { it.startsWith("ziti:$zitiId/") }
                val caCerts = caAliases.map {
                    keyStore.getEntry(it, null)
                }.filterIsInstance<TrustedCertificateEntry>().map { it.trustedCertificate }
                caCerts.forEachIndexed { idx, c ->
                    if (c is X509Certificate) {
                        appendLine("""
                            |[$idx]: Sub: ${c.subjectDN}
                            |        Iss: ${c.issuerDN}
                            |        Exp: ${c.notAfter}
                            |""".trimMargin()
                        )
                    } else {
                        appendLine("$c")
                    }
                }
                appendLine()
            }
        }
    }

    data object MemoryInfo: DebugInfo() {
        override val names: Iterable<String> = listOf("Memory")
        override fun dump(name: String, output: Writer) = output.apply {
            val info = Debug.MemoryInfo()
            Debug.getNativeHeapAllocatedSize()
            Debug.getMemoryInfo(info)
            info.memoryStats.forEach { (k, v) ->
                appendLine("$k -> $v")
            }
        }
    }

    data object ExitInfo: DebugInfo(wrap = true) {
        override val names = listOf("Last Exit")
        override fun dump(name: String, output: Writer) = output.apply {
            with(zme.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    getHistoricalProcessExitReasons(null, 0, 1).firstOrNull()?.let {
                        val ts = Instant.ofEpochMilli(it.timestamp)
                        appendLine("last exit: $ts")
                        appendLine()
                        appendLine("pid         = ${it.pid}")
                        appendLine("reason      = ${it.reason}")
                        appendLine("status      = ${it.status}")
                        appendLine("description = ${it.description}")
                        appendLine()
                        appendLine(it.toString())

                    }
                } else {
                    appendLine("not supported on Android ${Build.VERSION.SDK_INT}")
                }
            }
        }
    }

    data object ZitiDumpInfo: DebugInfo(wrap = true) {
        override val names: Iterable<String>
            get() =
                zme.model.identities().value?.map{it.zitiID} ?: emptyList()

        override fun dump(name: String, output: Writer) = output.apply {
            runCatching {
                val ztx = zme.model.identities().value?.first { it.zitiID == name }
                ztx?.let {
                    zme.model.dumpIdentity(it.id).get()
                } ?: "not found"
            }.onSuccess {
                append(it)
            }.onFailure {
                it.printStackTrace(PrintWriter(output))
            }
        }
    }

    companion object {
        lateinit var zme: ZitiMobileEdgeApp
        fun init(app: ZitiMobileEdgeApp) {
            zme = app
        }
        val providers = listOf(
            AppInfoProvider,
            ExitInfo,
            MemoryInfo,
            LogCatProvider,
            KeystoreInfo,
            ZitiDumpInfo,
        )

        fun feedbackIntent(app: ZitiMobileEdgeApp) = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_EMAIL, arrayOf("developers@openziti.org"))
            putExtra(Intent.EXTRA_SUBJECT, app.getString(R.string.supportEmailSubject))

            val identities = app.model.identities().value ?: emptyList()
            val ids = if (identities.isEmpty()) {
                "no enrollments"
            } else {
                identities.joinToString(separator = "\n") {
                    "\t${it.name.value ?: it.zitiID} - ${it.status.value}"
                }
            }

            val bodyString = """ |${AppInfoProvider.dump("App Info")}Enrollments:
                | 
                | ${ids}
                | """.trimMargin()

            putExtra(Intent.EXTRA_TEXT, bodyString)

            val logDir = app.externalCacheDir!!.resolve("logs")
            logDir.mkdirs()

            val label = DateTimeFormatter.ofPattern("uuuuMMdd-HHmm").format(LocalDateTime.now())
            val logFile = logDir.resolve("feedback-${label}.zip")
            val zip = ZipOutputStream(logFile.outputStream())
            val writer = zip.writer()

            for (p in providers) {
                for (name in p.names) {
                    zip.putNextEntry(ZipEntry(name))
                    p.runCatching { dump(name, writer) }
                        .onFailure {
                            writer.appendLine()
                            writer.appendLine("== WARNING ==")
                            writer.appendLine("Provider[${p.javaClass.name}] has thrown an exception[$it]")
                            it.printStackTrace(PrintWriter(writer))
                        }
                    writer.flush()
                }
            }
            NativeLog.flush()
            app.cacheDir.list { _, name -> name.endsWith(".log") }?.forEach { l ->
                val file = app.cacheDir.resolve(l)
                zip.putNextEntry(
                    ZipEntry("logs/${l}").apply {
                        time = file.lastModified()
                    })
                file.inputStream().use { it.copyTo(zip) }
                writer.flush()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val fmt = DateTimeFormatter.ISO_INSTANT
                with(app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager) {
                    getHistoricalProcessExitReasons(null, 0, 10)
                        .forEachIndexed { idx, it ->
                            val ts = Instant.ofEpochMilli(it.timestamp)
                            val label = "terminations/$idx-exit-${fmt.format(ts)}"
                            zip.putNextEntry(
                                ZipEntry("$label/info").apply {
                                    time = it.timestamp
                                }
                            )
                            writer.appendLine(it.toString())
                            writer.flush()
                            it.traceInputStream?.use { dump ->
                                zip.putNextEntry(
                                    ZipEntry("$label/dump").apply { dump
                                        time = it.timestamp
                                    }
                                )
                                dump.copyTo(zip)
                            }
                        }
                }
            }

            zip.finish()
            zip.flush()
            zip.close()

            putExtra(
                Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                    app,
                    "${app.packageName}.provider", logFile
                )
            )
        }

    }
}
