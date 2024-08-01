import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties
import com.android.build.gradle.internal.tasks.factory.dependsOn

plugins {
    alias(libs.plugins.android.lib)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = gradleLocalProperties(parent!!.projectDir, providers)

val zitiSdkDir = localProperties["ziti.dir"]
println("ziti-sdk = ${zitiSdkDir}")

val cmakeArgs = mutableListOf("-DDEPS_DIR=${project.buildDir.path}/cmake")
localProperties["ziti.dir"]?.let { cmakeArgs.add("-DZITI_SDK_DIR=$it") }
localProperties["tlsuv.dir"]?.let { cmakeArgs.add("-Dtlsuv_DIR=$it") }
localProperties["tunnel.dir"]?.let { cmakeArgs.add("-Dtunnel_DIR=$it")}

android {
    namespace = "org.openziti.tunnel"
    compileSdk = 34

    defaultConfig {
        minSdk = 26

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
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = properties["cmake.version"].toString()
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

val presets = listOf("arm-android", "arm64-android", "x64-android", "x86-android")

val buildNative = tasks.register("build-native-dependencies") {}

tasks.named("preBuild").dependsOn(buildNative)

presets.forEach { triplet ->
    val task = tasks.register<Exec>("build-native-deps-${triplet}") {
        commandLine("cmake", "--preset", triplet)
    }
    buildNative.dependsOn(task)
}
