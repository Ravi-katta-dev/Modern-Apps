package com.vayunmathur.everysync.core.network

import com.sun.jna.NativeLong
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import com.vayunmathur.everysync.core.crypto.AesGcm
import com.vayunmathur.everysync.core.crypto.BundleFraming

/**
 * Production `PqcCrypto` on desktop JVM: JNA to the `e2ee_pqc` C ABI
 * ([PqcJnaLib]) for keygen/encaps/decaps/sign/verify, Kotlin-side seal
 * framing on the shared `AesGcm` actual.
 *
 * Seal layout (byte-identical to Android `Pqc.encryptTo`):
 * `[4B encapLen BE][encap(1088)][aes]` where `aes = [12B iv][ct||tag]`.
 * The AES key is the 32B shared secret straight from `pqc_kem_encaps`
 * (the Rust `concat_kdf_sha256` already ran inside `mlkem_encaps_der`).
 */
class JvmPqcCrypto(
    private val native: PqcJnaLib = PqcJnaLib.load(),
) : PqcCrypto {

    override fun generateKem(): Pair<ByteArray, ByteArray> {
        val pubOut = PointerByReference()
        val pubLen = NativeLongByReference()
        val privOut = PointerByReference()
        val privLen = NativeLongByReference()
        check(native.pqc_kem_keygen(pubOut, pubLen, privOut, privLen) == 1) { "ML-KEM keygen failed" }
        return take(pubOut, pubLen) to take(privOut, privLen)
    }

    override fun generateDsa(): Pair<ByteArray, ByteArray> {
        val pubOut = PointerByReference()
        val pubLen = NativeLongByReference()
        val privOut = PointerByReference()
        val privLen = NativeLongByReference()
        check(native.pqc_dsa_keygen(pubOut, pubLen, privOut, privLen) == 1) { "ML-DSA keygen failed" }
        return take(pubOut, pubLen) to take(privOut, privLen)
    }

    override fun bundle(kemPub: ByteArray, dsaPub: ByteArray): ByteArray =
        BundleFraming.prefix(kemPub, dsaPub)

    override fun splitBundle(bundle: ByteArray): Pair<ByteArray, ByteArray>? =
        BundleFraming.unprefix(bundle)

    override fun seal(bundle: ByteArray, data: ByteArray): ByteArray {
        val (kemPub, _) = BundleFraming.unprefix(bundle) ?: error("malformed bundle")
        val ctOut = PointerByReference()
        val ctLen = NativeLongByReference()
        val ssOut = PointerByReference()
        val ssLen = NativeLongByReference()
        check(
            native.pqc_kem_encaps(kemPub, NativeLong(kemPub.size.toLong()), ctOut, ctLen, ssOut, ssLen) == 1,
        ) { "ML-KEM encaps failed" }
        val encap = take(ctOut, ctLen)
        val ss = take(ssOut, ssLen)
        try {
            val aes = AesGcm.encrypt(ss, data)
            return BundleFraming.prefix(encap, aes)
        } finally {
            ss.fill(0)
        }
    }

    override fun decrypt(kemPrivate: ByteArray, data: ByteArray): ByteArray {
        val (encap, aes) = BundleFraming.unprefix(data) ?: error("malformed sealed blob")
        val ssOut = PointerByReference()
        val ssLen = NativeLongByReference()
        check(
            native.pqc_kem_decaps(
                kemPrivate, NativeLong(kemPrivate.size.toLong()),
                encap, NativeLong(encap.size.toLong()),
                ssOut, ssLen,
            ) == 1,
        ) { "ML-KEM decaps failed" }
        val ss = take(ssOut, ssLen)
        try {
            return AesGcm.decrypt(ss, aes)
        } finally {
            ss.fill(0)
        }
    }

    override fun sign(dsaPrivate: ByteArray, data: ByteArray): ByteArray {
        val sigOut = PointerByReference()
        val sigLen = NativeLongByReference()
        check(
            native.pqc_dsa_sign(
                dsaPrivate, NativeLong(dsaPrivate.size.toLong()),
                data, NativeLong(data.size.toLong()),
                sigOut, sigLen,
            ) == 1,
        ) { "ML-DSA sign failed" }
        return take(sigOut, sigLen)
    }

    override fun verify(publicBundle: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            val (_, dsaPub) = BundleFraming.unprefix(publicBundle) ?: return false
            native.pqc_dsa_verify(
                dsaPub, NativeLong(dsaPub.size.toLong()),
                data, NativeLong(data.size.toLong()),
                signature, NativeLong(signature.size.toLong()),
            ) == 1
        }.getOrDefault(false)

    /** Copies a Rust-allocated out-buffer into Kotlin and frees the native side. */
    private fun take(out: PointerByReference, len: NativeLongByReference): ByteArray {
        val ptr = out.value ?: error("native returned null")
        val size = len.value.toInt()
        return try {
            ptr.getByteArray(0, size)
        } finally {
            native.pqc_free(ptr, NativeLong(size.toLong()))
        }
    }
}
