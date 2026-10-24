package com.vayunmathur.securemail.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.util.ensureNotificationChannel
import com.vayunmathur.securemail.data.InboundStore
import com.vayunmathur.securemail.data.MailConfig
import com.vayunmathur.securemail.network.MailSocket
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
                onMessage = { id, flags, raw ->
                    // Single choke point (decrypt-on-arrival + vault write),
                    // shared with the background sync worker.
                    InboundStore.store(this@MailSocketService, id, flags, raw)
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
