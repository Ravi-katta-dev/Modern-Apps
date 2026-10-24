package com.vayunmathur.securemail.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.util.ensureNotificationChannel
import com.vayunmathur.securemail.data.MailConfig
import com.vayunmathur.securemail.data.MailRepository
import com.vayunmathur.securemail.data.StoredMessage
import com.vayunmathur.securemail.domain.mail.MimeCodec
import com.vayunmathur.securemail.domain.pgp.PgpCrypto
import com.vayunmathur.securemail.network.MailSocket
import com.vayunmathur.securemail.platform.crypto.KeyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Persistent foreground service owning the mail relay socket — the SecureMail
 * twin of FindFamily's LocationTrackingService socket ownership. Started after
 * unlock (or boot, when an identity exists); the socket self-heals with
 * 1s→30s backoff and drains backlog on every (re)SUB.
 */
class MailSocketService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(
            CHANNEL_ID,
            "SecureMail relay",
            android.app.NotificationManager.IMPORTANCE_LOW,
        )
        startForeground(NOTIFICATION_ID, buildNotification())
        scope.launch {
            val settings = MailConfig(this@MailSocketService).settings.first()
            if (settings.address.isBlank()) {
                Log.status(TAG, "no address claimed; socket idle")
                return@launch
            }
            MailSocket.start(
                scope = scope,
                identity = {
                    val current = MailConfig(this@MailSocketService).settings.first()
                    if (current.address.isBlank() || current.publicArmor.isBlank()) null
                    else MailSocket.IdentitySnapshot(current.address, current.publicArmor)
                },
                onMessage = { id, unencrypted, raw ->
                    // Write straight to the Room vault, decrypting immediately:
                    // the S2K passphrase loads transparently from
                    // Keystore-wrapped storage, so arrival-time decrypt needs
                    // no user interaction. The UI observes the vault as a Flow
                    // and shows decrypted content as soon as it lands.
                    val repo = MailRepository.get(this@MailSocketService)
                    val config = MailConfig(this@MailSocketService)
                    val settings = config.settings.first()
                    val keyManager = KeyManager(this@MailSocketService)
                    if (unencrypted) {
                        // Plaintext frames carry the MIME body directly.
                        val text = raw.toString(Charsets.UTF_8)
                        val (subject, body, from) =
                            MimeCodec.parseMime(text)
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
                    } else {
                        val passphrase = keyManager.getKeyPassphrase()
                        val stored = if (passphrase != null) {
                            runCatching {
                                val pgpB64 = MimeCodec.extractPgpPayloadB64(raw)
                                val pgpBytes = if (pgpB64 != null) {
                                    android.util.Base64.decode(
                                        pgpB64, android.util.Base64.DEFAULT,
                                    )
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
                                    blobBase64 = android.util.Base64.encodeToString(
                                        raw, android.util.Base64.NO_WRAP,
                                    ),
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
                                blobBase64 = android.util.Base64.encodeToString(
                                    raw, android.util.Base64.NO_WRAP,
                                ),
                                unencrypted = false,
                            ),
                        )
                    }
                },
            ) {
                // onBump: socket liveness tick — no-op here; UI polls [MailSocket.connected].
            }
        }
    }

    override fun onDestroy() {
        MailSocket.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): android.app.Notification {
        val builder = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SecureMail relay")
            .setContentText("Listening for encrypted mail")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
        return builder.build()
    }

    companion object {
        private const val TAG = "MailSocketService"
        private const val CHANNEL_ID = "securemail_relay"
        private const val NOTIFICATION_ID = 4101

        fun start(context: Context) {
            val intent = Intent(context, MailSocketService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MailSocketService::class.java))
        }
    }
}
