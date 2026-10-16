import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    // AGP 9.x KMP host plugin (replaces com.android.library for
    // multiplatform modules — androidTarget() + com.android.library is
    // rejected when android.newDsl=true). Pairs with kotlin.androidLibrary.
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("dev.detekt")
}

// Static analysis (./check runs `:module:detekt`). Rules live in the committed
// root detekt.yml; findings fail the task (no ignoreFailures).
// Config-cache compatible: the config file is resolved here, never a Project.
detekt {
    config.setFrom(files(rootDir.resolve("detekt.yml")))
}

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

// Shared sync-brain conventions for `:library:everysync-core`.
//
// Targets: android + jvm (desktop) + iosArm64 + iosSimulatorArm64.
// commonMain must stay free of Android APIs — platform coupling goes through
// expect/actual (RelayTransport, SecureStore, SyncScheduler, SyncSink, AesGcm,
// SyncDigest). In particular there is deliberately NO dependency on
// `:library:log` / `:library:network` / `:library:room` here: none of them has
// a common variant, and the shared core must not inherit their Android coupling.
kotlin {
    androidLibrary {
        namespace = "com.vayunmathur.everysync.core"
        compileSdk = 37
        minSdk = 31
    }

    jvm()

    iosArm64()
    iosSimulatorArm64()

    jvmToolchain(21)

    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        named("commonMain") {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
            }
        }
        named("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        named("androidMain") {
            dependencies {
                implementation(libs.kotlinx.coroutines.android)
            }
        }
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Reproducible builds: log SOURCE_DATE_EPOCH if present for verification
providers.environmentVariable("SOURCE_DATE_EPOCH").orNull?.let {
    logger.lifecycle("Reproducible build: SOURCE_DATE_EPOCH=$it")
}
