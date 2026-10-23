package com.vayunmathur.securemail.platform

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.work.startRepeatedTask
import com.vayunmathur.securemail.data.MailConfig
import com.vayunmathur.securemail.data.MailRepository
import com.vayunmathur.securemail.network.MailSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.minutes

/**
 * Periodic backstop behind the live socket: while the app is backgrounded
 * without the foreground service, this worker opens a short socket session to
 * drain backlog (SUB → MSG drain → close). No-ops without an identity.
 */
class MailSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = MailConfig(applicationContext).settings.first()
        if (settings.address.isBlank()) return Result.success()
        if (MailSocket.connected) return Result.success()
        return try {
            var drained = 0
            // A dedicated scope for the short drain session (the worker's own
            // coroutine must stay free to enforce the delay/stop below).
            val drainScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            MailSocket.start(
                scope = drainScope,
                identity = {
                    MailSocket.IdentitySnapshot(settings.address, settings.publicArmor)
                },
                onMessage = { id, unencrypted, raw ->
                    val repo = MailRepository.get(applicationContext)
                    repo.messageDao().upsert(
                        com.vayunmathur.securemail.data.StoredMessage(
                            id = id,
                            from = "",
                            toJson = "[\"${settings.address}\"]",
                            dateMillis = System.currentTimeMillis(),
                            messageId = id,
                            blobBase64 = android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP),
                            unencrypted = unencrypted,
                        ),
                    )
                    drained++
                },
            )
            kotlinx.coroutines.delay(20_000)
            MailSocket.stop()
            drainScope.cancel()
            Log.dev(TAG, "sync worker drained $drained")
            Result.success()
        } catch (e: Exception) {
            Log.status(TAG, "sync worker failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "MailSyncWorker"
        private const val WORK_NAME = "securemail_sync"

        fun schedule(context: Context) {
            startRepeatedTask<MailSyncWorker>(context, WORK_NAME, 15.minutes)
        }
    }
}
