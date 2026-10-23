plugins {
    id("common-conventions-app")
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "movie"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.uniflix"
    }
}

dependencies {
    implementation(project(":library:network"))
    implementation(project(":library:image"))
    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui.compose.material3)
    implementation(libs.androidx.datastore.preferences)
    // JS plugin runtime: QuickJS engine + pure-Java WASM host (Chicory).
    implementation(libs.quickjs.kt)
    implementation(libs.chicory.runtime)
}

// Desktop JVM unit tests cannot load the Android QuickJS .so: run them against
// the -jvm artifact (per quickjs-kt README) while the app keeps the -android AAR.
// configureEach (not named) so this holds however AGP creates the test configs.
configurations.configureEach {
    if (name.endsWith("UnitTestRuntimeClasspath")) {
        resolutionStrategy.dependencySubstitution {
            substitute(module("io.github.dokar3:quickjs-kt-android"))
                .using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickjs.get()}"))
        }
    }
}
