plugins {
    id("common-conventions-jvm")
    application
}

dependencies {
    implementation(project(":library:everysync-core"))
    implementation(libs.kotlinx.coroutines.core)
    // EDS native bridge stub (EdsBridge JNA shapes for libecal/libebook);
    // inactive until a Linux lib links (see EdsBridge.isAvailable).
    implementation(libs.jna)
}

application {
    mainClass.set("com.vayunmathur.desktop.notes.MainKt")
}
