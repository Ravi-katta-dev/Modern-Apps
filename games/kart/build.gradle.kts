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

dependencies {
    implementation(project(":sdk:games"))
    implementation(project(":sdk:controller"))
    implementation(project(":library:work"))
    implementation(libs.androidx.datastore.preferences)
}
