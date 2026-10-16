# iOS PQC notes (Phase 1D shell — Phase 3 wires it)

## Surface (stable, from `c_bridge.rs`)

Bind exactly these 10 fns (Kotlin seal framing stays in `CryptoIos.kt`
via the `AesGcm` actual when it lands — there are NO AES fns to bind):

- `pqc_kem_keygen`, `pqc_kem_encaps`, `pqc_kem_decaps`
- `pqc_dsa_keygen`, `pqc_dsa_sign`, `pqc_dsa_verify` (1 valid / 0 invalid / -1 parse error)
- `pqc_bundle_build`, `pqc_bundle_split`
- `pqc_free`, `pqc_secure_zero`

Explicitly NOT bound: `pqc_identity_keygen` (identity assembly stays in
Kotlin), `pqc_link_keygen` / `pqc_link_pub_from_seed` (FindFamily link keys),
`pqc_security_code` (pure SHA-256).

## cinterop `.def` (Phase 3 creates `src/nativeInterop/cinterop/e2eePqc/e2eePqc.def`)

```
headers = e2ee_pqc.h
headerFilter = e2ee_pqc/*
package = e2ee.pqc.native
compilerOpts = -Ie2ee_pqc          # dir holding the cbindgen-generated header
linkerOpts = -le2ee_pqc             # from the XCFramework slice
```

`e2ee_pqc.h` is generated from `c_bridge.rs` via `cbindgen` at Rust build time.

## XCFramework build (Phase 3)

```
cargo build --target aarch64-apple-ios --release
cargo build --target x86_64-apple-ios --release   # simulator
lipo/xcodebuild -create-xcframework ...           # then cinterop against it
```

Memory discipline matches JNA: every `*mut u8 + len` out-param buffer is
`Box::into_raw` — copy to Kotlin, then `pqc_free`; zero priv/SS bytes.
