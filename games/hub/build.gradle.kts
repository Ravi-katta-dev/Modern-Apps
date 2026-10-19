plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:games:hub:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "stadia_controller"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.games.hub"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Yuu 3D avatar engine (glTF upper-body base mesh, skeletal skinning, preset
// poses, Vulkan-first + GLES fallback). Same arrangement as games/kart: the
// crate builds into rustJniLibs and YuuAvatarNative.isAvailable gates every
// call, so devices without the .so keep the 2D YuuFace preview. The Vulkan
// swapchain bring-up is still landing (new() reports unavailable); the GLES
// path is complete.
rustNativeLib("yuu_avatar", "yuu")

dependencies {
    implementation(project(":library"))
    implementation(project(":library:ui"))
    implementation(project(":library:room"))
    implementation(project(":sdk:games"))
    // "Play on TV": the generic phone-as-controller SDK (pad UI + discovery +
    // client) plus PQC sealing for the handshake's session secret.
    implementation(project(":sdk:controller"))
    implementation(project(":library:e2ee-p2p"))
    implementation(libs.androidx.datastore.preferences)
    implementRoom(libs)
}
