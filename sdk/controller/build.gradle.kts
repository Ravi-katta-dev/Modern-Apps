plugins {
    id("common-conventions-library")
}

dependencies {
    // Hkdf: the whole key schedule expands from one 32-byte session secret.
    implementation(project(":library"))
    implementation(project(":library:e2ee-p2p"))
    // ControllerPad is built from the shared wrappers (Text/Button/OutlinedTextField), so the
    // phone UI restyles with everything else. Acyclic: :library:ui never depends back on SDKs.
    implementation(project(":library:ui"))
}
