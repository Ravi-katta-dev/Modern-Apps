package com.vayunmathur.everysync.core.platform

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [SecureStore] for tests and for drivers that manage their own
 * persistence. `onlyIfAbsent` is honored atomically.
 */
class MemorySecureStore : SecureStore {
    private val bytes = mutableMapOf<String, ByteArray>()
    private val strings = mutableMapOf<String, String>()
    private val longs = mutableMapOf<String, Long>()
    private val mutex = Mutex()

    override suspend fun getBytes(name: String): ByteArray? = mutex.withLock { bytes[name] }

    override suspend fun setBytes(name: String, value: ByteArray, onlyIfAbsent: Boolean) {
        mutex.withLock {
            if (!onlyIfAbsent || name !in bytes) bytes[name] = value
        }
    }

    override suspend fun delete(name: String) {
        mutex.withLock {
            bytes.remove(name)
            strings.remove(name)
            longs.remove(name)
        }
    }

    override suspend fun getString(name: String): String? = mutex.withLock { strings[name] }

    override suspend fun setString(name: String, value: String, onlyIfAbsent: Boolean) {
        mutex.withLock {
            if (!onlyIfAbsent || name !in strings) strings[name] = value
        }
    }

    override suspend fun getLong(name: String): Long? = mutex.withLock { longs[name] }

    override suspend fun setLong(name: String, value: Long) {
        mutex.withLock { longs[name] = value }
    }
}

/**
 * Cursor + baseline persistence over any [SecureStore]. Key layouts mirror
 * the Android stores (`relay_cursor:<type>:<vault>:<channel>`,
 * `relay_baseline:<vault>:<kind>`), so a future Android migration to the
 * shared core can adopt existing DataStore values verbatim.
 */
class RelayCursorStore(private val store: SecureStore) {
    private fun key(dataType: String, vaultId: String, channel: String): String =
        "relay_cursor:$dataType:$vaultId:$channel"

    suspend fun get(dataType: String, vaultId: String, channel: String): Int =
        store.getLong(key(dataType, vaultId, channel))?.toInt() ?: 0

    suspend fun set(dataType: String, vaultId: String, channel: String, seq: Int) {
        store.setLong(key(dataType, vaultId, channel), seq.toLong())
    }

    /** Advances the cursor only forward; returns true when it moved. */
    suspend fun advance(dataType: String, vaultId: String, channel: String, seq: Int): Boolean {
        val k = key(dataType, vaultId, channel)
        val cur = store.getLong(k)?.toInt() ?: 0
        if (seq <= cur) return false
        store.setLong(k, seq.toLong())
        return true
    }

    suspend fun clearVault(dataType: String, vaultId: String, channels: List<String>) {
        for (c in channels) store.delete(key(dataType, vaultId, c))
    }
}
