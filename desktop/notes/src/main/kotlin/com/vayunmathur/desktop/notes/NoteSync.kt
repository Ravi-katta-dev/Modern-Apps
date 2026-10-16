package com.vayunmathur.desktop.notes

import com.vayunmathur.everysync.core.data.RemoteNote
import com.vayunmathur.everysync.core.network.RelayTransport
import com.vayunmathur.everysync.core.network.appendRecords
import com.vayunmathur.everysync.core.network.blobChannel
import com.vayunmathur.everysync.core.network.pullRecords
import com.vayunmathur.everysync.core.network.recordChannel
import com.vayunmathur.everysync.core.provider.SyncDriver
import com.vayunmathur.everysync.core.sync.EntityKind
import com.vayunmathur.everysync.core.sync.RelayStore
import com.vayunmathur.everysync.core.sync.adapters.NoteKind
import com.vayunmathur.everysync.core.sync.adapters.TodoKind
import java.io.File
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Image bytes by file name. Note bodies ([RemoteNote]) reference images by
 * `fileName` only; the bytes ride the `notes-blobs:<vaultId>` side channel so
 * the record log stays small and mergeable (mirrors Android `RelayBlobStore`).
 */
interface BlobStore {
    fun read(name: String): ByteArray?
    fun write(name: String, bytes: ByteArray)
    fun delete(name: String)
}

/** [BlobStore] over `dataDir/blobs/<vaultId>/<name>` (JVM files, no Android). */
class FileBlobStore(dataDir: File, vaultId: String) : BlobStore {
    private val dir = File(File(dataDir, "blobs"), vaultId).apply { mkdirs() }

    override fun read(name: String): ByteArray? {
        val f = File(dir, name)
        return if (f.isFile) runCatching { f.readBytes() }.getOrNull() else null
    }

    override fun write(name: String, bytes: ByteArray) {
        runCatching { File(dir, name).writeBytes(bytes) }
    }

    override fun delete(name: String) {
        runCatching { File(dir, name).delete() }
    }
}

/**
 * Drives the notes + todos channels through the shared [SyncDriver] over the
 * shared [RelayTransport] (JVM: [com.vayunmathur.everysync.core.network.JvmRelayTransport]).
 *
 * Per-channel cycle (mirrors `E2eeProvider.driveMerge`):
 *  1. Fold the FULL decrypted record log from 0 (tombstones included — full
 *     state, never a delta, so absence vs deletion stays decidable).
 *  2. A failed pull (null) or a fully-undecryptable log aborts the channel
 *     with zero writes — never merge a failure as empty.
 *  3. [SyncDriver.drive] merges against the sink's full local state +
 *     baseline, appends `remoteOut` BEFORE persisting locally, then applies
 *     the local half.
 *
 * Blob sync is last-writer-wins per file name (latest [BlobEnvelope.updatedAt]
 * wins; remote wins on differing bytes; referenced-but-absent uploads). A
 * real UI would track local mtimes for finer conflicts — the shell keeps the
 * simple rule and documents it.
 *
 * Vault-key note: the caller supplies the epoch-0 vault key directly. Roster
 * / key-epoch / ownership resolution (`VaultRoster`, `VaultKeys`,
 * `OwnerTransfer`) is future work for the desktop shell — shared vaults that
 * rotated keys need that before this shell can read them.
 */
