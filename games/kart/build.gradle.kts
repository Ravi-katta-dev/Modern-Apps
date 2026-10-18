plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:games:kart:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "directions_car"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.games.kart"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native 3D racer engine (Sunset Oval track, kart physics, chase cam, items +
// AI). Same arrangement as games/voxels: the crate builds into rustJniLibs and
// KartNative.isAvailable gates every call, so devices without the .so run the
// placeholder race. Listing images are hand-captured (Layoutlib can't render
// the Vulkan surface); see the plan's verification notes.
rustNativeLib("kart_engine", "kart")

dependencies {
    implementation(project(":sdk:games"))
    implementation(project(":sdk:controller"))
    implementation(project(":library:work"))
    implementation(project(":library:e2ee-p2p"))
    implementation(libs.androidx.datastore.preferences)
}
