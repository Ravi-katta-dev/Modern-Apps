package com.vayunmathur.securemail.domain.mail

import kotlinx.serialization.Serializable

/**
 * Pure address + envelope validation (no Android imports). Own-domain addresses
 * end in @maemail.org; external addresses federate via SMTP with an encryption
 * fallback decided by [KeyLookupPolicy].
 */
object AddressValidator {

    const val OWN_DOMAIN = "maemail.org"

    private val LOCAL_PART = Regex("^[a-z0-9][a-z0-9._-]{1,62}[a-z0-9]$")
    private val EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    /** True for a claimable own-domain local part (lowercased, 3–64 chars). */
    fun isClaimableLocalPart(local: String): Boolean =
        local.length in 3..64 && LOCAL_PART.matches(local.lowercase())

    /** Canonical own-domain address for a local part, or null when invalid. */
    fun canonicalAddress(local: String): String? {
        val clean = local.trim().lowercase()
        return if (isClaimableLocalPart(clean)) "$clean@$OWN_DOMAIN" else null
    }

    /** True for any syntactically valid email address (own or external). */
    fun isValidAddress(address: String): Boolean =
        EMAIL.matches(address.trim()) && address.trim().length <= 254

    /** True when [address] belongs to this relay's domain. */
    fun isOwnDomain(address: String): Boolean =
        address.trim().substringAfterLast('@', "").lowercase() == OWN_DOMAIN

    /** Splits a comma/semicolon recipient line into trimmed non-empty addresses. */
    fun parseRecipients(line: String): List<String> =
        line.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * Envelope metadata for one message. The server stores the envelope + opaque
 * PGP/MIME blob only — never plaintext subject/body. See the Phase 0 wire spec.
 */
@Serializable
data class MessageEnvelope(
    val id: String,
    val from: String,
    val to: List<String>,
    val dateMillis: Long,
    val messageId: String,
    /** Present only for own messages composed unencrypted (fallback opt-in). */
    val unencrypted: Boolean = false,
    val sizeBytes: Long = 0,
    val fetched: Boolean = false,
)

/** Per-recipient send outcome returned by the relay. */
@Serializable
data class SendStatus(
    val recipient: String,
    val queued: Boolean,
    /** "relay" (own-domain live/queued), "federated" (SMTP out), "held". */
    val path: String,
    val detail: String = "",
)