class NoteSync(
    private val transport: RelayTransport,
    private val dataDir: File,
    private val blobStore: BlobStore? = null,
) {
    data class ChannelResult(val ok: Boolean, val localCount: Int)
    data class BlobResult(val ok: Boolean, val pulled: Int, val pushed: Int, val deleted: Int)
    data class SyncResult(
        val notes: ChannelResult,
        val todos: ChannelResult,
        val todosViewCount: Int,
        val blobs: BlobResult?,
    )

    private val blobJson = Json { ignoreUnknownKeys = true }

    suspend fun syncOnce(vaultId: String, vaultKey: ByteArray): SyncResult {
        val notes = NoteVault(dataDir, vaultId)
        val todos = TodoView(dataDir, vaultId)
        val now = System.currentTimeMillis()
        val notesOk = syncChannel(NoteKind, notes, recordChannel("notes", vaultId), vaultKey, now)
        val todosOk = syncChannel(TodoKind, todos, TodoKind.channel(vaultId), vaultKey, now)
        val blobs = syncBlobs(vaultId, vaultKey, notes)
        return SyncResult(
            notes = ChannelResult(notesOk, notes.listLocal().size),
            todos = ChannelResult(todosOk, todos.listLocal().size),
            todosViewCount = todos.todosAsNotes().size,
            blobs = blobs,
        )
    }

    private suspend fun <T> syncChannel(
        kind: EntityKind<T>,
        store: RelayStore<T>,
        channel: String,
        key: ByteArray,
        now: Long,
    ): Boolean {
        val pulled = transport.pullRecords(channel, key, 0) ?: return false
        if (pulled.fullyUndecryptable) return false
        val remote = pulled.items.mapNotNull { store.parseRemote(it) }
        return SyncDriver.drive(
            kind = kind,
            store = store,
            remoteEntities = remote,
            appendRemote = { items -> transport.appendRecords(channel, key, items) != null },
            now = now,
        )
    }

    /**
     * Blob side-channel sync. Returns null when no [BlobStore] is wired —
     * TODO: default the CLI to [FileBlobStore] so images follow notes.
     */
    private suspend fun syncBlobs(
        vaultId: String,
        vaultKey: ByteArray,
        notes: NoteVault,
    ): BlobResult? {
        val store = blobStore ?: return null
        val channel = blobChannel(vaultId)
        val pulled = transport.pullRecords(channel, vaultKey, 0)
            ?: return BlobResult(ok = false, pulled = 0, pushed = 0, deleted = 0)
        if (pulled.fullyUndecryptable) {
            return BlobResult(ok = false, pulled = 0, pushed = 0, deleted = 0)
        }
        // Fold to latest envelope per name (LWW by updatedAt).
        val remoteByName = LinkedHashMap<String, BlobEnvelope>()
        for (raw in pulled.items) {
            val env = decodeBlob(raw) ?: continue
            if (env.name.isBlank()) continue
            val prev = remoteByName[env.name]
            if (prev == null || env.updatedAt >= prev.updatedAt) remoteByName[env.name] = env
        }
        var pulledCount = 0
        var deletedCount = 0
        for ((name, env) in remoteByName) {
            if (env.deleted) {
                if (store.read(name) != null) {
                    store.delete(name)
                    deletedCount++
                }
                continue
            }
            val bytes = runCatching { Base64.decode(env.bytesB64) }.getOrNull() ?: continue
            val local = store.read(name)
            if (local == null || sha256Hex(local) != env.sha256) {
                store.write(name, bytes)
                pulledCount++
            }
        }
        // Push images local notes reference but the relay has never seen.
        val referenced = notes.listLocal()
            .flatMap { NoteKind.imageNames(it.blocksJson) }
            .distinct()
        var pushedCount = 0
        val out = mutableListOf<String>()
        for (name in referenced) {
            if (name in remoteByName) continue
            val bytes = store.read(name) ?: continue
            out += blobJson.encodeToString(
                BlobEnvelope(name, sha256Hex(bytes), Base64.encode(bytes), System.currentTimeMillis()),
            )
        }
        if (out.isNotEmpty()) {
            if (transport.appendRecords(channel, vaultKey, out) == null) {
                return BlobResult(ok = false, pulled = pulledCount, pushed = 0, deleted = deletedCount)
            }
            pushedCount = out.size
        }
        return BlobResult(ok = true, pulled = pulledCount, pushed = pushedCount, deleted = deletedCount)
    }

    private fun decodeBlob(raw: String): BlobEnvelope? = runCatching {
        blobJson.decodeFromString<BlobEnvelope>(raw)
    }.getOrNull()

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /**
     * Blob envelope, field-for-field compatible with Android
     * `RelayBlobStore.BlobEnvelope` (same channel, same vault key, same JSON).
     */
    @Serializable
    private data class BlobEnvelope(
        val name: String,
        val sha256: String = "",
        val bytesB64: String = "",
        val updatedAt: Long = 0L,
        val deleted: Boolean = false,
    )
}
