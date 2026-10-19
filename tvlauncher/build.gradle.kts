plugins {
    id("common-conventions-app")
    // androidx.tv, so the home screen is built on a design system that knows what a D-pad is.
    // Named here *alongside* the app convention rather than instead of it, even though it applies
    // that itself: the type-safe `launcherIcon` and `nativeAbis` accessors below are generated
    // from the plugins this file applies directly (the cast:tv precedent).
    id("common-conventions-tv")
    // No preview-metadata convention. The home screen is a TV-shaped fullscreen grid and
    // Layoutlib cannot render it into a *phone* listing image (the cast:tv precedent).
    // metadata_data/tvlauncher.md still exists, which is what generates a listing at all.
}

launcherIcon {
    symbol = "tv"
}

// Google TV hardware (the onn 4K Streaming Box and friends) runs a 32-bit userspace and
// reports only `armeabi-v7a,armeabi`, so an arm64-only APK is rejected at install time.
nativeAbis {
    armv7 = true
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.tvlauncher"
    }
}

dependencies {
    // Pinned-row order + quick settings (the BackupConfig preferencesDataStore pattern, not
    // Room: a launcher row order is a flat list, not relational data).
    implementation(libs.androidx.datastore.preferences)
}
