plugins {
    id("common-conventions-jvm")
    application
}

dependencies {
    implementation(project(":library:everysync-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.jna)
}

application {
    mainClass.set("com.vayunmathur.desktop.passwords.MainKt")
}
