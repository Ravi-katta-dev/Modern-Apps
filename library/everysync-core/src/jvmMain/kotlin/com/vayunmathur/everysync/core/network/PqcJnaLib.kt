package com.vayunmathur.everysync.core.network

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference

/**
 * JNA binding to the `e2ee_pqc` C ABI (`c_bridge.rs`) — the same Rust core
 * (`lib.rs`: ML-KEM-768 + ML-DSA-65) that Android reaches via JNI.
 *
 * C ABI rules (see `c_bridge.rs` header): every buffer-returning fn writes
 * `*mut u8 + size_t len` into caller out-params and returns 1 on success / 0
 * on failure (`dsa_verify` returns 1 valid / 0 invalid / -1 parse error).
 * `size_t` is 64-bit on all 64-bit hosts, hence [NativeLong]/[NativeLongByReference]
 * (NOT `Int`/`IntByReference` — that would truncate lengths on Win64/Linux64).
 * Callers MUST `pqc_free` every returned buffer; private/shared-secret bytes
 * should be `pqc_secure_zero`d after use.
 *
 * Only the 10 fns needed for [PqcCrypto] are bound. Deliberately NOT bound:
 * `identity_keygen` (identity assembly stays in Kotlin via `bundle_build`),
 * `link_keygen`/`link_pub_from_seed` (FindFamily share-link keys — not part
 * of `PqcCrypto`), `security_code` (pure SHA-256, computed in Kotlin if
 * needed). No AES fns exist — seal framing is reproduced in Kotlin on the
 * shared `AesGcm` actual, exactly like Android `Pqc.encryptTo`.
 *
 * Library resolution: `jna.library.path` sysprop → `java.library.path` →
 * `PATH`. Gradle sets `-Djna.library.path` to the host `.dll`/`.so` built
 * from the same crate (see the `buildHostPqcLib` task wiring).
 */
interface PqcJnaLib : Library {
    fun pqc_kem_keygen(
        pubOut: PointerByReference,
        pubLenOut: NativeLongByReference,
        privOut: PointerByReference,
        privLenOut: NativeLongByReference,
    ): Int

    fun pqc_kem_encaps(
        kemPub: ByteArray,
        kemPubLen: NativeLong,
        ctOut: PointerByReference,
        ctLenOut: NativeLongByReference,
        ssOut: PointerByReference,
        ssLenOut: NativeLongByReference,
    ): Int

    fun pqc_kem_decaps(
        kemPriv: ByteArray,
        kemPrivLen: NativeLong,
        ct: ByteArray,
        ctLen: NativeLong,
        ssOut: PointerByReference,
        ssLenOut: NativeLongByReference,
    ): Int

    fun pqc_dsa_keygen(
        pubOut: PointerByReference,
        pubLenOut: NativeLongByReference,
        privOut: PointerByReference,
        privLenOut: NativeLongByReference,
    ): Int

    fun pqc_dsa_sign(
        dsaPriv: ByteArray,
        dsaPrivLen: NativeLong,
        msg: ByteArray,
        msgLen: NativeLong,
        sigOut: PointerByReference,
        sigLenOut: NativeLongByReference,
    ): Int

    fun pqc_dsa_verify(
        dsaPub: ByteArray,
        dsaPubLen: NativeLong,
        msg: ByteArray,
        msgLen: NativeLong,
        sig: ByteArray,
        sigLen: NativeLong,
    ): Int

    fun pqc_bundle_build(
        kemPub: ByteArray,
        kemPubLen: NativeLong,
        dsaPub: ByteArray,
        dsaPubLen: NativeLong,
        out: PointerByReference,
        outLen: NativeLongByReference,
    ): Int

    fun pqc_bundle_split(
        bundle: ByteArray,
        bundleLen: NativeLong,
        kemPubOut: PointerByReference,
        kemPubLenOut: NativeLongByReference,
        dsaPubOut: PointerByReference,
        dsaPubLenOut: NativeLongByReference,
    ): Int

    fun pqc_free(ptr: Pointer, len: NativeLong)

    fun pqc_secure_zero(ptr: Pointer, len: NativeLong)

    companion object {
        /** Loads `e2ee_pqc` (`.dll`/`.so`); throws `UnsatisfiedLinkError` when absent. */
        fun load(): PqcJnaLib =
            Native.load("e2ee_pqc", PqcJnaLib::class.java) as PqcJnaLib
    }
}
