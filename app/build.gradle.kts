
/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */
import org.jetbrains.kotlin.konan.properties.Properties
import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.android.app)
    alias(libs.plugins.kotlin.android)
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

android {
    namespace = "org.openziti.mobile"
    signingConfigs {
        create("release") {
            keyAlias = ("ziti1")
            keyPassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            storeFile = file(System.getenv("RELEASE_KEYSTORE") ?: "not found")
            storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
        }
    }

    compileSdk = 35

    defaultConfig {
        applicationId = "org.openziti.mobile"
        minSdk = 28
        targetSdk = 35
        versionCode = getVersionCode()
        versionName = "${project.version}"

        buildConfigField("String", "GIT_COMMIT", "\"${gitHash}\"")
        buildConfigField("String", "GIT_BRANCH", "\"${gitBranch}\"")
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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

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
}
