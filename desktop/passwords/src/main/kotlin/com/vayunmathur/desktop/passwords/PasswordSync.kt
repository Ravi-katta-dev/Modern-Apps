package com.vayunmathur.desktop.passwords

import com.vayunmathur.everysync.core.network.JvmRelayTransport
import com.vayunmathur.everysync.core.network.RelayTransport
import com.vayunmathur.everysync.core.network.appendRecords
import com.vayunmathur.everysync.core.network.pullRecords
import com.vayunmathur.everysync.core.network.recordChannel
import com.vayunmathur.everysync.core.platform.MemorySecureStore
import com.vayunmathur.everysync.core.platform.SecureStore
import com.vayunmathur.everysync.core.provider.SyncDriver
import com.vayunmathur.everysync.core.sync.adapters.PasswordKind
import java.io.File
import kotlin.io.encoding.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * File-backed [SecureStore] for the desktop shell's PQC identity + device id.
 * Keys persist as files under `dataDir/secure/<name>` (bytes raw, strings
 * UTF-8, longs decimal) with `onlyIfAbsent` honored atomically.
 *
 * NOTE: this is persistence, not encryption — a production desktop client
 * would wrap this in DPAPI / Keychain / libsecret (see the `SecureStore`
 * contract in the shared core). Phase 2a keeps the plain-file form and
 * documents the gap.
 */
class FileSecureStore(dataDir: File) : SecureStore {
    private val dir = File(dataDir, "secure").apply { mkdirs() }
    private val mutex = Mutex()

    override suspend fun getBytes(name: String): ByteArray? = mutex.withLock {
        val f = File(dir, name)
        if (f.isFile) runCatching { f.readBytes() }.getOrNull() else null
    }

    override suspend fun setBytes(name: String, value: ByteArray, onlyIfAbsent: Boolean) {
        mutex.withLock {
            val f = File(dir, name)
            if (onlyIfAbsent && f.isFile) return
            runCatching { f.writeBytes(value) }
        }
    }

    override suspend fun delete(name: String) {
        mutex.withLock { runCatching { File(dir, name).delete() } }
    }

    override suspend fun getString(name: String): String? = mutex.withLock {
        val f = File(dir, "$name.str")
        if (f.isFile) runCatching { f.readText() }.getOrNull() else null
    }

    override suspend fun setString(name: String, value: String, onlyIfAbsent: Boolean) {
        mutex.withLock {
            val f = File(dir, "$name.str")
            if (onlyIfAbsent && f.isFile) return
            runCatching { f.writeText(value) }
        }
    }

    override suspend fun getLong(name: String): Long? = mutex.withLock {
        val f = File(dir, "$name.num")
        if (f.isFile) runCatching { f.readText().trim().toLong() }.getOrNull() else null
    }

    override suspend fun setLong(name: String, value: Long) {
        mutex.withLock { runCatching { File(dir, "$name.num").writeText(value.toString()) } }
    }
}

/**
 * Drives the `passwords:<vaultId>` channel through the shared [SyncDriver]
 * over the shared [RelayTransport] (JVM: [JvmRelayTransport] with
 * [com.vayunmathur.everysync.core.network.JvmPqcCrypto] defaults).
 *
 * Cycle (mirrors `E2eeProvider.driveMerge`):
 *  1. Fold the FULL decrypted record log from 0 — tombstones included, last
 *     envelope per uid wins — into remote state (never a delta, so absence
 *     vs deletion stays decidable against the baseline).
 *  2. A failed pull (null) or a fully-undecryptable log aborts with zero
 *     writes — never merge a failure as empty (see `RelayMerge`).
 *  3. [SyncDriver.drive] merges against the vault's full local state +
 *     baseline, appends `remoteOut` BEFORE persisting locally, then applies
 *     the local half.
 *
 * Vault-key note: the caller supplies the epoch-0 vault key directly. Roster
 * / key-epoch / ownership resolution (`VaultRoster`, `VaultKeys`,
 * `OwnerTransfer`) is future work for the desktop shell — shared vaults that
 * rotated keys need that before this shell can read them.
 */
class PasswordSync(
    private val transport: RelayTransport,
    private val dataDir: File,
) {
    data class SyncResult(val ok: Boolean, val passwords: Int, val passkeys: Int)

    companion object {
        fun channelFor(vaultId: String): String = recordChannel("passwords", vaultId)

        /** Builds a transport with production JVM PQC + pinned HTTP defaults. */
        fun defaultTransport(store: SecureStore = MemorySecureStore()): JvmRelayTransport =
            JvmRelayTransport(store)
    }

    suspend fun syncOnce(vaultId: String, vaultKey: ByteArray): SyncResult {
        val vault = PasswordVault(dataDir, vaultId)
        val channel = channelFor(vaultId)
        val now = System.currentTimeMillis()

        // Null pull (any transport failure) aborts — never merge a failure.
        val pulled = transport.pullRecords(channel, vaultKey, 0) ?: return SyncResult(false, 0, 0)
        if (pulled.fullyUndecryptable) return SyncResult(false, 0, 0)

        // Fold to FULL state: last envelope per uid wins (append-only log).
        val folded = LinkedHashMap<String, String>()
        for (raw in pulled.items) {
            val entity = vault.parseRemote(raw) ?: continue
            folded[entity.uid] = raw
        }
        val remote = folded.values.mapNotNull { vault.parseRemote(it) }

        val ok = SyncDriver.drive(
            kind = PasswordKind,
            store = vault,
            remoteEntities = remote,
            appendRemote = { items -> transport.appendRecords(channel, vaultKey, items) != null },
            now = now,
        )
        if (!ok) return SyncResult(false, 0, 0)
        val local = vault.listLocal()
        return SyncResult(true, local.count { !it.isPasskey }, local.count { it.isPasskey })
    }
}

/** Vault-key file helpers (`dataDir/vault-<vaultId>.key`, base64). */
object VaultKeyFile {
    private val json = Json { ignoreUnknownKeys = true }

    fun fileFor(dataDir: File, vaultId: String): File = File(dataDir, "vault-$vaultId.key")

    fun load(dataDir: File, vaultId: String): ByteArray? {
        val f = fileFor(dataDir, vaultId)
        if (!f.isFile) return null
        return runCatching { Base64.decode(f.readText().trim()) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun save(dataDir: File, vaultId: String, key: ByteArray) {
        dataDir.mkdirs()
        fileFor(dataDir, vaultId).writeText(json.encodeToString(Base64.encode(key)))
    }
}
