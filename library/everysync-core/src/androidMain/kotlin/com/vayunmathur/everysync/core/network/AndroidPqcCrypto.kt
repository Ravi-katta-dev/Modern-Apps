package com.vayunmathur.everysync.core.network

import com.vayunmathur.e2ee.Pqc
import com.vayunmathur.everysync.core.crypto.BundleFraming

/**
 * Production `PqcCrypto` on Android: delegates to the `:library:e2ee-p2p`
 * `Pqc` object (ML-KEM-768 + ML-DSA-65 via the `e2ee_pqc` JNI library,
 * DER byte-compatible with the previously-deployed Bouncy Castle encoding).
 *
 * No JNI here — `PqcNative` loading (`System.loadLibrary("e2ee_pqc")`) and
 * the 8 `native*` externals stay in `:library:e2ee-p2p`. This class is pure
 * delegation so the shared `PqcCrypto` interface (and its framing contract)
 * is the only thing `:library:everysync-core` depends on.
 */
class AndroidPqcCrypto : PqcCrypto {
    override fun generateKem(): Pair<ByteArray, ByteArray> = Pqc.generateKem()

    override fun generateDsa(): Pair<ByteArray, ByteArray> = Pqc.generateDsa()

    override fun bundle(kemPub: ByteArray, dsaPub: ByteArray): ByteArray =
        Pqc.bundle(kemPub, dsaPub)

    override fun splitBundle(bundle: ByteArray): Pair<ByteArray, ByteArray>? =
        BundleFraming.unprefix(bundle)

    override fun seal(bundle: ByteArray, data: ByteArray): ByteArray =
        Pqc.encryptTo(bundle, data)

    override fun decrypt(kemPrivate: ByteArray, data: ByteArray): ByteArray =
        Pqc.decrypt(kemPrivate, data)

    override fun sign(dsaPrivate: ByteArray, data: ByteArray): ByteArray =
        Pqc.signWith(dsaPrivate, data)

    override fun verify(publicBundle: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        Pqc.verify(publicBundle, data, signature)
}
