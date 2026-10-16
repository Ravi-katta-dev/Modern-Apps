package com.vayunmathur.everysync.core.sync

/**
 * Content bytes for note image blocks, keyed by `(vaultId, name)`.
 *
 * The relay side of these bytes is the `notes-blobs:<vaultId>` channel (see
 * `blobChannel`); this interface is the local half shells compose into their
 * note stores. Names are the `fileName` values referenced inside
 * `NoteBody` blocks JSON (see `NoteKind.imageNames`).
 *
 * Platform impls: [JvmFileBlobStore] (desktop user-data dir) and
 * [AndroidFileBlobStore] (app `filesDir`). Both reject path separators in
 * `vaultId`/`name` so a malicious envelope can never traverse out of the root.
 */
interface BlobStore {
    /** Stores (or replaces) the bytes for `(vaultId, name)`. */
    suspend fun put(vaultId: String, name: String, bytes: ByteArray)

    /** Returns the bytes for `(vaultId, name)`, or null when absent/invalid. */
    suspend fun get(vaultId: String, name: String): ByteArray?

    /** Deletes `(vaultId, name)`; no-op when absent. */
    suspend fun delete(vaultId: String, name: String)
}
