/*
 * Copyright (c) 2025 NetFoundry. All rights reserved.
 */

import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties
import com.android.build.gradle.internal.tasks.factory.dependsOn
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.lib)
    alias(libs.plugins.kotlin.serialization)
}

val rootDir = parent!!.projectDir
val ndk = libs.versions.ndk.get()
val overrides = gradleLocalProperties(rootDir, providers)

// version of the cmake on PATH, null if there is none (a ValueSource: the configuration cache notices a new cmake,
// and a failure to start the process is ours to handle, not a failed build)
abstract class PathCMakeVersion : ValueSource<String, ValueSourceParameters.None> {
    @get:Inject
    abstract val exec: ExecOperations

    override fun obtain(): String? = runCatching {
        val out = ByteArrayOutputStream()
        exec.exec {
            commandLine("cmake", "--version")
            standardOutput = out
        }
        Regex("""cmake version (\S+)""").find(out.toString())?.groupValues?.get(1)
    }.getOrNull()
}

// AGP only accepts the exact version of a cmake it can find, and the dependency build below runs the cmake on PATH:
// use that one, so the installed cmake is the single place that says which version builds the native code.
// -Pcmake.version=<version> overrides it.
val cmakeVersion: String? = providers.gradleProperty("cmake.version").orElse(providers.of(PathCMakeVersion::class){}).orNull
logger.info("native build uses cmake[{}]", cmakeVersion ?: "default")

val cmakeArgs = mutableListOf(
    "-DANDROID_STL=c++_shared",
    "-DDEPS_DIR=${project.layout.buildDirectory.get()}/cmake",
)

private fun Any.resolve(): String  {
    return rootDir.resolve(this.toString()).absolutePath
}
// use local checkouts or custom version if desired
// set in local.properties
// ziti.dir = /home/ziggy/work/ziti-sdk-c
// XXX.dir takes precedence over XXX.version
overrides["tunnel.dir"]?.resolve()?.let { cmakeArgs.add("-Dtunnel_DIR=$it") }
overrides.getOrElse("tunnel.version"){ libs.versions.ziti.tunnel.sdk.get() }.let {
    cmakeArgs.add("-Dtunnel_sdk_VERSION=$it")
}
overrides["ziti.dir"]?.resolve()?.let { cmakeArgs.add("-DZITI_SDK_DIR=$it") }
overrides["ziti.version"]?.let{ cmakeArgs.add("-DZITI_SDK_VERSION=$it") }
overrides["tlsuv.dir"]?.resolve()?.let { cmakeArgs.add("-Dtlsuv_DIR=$it") }

val sanitizer = overrides["sanitize"]
when (sanitizer) {
    "hwasan" -> cmakeArgs.add("-DSANITIZE=$sanitizer")
    null -> logger.debug("Sanitizer is not set")
    else -> logger.warn("Sanitizer[{}] is not supported", sanitizer)
}

android {
    namespace = "org.openziti.tunnel"
    compileSdk = 37
    ndkVersion = ndk

    defaultConfig {
        // VCPKG default triplets target 28 (as of 2025.04.09)
        minSdk = 28

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                arguments(*cmakeArgs.toTypedArray())
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        debug {
            isMinifyEnabled = false
            packaging{
                jniLibs {
                    useLegacyPackaging = true
                }
            }

            ndk {
                isJniDebuggable = true
                if (sanitizer == "hwasan") { // HWASAN only work on arm 64
                    abiFilters.clear()
                    abiFilters.add("arm64-v8a")
                }
            }
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            cmakeVersion?.let { version = it }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

val presets = listOf("arm-android", "arm64-android", "x64-android", "x86-android")

val buildNative = tasks.register("build-native-dependencies") {}

tasks.named("preBuild").dependsOn(buildNative)

if (!hasProperty("skipDependentBuild")) {
    val ndkRoot = androidComponents.sdkComponents.ndkDirectory.get()
    println("using NDK: $ndkRoot")
    presets.forEach { triplet ->
        val task = tasks.register<Exec>("build-native-deps-${triplet}") {
            executable("env")
            args("ANDROID_NDK_ROOT=${ndkRoot}", "cmake", "--preset", triplet)
        }
        buildNative.dependsOn(task)
    }
}