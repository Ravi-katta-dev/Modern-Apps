plugins {
    id("common-conventions-kmp")
}

dependencies {
    // OkHttp lives only in platform actuals: pinned TLS + WS on Android/JVM
    // (Phase 1C), Darwin/sec-trust on iOS (Phase 3). commonMain stays
    // engine-free behind the RelayHttpClient interface so no HTTP API leaks
    // into the shared merge/vault policy.
    add("androidMainImplementation", libs.okhttp)
    add("jvmMainImplementation", libs.okhttp)
}

// Pinning DERs (FIRST_PARTY bundle: ISRG X1/X2 + GTS R1-R4) as KMP resources.
// The copy runs as a task dependency so a fresh checkout self-heals.
val syncCaResources = tasks.register("syncCaResources", Copy::class.java) {
    description = "Copies FIRST_PARTY pinning DERs from :library:network assets into common resources."
    val caDir = rootProject.file("library/network/src/main/assets/ca")
    val firstParty = listOf(
        "isrgrootx1.der",
        "isrgrootx2.der",
        "gts-root-r1.der",
        "gts-root-r2.der",
        "gts-root-r3.der",
        "gts-root-r4.der",
    )
    onlyIf { caDir.isDirectory }
    from(caDir) { include(firstParty) }
    into(layout.projectDirectory.dir("src/commonMain/resources/ca"))
}

tasks.matching { it.name.startsWith("compile") || it.name.contains("ProcessResources") }.configureEach {
    dependsOn(syncCaResources)
}
