package com.vayunmathur.securemail.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.securemail.data.MailConfig
import com.vayunmathur.securemail.service.MailSocketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Restarts the relay socket after boot/package-replace when an identity exists. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CoroutineScope(Dispatchers.IO).launch {
            val settings = MailConfig(context).settings.first()
            if (settings.address.isNotBlank()) MailSocketService.start(context)
        }
    }
}
