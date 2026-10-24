package com.vayunmathur.securemail.data

import android.content.Context
import android.util.Base64
import com.vayunmathur.securemail.domain.mail.MimeCodec
import com.vayunmathur.securemail.domain.pgp.PgpCrypto
import com.vayunmathur.securemail.platform.crypto.KeyManager
import kotlinx.coroutines.flow.first

/**
 * Single choke point for inbound socket frames (relay MSG + SMTP-ingested
 * blobs): writes to the Room vault, decrypting immediately when the Keystore
 * holds the S2K passphrase. Used by both [MailSocketService] (live socket)
 * and [MailSyncWorker] (background drain) so arrival behavior is identical
 * everywhere — no caller duplicates this logic.
 */
object InboundStore {

    /**
     * Stores one inbound frame. Encrypted frames decrypt on arrival (S2K
     * passphrase loads transparently from Keystore-wrapped storage);
     * unencrypted frames parse their MIME envelope directly; undecryptable
     * frames persist as locked blobs for later retry.
     */
    suspend fun store(
        context: Context,
        id: String,
        unencrypted: Boolean,
        raw: ByteArray,
    ) {
        val app = context.applicationContext
        val repo = MailRepository.get(app)
        val settings = MailConfig(app).settings.first()
        val keyManager = KeyManager(app)
        if (unencrypted) {
            val (subject, body, from) = MimeCodec.parseMime(raw.toString(Charsets.UTF_8))
            repo.messageDao().upsert(
                StoredMessage(
                    id = id,
                    from = from,
                    toJson = settings.address,
                    dateMillis = System.currentTimeMillis(),
                    messageId = id,
                    blobBase64 = "",
                    decryptedBody = body,
                    decryptedSubject = subject,
                    unencrypted = true,
                ),
            )
            return
        }
        val passphrase = keyManager.getKeyPassphrase()
        val stored = if (passphrase != null) {
            runCatching {
                val pgpB64 = MimeCodec.extractPgpPayloadB64(raw)
                val pgpBytes = if (pgpB64 != null) {
                    Base64.decode(pgpB64, Base64.DEFAULT)
                } else {
                    raw
                }
                val mime = PgpCrypto.decrypt(
                    pgpBytes,
                    keyManager.getSecretArmor(),
                    passphrase,
                ).toString(Charsets.UTF_8)
                val (subject, body, from) = MimeCodec.parseMime(mime)
                StoredMessage(
                    id = id,
                    from = from,
                    toJson = settings.address,
                    dateMillis = System.currentTimeMillis(),
                    messageId = id,
                    blobBase64 = Base64.encodeToString(raw, Base64.NO_WRAP),
                    decryptedBody = body,
                    decryptedSubject = subject,
                    unencrypted = false,
                )
            }.getOrNull()
        } else {
            null
        }
        repo.messageDao().upsert(
            stored ?: StoredMessage(
                id = id,
                from = "",
                toJson = settings.address,
                dateMillis = System.currentTimeMillis(),
                messageId = id,
                blobBase64 = Base64.encodeToString(raw, Base64.NO_WRAP),
                unencrypted = false,
            ),
        )
    }
}
