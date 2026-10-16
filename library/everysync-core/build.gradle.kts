import org.gradle.api.tasks.testing.Test

plugins {
    id("common-conventions-kmp")
}

dependencies {
    // OkHttp lives only in platform actuals: pinned TLS + WS on Android/JVM
    // (Phase 1C), Darwin/sec-trust on iOS (Phase 3). commonMain stays
    // engine-free behind the RelayHttpClient interface so no HTTP API leaks
    // into the shared merge/vault policy.
    add("androidMainImplementation", libs.okhttp)
    add("androidMainImplementation", project(":library:e2ee-p2p"))
    add("jvmMainImplementation", libs.okhttp)
    add("jvmMainImplementation", libs.jna)
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

// BC test vectors (ML-KEM + ML-DSA DER lens/hex, CT, SS, sig) are owned by
// :library:e2ee-p2p's Rust crate; jvmTest consumes a synced copy so the file
// has exactly one source of truth.
val syncPqcVectors = tasks.register("syncPqcVectors", Copy::class.java) {
    description = "Syncs BC PQC vectors into jvmTest resources."
    val vectors = rootProject.file("library/e2ee-p2p/src/main/rust/testvectors/bc_pqc_vectors.txt")
    onlyIf { vectors.isFile }
    from(vectors)
    into(layout.projectDirectory.dir("src/jvmTest/resources"))
}

// Host build of the e2ee_pqc C ABI for desktop JVM (JNA) + JVM tests.
// Android .so packaging is untouched (rustNativeLib in :library:e2ee-p2p):
// this builds the SAME crate for the host target into build/rustHostLibs,
// which jvmTest/jvmRun consume via -Djna.library.path (never a hardcoded path).
//
// Toolchain note: Windows hosts use the GNU triple (x86_64-pc-windows-gnu,
// MinGW — matches the installed toolchain; MSVC would need
// `rustup target add x86_64-pc-windows-msvc` + VS Build Tools). The cdylib
// loads identically under JNA. See rust-toolchain.toml for the pin.
// Config-cache compatible: only plain strings cross into the task action.
val hostRustTriple: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val isArm = arch.contains("aarch64") || arch.startsWith("arm")
    when {
        os.contains("win") -> if (isArm) "aarch64-pc-windows-gnullvm" else "x86_64-pc-windows-gnu"
        os.contains("mac") -> if (isArm) "aarch64-apple-darwin" else "x86_64-apple-darwin"
        else -> if (isArm) "aarch64-unknown-linux-gnu" else "x86_64-unknown-linux-gnu"
    }
}
val hostLibFileName: String = when {
    System.getProperty("os.name").lowercase().contains("win") -> "e2ee_pqc.dll"
    System.getProperty("os.name").lowercase().contains("mac") -> "libe2ee_pqc.dylib"
    else -> "libe2ee_pqc.so"
}

val hostLibRelPath: String = "rustHostLibs/$hostLibFileName"
val builtLibRelPath: String = "target/$hostRustTriple/release/$hostLibFileName"

val buildHostPqcLib = tasks.register("buildHostPqcLib", Exec::class.java) {
    description = "Builds the e2ee_pqc C ABI for the host (JNA/cinterop consumers)."
    workingDir = rootProject.file("library/e2ee-p2p/src/main/rust")
    val cargoBin = "${System.getProperty("user.home")}/.cargo/bin"
    val rustSrc = rootProject.file("library/e2ee-p2p/src/main/rust").absolutePath
    inputs.dir("$rustSrc/src")
    inputs.file("$rustSrc/Cargo.toml")
    inputs.file(rootProject.file("Cargo.toml"))
    inputs.file(rootProject.file("Cargo.lock"))
    inputs.file(rootProject.file("rust-toolchain.toml"))
    outputs.file(layout.buildDirectory.file(hostLibRelPath))
    val isWindowsHost = System.getProperty("os.name").lowercase().contains("win")
    val pathSep = if (isWindowsHost) ";" else ":"
    val exeExt = if (isWindowsHost) ".exe" else ""
    environment("PATH", "$cargoBin$pathSep${System.getenv("PATH")}")
    environment("RUSTC", "$cargoBin/rustc$exeExt")
    environment(
        "RUSTFLAGS",
        "--remap-path-prefix=${System.getenv("CARGO_HOME") ?: "${System.getProperty("user.home")}/.cargo"}=/cargo" +
            " --remap-path-prefix=$rustSrc=/e2ee-pqc",
    )
    environment("CARGO_INCREMENTAL", "0")
    commandLine("$cargoBin/cargo$exeExt", "build", "--locked", "--release", "--target", hostRustTriple)
    // Config-cache safe: no Project/File captured — the copy runs in a
    // separate cache-safe task below.
}

val installHostPqcLib = tasks.register("installHostPqcLib", Copy::class.java) {
    description = "Copies the built host e2ee_pqc lib into build/rustHostLibs."
    dependsOn(buildHostPqcLib)
    from(rootProject.file(builtLibRelPath))
    into(layout.buildDirectory.dir("rustHostLibs"))
}

tasks.matching { it.name.startsWith("compile") || it.name.contains("ProcessResources") }.configureEach {
    dependsOn(syncCaResources)
    dependsOn(syncPqcVectors)
}

tasks.withType<Test>().matching { it.name == "jvmTest" || it.name == "jvmRun" }.configureEach {
    dependsOn("installHostPqcLib")
    dependsOn(syncPqcVectors)
    val hostLibDir = layout.buildDirectory.dir("rustHostLibs").get().asFile.absolutePath
    // systemProperty resolves the dir at execution time; the plain string keeps
    // the configuration cache happy.
    systemProperty("jna.library.path", hostLibDir)
}
