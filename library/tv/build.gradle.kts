plugins {
    id("common-conventions-library")
}
dependencies {
    implementation(project(":library"))
    // Icons come from :library:ui; deliberately no :library:image so the offline cast receiver
    // does not inherit INTERNET. Image-bearing components take a slot instead of a URL.
    implementation(project(":library:ui"))
    api(libs.androidx.tv.material)
    implementation(libs.androidx.compose.foundation)
}
