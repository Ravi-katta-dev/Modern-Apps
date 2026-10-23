package com.vayunmathur.securemail.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Immutable snapshot of the SecureMail account state. */
data class MailSettings(
    val address: String = "",
    val publicArmor: String = "",
    val fingerprintHex: String = "",
    val lastSyncMillis: Long = 0L,
) {
    val isConfigured: Boolean get() = address.isNotBlank()
}

private val Context.mailDataStore by preferencesDataStore(name = "securemail_config")

/** DataStore-backed persistence for [MailSettings] (non-secret fields only). */
class MailConfig(private val context: Context) {
    val settings: Flow<MailSettings> = context.mailDataStore.data.map { p ->
        MailSettings(
            address = p[KEY_ADDRESS] ?: "",
            publicArmor = p[KEY_PUBLIC] ?: "",
            fingerprintHex = p[KEY_FINGERPRINT] ?: "",
            lastSyncMillis = p[KEY_LAST_SYNC] ?: 0L,
        )
    }

    suspend fun setIdentity(address: String, publicArmor: String, fingerprintHex: String) {
        context.mailDataStore.edit {
            it[KEY_ADDRESS] = address
            it[KEY_PUBLIC] = publicArmor
            it[KEY_FINGERPRINT] = fingerprintHex
        }
    }

    suspend fun setLastSync(epochMillis: Long) {
        context.mailDataStore.edit { it[KEY_LAST_SYNC] = epochMillis }
    }

    suspend fun clear() {
        context.mailDataStore.edit { it.clear() }
    }

    companion object {
        private val KEY_ADDRESS = stringPreferencesKey("address")
        private val KEY_PUBLIC = stringPreferencesKey("public_armor")
        private val KEY_FINGERPRINT = stringPreferencesKey("fingerprint_hex")
        private val KEY_LAST_SYNC = longPreferencesKey("last_sync")
    }
}
