package com.vayunmathur.everysync.core.crypto

/**
 * Length-prefix framing shared by every target, byte-identical to Android
 * `Pqc.lenPrefix/unLenPrefix` (and the Rust `c_bridge` helpers):
 * `[4B big-endian length of a][a][b]`.
 *
 * Used for public bundles (`[kemPub][dsaPub]`) and sealed blobs
 * (`[encap(1088)][aes]`, where `aes = [12B iv][ct||tag]`).
 */
object BundleFraming {
    private const val LEN_PREFIX_BYTES = 4
    private const val BYTE_MASK = 0xFF

    fun prefix(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(LEN_PREFIX_BYTES + a.size + b.size)
        out[0] = (a.size ushr 24).toByte()
        out[1] = (a.size ushr 16).toByte()
        out[2] = (a.size ushr 8).toByte()
        out[3] = a.size.toByte()
        a.copyInto(out, LEN_PREFIX_BYTES)
        b.copyInto(out, LEN_PREFIX_BYTES + a.size)
        return out
    }

    /** Splits `[a][b]`; null when malformed (short header or truncated first part). */
    fun unprefix(x: ByteArray): Pair<ByteArray, ByteArray>? {
        if (x.size < LEN_PREFIX_BYTES) return null
        val len = ((x[0].toInt() and BYTE_MASK) shl 24) or
            ((x[1].toInt() and BYTE_MASK) shl 16) or
            ((x[2].toInt() and BYTE_MASK) shl 8) or
            (x[3].toInt() and BYTE_MASK)
        if (LEN_PREFIX_BYTES + len > x.size) return null
        return x.copyOfRange(LEN_PREFIX_BYTES, LEN_PREFIX_BYTES + len) to
            x.copyOfRange(LEN_PREFIX_BYTES + len, x.size)
    }
}
