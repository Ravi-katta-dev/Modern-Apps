package com.vayunmathur.share.network.airdrop

import android.annotation.SuppressLint
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * TLS for AirDrop's HTTPS.
 *
 * AirDrop in "Everyone" mode authenticates nobody at the TLS layer: Mosey's server presents a
 * fresh self-signed RSA-2048 certificate and its client trusts any certificate (`cea`, `cev`).
 * Apple peers do the same with their own self-signed identities, so the trust-all client here
 * is the protocol, not a shortcut — sender identity is what the user approves in the Ask.
 */
object AirDropTls {

    private const val VALIDITY_MS = 365L * 24 * 60 * 60 * 1000
    private const val BACKDATE_MS = 60L * 60 * 1000
    private const val KEY_ALIAS = "selfsigned"
    val PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")

    /** Server context with a newly minted self-signed identity. */
    fun serverContext(commonName: String = "AirDrop"): SSLContext {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = selfSignedCertificate(keyPair, commonName)
        val password = CharArray(0)
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry(KEY_ALIAS, keyPair.private, password, arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, password) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
    }

    fun clientContext(): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(TrustAll), SecureRandom()) }

    /** Accepts any peer certificate; see the object doc for why that is the protocol. */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object TrustAll : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** A minimal X.509 v3 certificate, DER-encoded by hand and signed SHA256withRSA. */
    fun selfSignedCertificate(keyPair: KeyPair, commonName: String, now: Long = System.currentTimeMillis()): X509Certificate {
        val sigAlg = Der.seq(Der.oid(SHA256_WITH_RSA), Der.NULL)
        val name = Der.seq(Der.set(Der.seq(Der.oid(COMMON_NAME), Der.utf8(commonName))))
        val tbs = Der.seq(
            Der.explicit(0, Der.int(BigInteger.valueOf(2))),
            Der.int(BigInteger(127, SecureRandom()).add(BigInteger.ONE)),
            sigAlg,
            name,
            Der.seq(Der.utcTime(now - BACKDATE_MS), Der.utcTime(now + VALIDITY_MS)),
            name,
            keyPair.public.encoded,
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }
        val der = Der.seq(tbs, sigAlg, Der.bitString(signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    private val SHA256_WITH_RSA = intArrayOf(1, 2, 840, 113549, 1, 1, 11)
    private val COMMON_NAME = intArrayOf(2, 5, 4, 3)

    private object Der {
        val NULL = byteArrayOf(0x05, 0x00)

        fun seq(vararg parts: ByteArray) = tlv(0x30, concat(parts))
        fun set(vararg parts: ByteArray) = tlv(0x31, concat(parts))
        fun explicit(tag: Int, inner: ByteArray) = tlv(0xA0 or tag, inner)
        fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
        fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
        fun bitString(bytes: ByteArray) = tlv(0x03, byteArrayOf(0) + bytes)

        fun utcTime(millis: Long): ByteArray {
            val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            return tlv(0x17, fmt.format(Date(millis)).toByteArray(Charsets.US_ASCII))
        }

        fun oid(arcs: IntArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(arcs[0] * 40 + arcs[1])
            for (arc in arcs.drop(2)) {
                val stack = ArrayDeque<Int>()
                var v = arc
                stack.addFirst(v and 0x7F)
                v = v ushr 7
                while (v > 0) {
                    stack.addFirst((v and 0x7F) or 0x80)
                    v = v ushr 7
                }
                stack.forEach(out::write)
            }
            return tlv(0x06, out.toByteArray())
        }

        private fun tlv(tag: Int, value: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val len = value.size
            when {
                len < 0x80 -> out.write(len)
                len <= 0xFF -> { out.write(0x81); out.write(len) }
                len <= 0xFFFF -> { out.write(0x82); out.write(len ushr 8); out.write(len and 0xFF) }
                else -> { out.write(0x83); out.write(len ushr 16); out.write(len ushr 8 and 0xFF); out.write(len and 0xFF) }
            }
            out.write(value)
            return out.toByteArray()
        }

        private fun concat(parts: Array<out ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            parts.forEach(out::write)
            return out.toByteArray()
        }
    }
}
