
/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */
import org.jetbrains.kotlin.konan.properties.Properties
import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.android.app)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

fun getCommitHash() = runCatching {
    val stdout = ByteArrayOutputStream()
    exec {
        commandLine("git", "log", "-1", "--format=%h")
        standardOutput = stdout
    }
    stdout.toString().trim()
}.getOrNull()

fun getVersionName() = runCatching {
    val stdout = ByteArrayOutputStream()
    exec {
        commandLine("git", "describe", "--tags", "--dirty")
        standardOutput = stdout
    }
    stdout.toString().trim()
}.getOrElse{ "0.0.0-local" }

fun getVersionCode() =  runCatching {
    val stdout = ByteArrayOutputStream()
    exec {
        commandLine("git", "describe", "--tags", "--match", "v*", "--always")
        standardOutput = stdout
    }
    val version = stdout.toString().trim().replace(Regex("^v"), "")

    val split = version.split(".")

    val (major, minor, pat) = split

    val patchSplit = pat.split("-")
    val patch = patchSplit[0]
    val tweak = if (patchSplit.size > 1) patchSplit[1] else "0"

    val code = ((Integer.parseInt(major) * 100 + Integer.parseInt(minor)) * 100 + Integer.parseInt(patch)) * 100 + Integer.parseInt(tweak)
    code
}.getOrElse{ 1 }

val gitHash = getCommitHash()
val gitBranch = getVersionName()
version = getVersionName()


val wlp = File(rootProject.projectDir, "whitelabel.properties")
val whitelabel = wlp.isFile
val wlProperties = Properties()

if (whitelabel) {
    if (whitelabel) {
        wlProperties.load(wlp.inputStream())
    }
}

val wlAppId = wlProperties["id"]?.toString() ?: "org.openziti.mobile"
val wlOrg = wlProperties["org"]?.toString() ?: "openziti"
val wlResources = wlProperties["resourceDir"]?.toString()

android {
    namespace = "org.openziti.mobile"
    signingConfigs {
        create("release") {
            keyAlias = (System.getenv("RELEASE_KEY_ALIAS") ?: "ziti1")
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: System.getenv("RELEASE_KEYSTORE_PASSWORD")
            storeFile = file(System.getenv("RELEASE_KEYSTORE") ?: "not found")
            storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
        }
    }

    compileSdk = 36
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "org.openziti.mobile"
        minSdk = 28
        targetSdk = 36
        versionCode = getVersionCode()
        versionName = "${project.version}"

        buildConfigField("String", "GIT_COMMIT", "\"${gitHash}\"")
        buildConfigField("String", "GIT_BRANCH", "\"${gitBranch}\"")
    }

    if (whitelabel) {
        flavorDimensions += listOf("whitelabel")
        productFlavors {
            create(wlOrg) {
                dimension = "whitelabel"
                applicationId = wlAppId
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        // Or, if you prefer, you can continue to check for errors in release builds,
        // but continue the build even when errors are found:
        abortOnError = false
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    if (whitelabel && wlResources != null) {
        sourceSets {
            named(wlOrg) {
                res.srcDirs(wlResources)
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":tunnel"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.datastore.prefs)

    implementation(libs.kotlin)
    implementation(libs.coroutines.android)

    implementation(libs.material)
    implementation(libs.constraintlayout)

    implementation(libs.zxing.android.embedded)

    implementation(libs.lifecycle.extensions)
    implementation(libs.appcompat)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.fragment.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)
}
