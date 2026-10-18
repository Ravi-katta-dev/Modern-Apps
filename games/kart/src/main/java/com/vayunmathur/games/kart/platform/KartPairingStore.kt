package com.vayunmathur.games.kart.platform

import android.content.Context
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vayunmathur.e2ee.E2eeKeyStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.kartIdentityStore by preferencesDataStore(name = "kart_identity")

/**
 * The TV's long-term secrets: its own ML-KEM identity, and one device key per paired phone.
 *
 * Follows the cast/tv `PairingStore` shape (an [E2eeKeyStore] for
 * `PqcIdentity.loadOrCreate`, plus remembered device keys), with one deliberate
 * difference: keys live in their own `kart_identity` DataStore file, NOT in the
 * shared `datastore_default` that [AppBackupAgent] ships to cloud. Restoring
 * identity keys onto a different TV would let that TV impersonate this one to
 * an already-paired phone, with no code shown to give it away — so the file
 * stays off the backup list by living outside `datastoreNames`.
 *
 * Suspend getters throughout (never the snapshot): this is read on a cold
 * lobby start, where an eagerly-mirrored snapshot has not hydrated.
 */
class KartPairingStore(private val context: Context) : E2eeKeyStore {

    // --- E2eeKeyStore, for PqcIdentity.loadOrCreate ---

    override suspend fun getBytes(name: String): ByteArray? =
        context.kartIdentityStore.data.map { it[byteArrayPreferencesKey("kart_$name")] }.first()

    override suspend fun setBytes(name: String, value: ByteArray, onlyIfAbsent: Boolean) {
        context.kartIdentityStore.edit {
            val key = byteArrayPreferencesKey("kart_$name")
            if (onlyIfAbsent && it.contains(key)) return@edit
            it[key] = value
        }
    }

    // --- remembered phones ---

    /** The device key for [senderId], or null when this phone has never paired. */
    suspend fun deviceKey(senderId: String): ByteArray? =
        context.kartIdentityStore.data.map { it[byteArrayPreferencesKey(deviceKeyName(senderId))] }.first()

    suspend fun remember(senderId: String, deviceKey: ByteArray) {
        context.kartIdentityStore.edit { it[byteArrayPreferencesKey(deviceKeyName(senderId))] = deviceKey }
    }

    /**
     * This TV's own stable id, generated once.
     *
     * Not derived from the ML-KEM bundle: the id is published in the mDNS
     * record, and a public identifier derived from a key is one more thing to
     * have to think about. A random id says nothing.
     */
    suspend fun deviceId(): String {
        val existing = context.kartIdentityStore.data.map { it[stringPreferencesKey(KEY_DEVICE_ID)] }.first()
        if (existing != null) return existing
        val id = java.util.UUID.randomUUID().toString()
        context.kartIdentityStore.edit {
            if (!it.contains(stringPreferencesKey(KEY_DEVICE_ID))) {
                it[stringPreferencesKey(KEY_DEVICE_ID)] = id
            }
        }
        // Re-read, so two hosts starting at once agree on which id won.
        return context.kartIdentityStore.data.map { it[stringPreferencesKey(KEY_DEVICE_ID)] }.first() ?: id
    }

    private fun deviceKeyName(senderId: String): String = "kart_paired_$senderId"

    private companion object {
        const val KEY_DEVICE_ID = "kart_device_id"
    }
}
