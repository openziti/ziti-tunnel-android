/*
 * Copyright (c) 2026 NetFoundry. All rights reserved.
 */

import java.util.Properties

// Host-side harness for the device integration tests: it starts a real ziti quickstart, drives an
// attached Android device/emulator and reports the results. See the design under .claude/android/specs.

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// `test` runs whatever is not tagged `integration`: nothing in `check` may need a ziti CLI or a device.
// The harness has no tests of its own, so today every test in this module is tagged and `test` finds none.
tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
    failOnNoDiscoveredTests = false
}

// `sdk.dir` of the root local.properties, or "" when there is none
val androidSdk: Provider<String> = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText
    .map { text -> Properties().apply { load(text.reader()) }.getProperty("sdk.dir").orEmpty() }
    .orElse("")

// Needs a ziti CLI and one attached device/emulator. Deliberately not wired into `check`.
val integrationTest by tasks.registering(Test::class) {
    description = "Runs the device integration tests (needs a ziti CLI and one adb device)"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }

    // the harness installs the :tunnel androidTest APK on the device
    dependsOn(":tunnel:assembleDebugAndroidTest")
    systemProperty(
        "integ.testApkDir",
        project(":tunnel").layout.buildDirectory.dir("outputs/apk/androidTest/debug").get().asFile.absolutePath
    )
    systemProperty("integ.outDir", layout.buildDirectory.dir("integ").get().asFile.absolutePath)
    // the SDK Gradle itself uses (local.properties sdk.dir): a developer may have no ANDROID_HOME for the harness to find adb by
    systemProperty("integ.androidSdk", androidSdk.get())

    // a run depends on the device and the ziti CLI, which Gradle cannot see: never skip it as up to date
    outputs.upToDateWhen { false }
}
