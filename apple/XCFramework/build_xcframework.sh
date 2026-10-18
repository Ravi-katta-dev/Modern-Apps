#!/bin/sh
# EverySync XCFramework build (Phase 3, Mac only).
# Wraps BUILD_NOTES.md steps 1-4. Fails fast; prints the manual fallback.
# Usage (repo root): sh apple/XCFramework/build_xcframework.sh
set -eu

RUST_MANIFEST="library/e2ee-p2p/src/main/rust/Cargo.toml"
INTEROP_DIR="library/everysync-core/src/nativeInterop/cinterop/e2eePqc"

echo "==> 1/4 Rust static libs (device + simulator ARM64)"
cargo build --target aarch64-apple-ios --release --manifest-path "$RUST_MANIFEST"
cargo build --target aarch64-apple-ios-sim --release --manifest-path "$RUST_MANIFEST"

echo "==> 2/4 cbindgen header"
if ! command -v cbindgen >/dev/null 2>&1; then
  echo "missing cbindgen (cargo install cbindgen)" >&2
  exit 1
fi
cbindgen --crate e2ee-p2p --output "$INTEROP_DIR/e2ee_pqc.h" \
  --config library/e2ee-p2p/src/main/rust/cbindgen.toml

echo "==> 3/4 cinterop .def already in $INTEROP_DIR/e2eePqc.def (verbatim from IOS_PQC_NOTES.md)"
test -f "$INTEROP_DIR/e2eePqc.def"

echo "==> 4/4 XCFramework via Gradle"
./gradlew :library:everysync-core:assembleEverysyncCoreXCFramework

echo "OK: EverysyncCore.xcframework built. Link it into Passwords, Notes,"
echo "Hub, the Credential Provider extension, and the Share extension."
