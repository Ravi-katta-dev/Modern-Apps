plugins {
    id("common-conventions-jvm")
    application
}

dependencies {
    implementation(project(":library:everysync-core"))
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("com.vayunmathur.desktop.notes.MainKt")
}
