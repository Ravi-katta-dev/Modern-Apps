package com.vayunmathur.everysync.core.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [BlobStore] over the app's `filesDir` (`<filesDir>/everysync-blobs/<vaultId>/<name>`).
 *
 * Shells construct with `context.filesDir` (pass the `File`, not the
 * `Context`, so this stays unit-testable without Robolectric). No Android
 * framework imports — plain `java.io` under `Dispatchers.IO`.
 *
 * TWIN: `jvmMain/.../JvmFileBlobStore.kt` carries the same validation +
 * atomic-write shape over the user-data dir; keep them in sync.
 */
class AndroidFileBlobStore(
    filesDir: File,
) : BlobStore {

    companion object {
        const val BLOBS_DIR = "everysync-blobs"

        internal fun checkName(vaultId: String, name: String) {
            require(vaultId.isNotBlank() && '/' !in vaultId && '\\' !in vaultId &&
                vaultId != ".." && vaultId != ".") { "invalid vaultId" }
            require(name.isNotBlank() && '/' !in name && '\\' !in name &&
                name != ".." && name != ".") { "invalid blob name: $name" }
        }
    }

    private val rootDir = File(filesDir, BLOBS_DIR)

    private fun fileFor(vaultId: String, name: String): File {
        checkName(vaultId, name)
        return File(File(rootDir, vaultId), name)
    }

    override suspend fun put(vaultId: String, name: String, bytes: ByteArray) =
        withContext(Dispatchers.IO) {
            val f = fileFor(vaultId, name)
            val dir = f.parentFile ?: rootDir
            dir.mkdirs()
            val tmp = File.createTempFile("blob-", ".tmp", dir)
            try {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }

    override suspend fun get(vaultId: String, name: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val f = runCatching { fileFor(vaultId, name) }.getOrNull() ?: return@withContext null
            if (!f.isFile) return@withContext null
            runCatching { f.readBytes() }.getOrNull()
        }

    override suspend fun delete(vaultId: String, name: String) {
        withContext(Dispatchers.IO) {
            runCatching { fileFor(vaultId, name).delete() }
        }
    }
}
