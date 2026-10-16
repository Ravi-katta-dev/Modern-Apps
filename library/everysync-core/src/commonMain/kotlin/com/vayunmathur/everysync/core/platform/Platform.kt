package com.vayunmathur.everysync.core.platform

/**
 * Encrypted key/value + blob storage. Platform actuals: AndroidKeyStore +
 * DataStore on Android; DPAPI / Keychain-via-JNA / libsecret on desktop JVM;
 * Keychain (`kSecClassGenericPassword`) on Apple.
 */
interface SecureStore {
    suspend fun getBytes(name: String): ByteArray?
    suspend fun setBytes(name: String, value: ByteArray, onlyIfAbsent: Boolean = false)
    suspend fun delete(name: String)

    suspend fun getString(name: String): String?
    suspend fun setString(name: String, value: String, onlyIfAbsent: Boolean = false)
    suspend fun getLong(name: String): Long?
    suspend fun setLong(name: String, value: Long)
}

/** Background sync scheduling. */
interface SyncScheduler {
    /** Schedules (or replaces) periodic sync for [accountName] every [intervalMinutes]. */
    fun schedulePeriodic(accountName: String, intervalMinutes: Long, wifiOnly: Boolean)

    /** Cancels periodic sync for [accountName]. */
    fun cancel(accountName: String)

    /** Runs a one-shot sync for [accountName] as soon as possible. */
    fun syncNow(accountName: String)
}

/**
 * Reads/writes one vault type's records through the owning app's storage.
 * Envelope ops map 1:1 to the Android relay-provider ops
 * (`querySince` / `upsertRaw` / `deleteByVault`; columns
 * `syncId / updatedAt / deleted / payloadJson / contentHash`).
 *
 * Passwords HARD-delete (no tombstone rows; `listLocal` never returns
 * deleted); notes/todos SOFT-delete (tombstone rows persist and propagate).
 */
interface SyncSink<T> {
    /** All local rows in relay form (tombstones included for soft-delete types). */
    suspend fun listLocal(vaultId: String): List<T>

    /** True when the last [listLocal] observed a healthy read; false aborts the merge. */
    val listLocalOk: Boolean

    /** Rows changed locally since [since] (tombstones included for soft-delete types). */
    suspend fun localChanges(vaultId: String, since: Long): List<LocalChange<T>>

    /** Idempotent upsert keyed on the relay sync identity; preserves pulled timestamps. */
    suspend fun upsert(vaultId: String, item: T)

    /**
     * Applies a pulled relay tombstone: tombstone the row (soft-delete types)
     * or hard-delete it (passwords). Merge-driven deletes ALWAYS arrive here
     * as tombstone envelopes, never as raw deletes.
     */
    suspend fun delete(vaultId: String, syncId: String)

    /** Removes every row (and snapshots) for this vault; the vault itself stays. */
    suspend fun purge(vaultId: String)
}

/** A locally-changed row detected via the store's timestamp watermark. */
data class LocalChange<T>(
    val syncId: String,
    val updatedAt: Long,
    val deleted: Boolean,
    val item: T?,
)
