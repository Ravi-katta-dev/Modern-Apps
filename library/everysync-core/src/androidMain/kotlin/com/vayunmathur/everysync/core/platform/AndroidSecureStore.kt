package com.vayunmathur.everysync.core.platform

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64

/**
 * [SecureStore] for Android shells, over plain `SharedPreferences`.
 *
 * Key names are used verbatim (`personalDeviceId`, `personalKemPub`,
 * `personalKemPriv`, `personalDsaPub`, `personalDsaPriv`,
 * `relay_cursor:…`, `relay_baseline:…`), matching the layouts
 * `SharedRelayTransport`, `PqcIdentityHandle`, `RelayCursorStore` and
 * `SnapshotFileStore` expect — the same names the everysync Android stores
 * (`VaultStore`/`TokenStore` family) use — so a future migration of those
 * shells to the shared core adopts existing values unchanged.
 *
 * Bytes are stored as Base64 strings under the same key (SharedPreferences
 * has no byte[] type); strings/longs use the native types. `onlyIfAbsent` is
 * honored atomically within this process (Mutex + `contains` check).
 *
 * TODO: migrate to EncryptedSharedPreferences
 * (androidx.security:security-crypto) once that dependency is added to
 * `:library:everysync-core`. Plain SharedPreferences is sufficient for
 * Phase 1: E2EE does not depend on it — the relay is an untrusted log, vault
 * payloads are AES-256-GCM, and PQC private keys sealed at rest add defense
 * in depth, not correctness.
 */
class AndroidSecureStore(
    context: Context,
    prefsName: String = DEFAULT_PREFS_NAME,
) : SecureStore {

    companion object {
        const val DEFAULT_PREFS_NAME = "everysync_secure"
    }

    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    override suspend fun getBytes(name: String): ByteArray? = mutex.withLock {
        val raw = prefs.getString(name, null) ?: return null
        runCatching { Base64.decode(raw) }.getOrNull()
    }

    override suspend fun setBytes(name: String, value: ByteArray, onlyIfAbsent: Boolean) {
        mutex.withLock {
            if (onlyIfAbsent && prefs.contains(name)) return
            prefs.edit().putString(name, Base64.encode(value)).apply()
        }
    }

    override suspend fun delete(name: String) {
        mutex.withLock { prefs.edit().remove(name).apply() }
    }

    override suspend fun getString(name: String): String? = mutex.withLock {
        prefs.getString(name, null)
    }

    override suspend fun setString(name: String, value: String, onlyIfAbsent: Boolean) {
        mutex.withLock {
            if (onlyIfAbsent && prefs.contains(name)) return
            prefs.edit().putString(name, value).apply()
        }
    }

    override suspend fun getLong(name: String): Long? = mutex.withLock {
        if (!prefs.contains(name)) return null
        prefs.getLong(name, 0L)
    }

    override suspend fun setLong(name: String, value: Long) {
        mutex.withLock { prefs.edit().putLong(name, value).apply() }
    }
}
