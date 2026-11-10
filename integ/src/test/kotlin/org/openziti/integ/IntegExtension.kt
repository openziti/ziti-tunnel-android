/*
 * Copyright (c) 2026 NetFoundry. All rights reserved.
 */

package org.openziti.integ

import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import java.nio.file.Files
import java.nio.file.Path

/** The test APK that carries the device tests; its package is what `pm clear` and `am instrument` address. */
const val TEST_PACKAGE = "org.openziti.tunnel.test"

/**
 * What a device integration test works with: the ziti [model], the [device], the [runner] that drives it, and the
 * [outDir] that collects logs. Started once per test run and closed (quickstart terminated) when the run ends.
 */
class IntegEnv private constructor(
    val model: Model,
    val device: Device,
    val outDir: Path,
    private val quickstart: Quickstart,
) : AutoCloseable {
    val runner = DeviceRunner(device, TEST_PACKAGE)

    override fun close() = quickstart.close()

    companion object {
        /** Fails fast (before starting anything slow) when there is no device or no ziti CLI. */
        fun start(): IntegEnv {
            val outDir = Path.of(property("integ.outDir"))
            val apkDir = Path.of(property("integ.testApkDir"))
            val apk = Files.list(apkDir).use { files -> files.filter { it.toString().endsWith(".apk") }.toList() }
                .singleOrNull() ?: error("expected exactly one test APK in $apkDir")

            val device = Device.connect()
            outDir.toFile().deleteRecursively() // a quickstart home from an earlier run must not be reused
            val session = Files.createDirectories(outDir.resolve("session"))
            val cli = ZitiCli(ZitiCli.locate(), session.resolve("cli"))
            Files.writeString(session.resolve("ziti-version.txt"), cli.version() + "\n")

            val quickstart = Quickstart(cli, session).start()
            try {
                val model = Model(cli, session.resolve("jwt"))
                model.createBase()
                device.install(apk)
                device.reverse(quickstart.controllerPort)
                device.reverse(quickstart.routerPort)
                return IntegEnv(model, device, outDir, quickstart)
            } catch (e: Throwable) {
                quickstart.close()
                throw e
            }
        }

        private fun property(name: String): String =
            System.getProperty(name) ?: error("system property $name is not set: run through ./gradlew :integ:integrationTest")
    }
}

/** Hands tests an [IntegEnv] parameter; it is created on first use and shared by every test of the run. */
class IntegExtension : ParameterResolver {
    override fun supportsParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Boolean =
        parameterContext.parameter.type == IntegEnv::class.java

    override fun resolveParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Any =
        extensionContext.root.getStore(NAMESPACE).computeIfAbsent("env", { IntegEnv.start() }, IntegEnv::class.java)

    private companion object {
        val NAMESPACE: ExtensionContext.Namespace = ExtensionContext.Namespace.create(IntegExtension::class.java)
    }
}
