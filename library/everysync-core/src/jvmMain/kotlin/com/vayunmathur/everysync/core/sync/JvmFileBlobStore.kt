package com.vayunmathur.everysync.core.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [BlobStore] over the desktop user-data dir (`<root>/<vaultId>/<name>`).
 *
 * TWIN: `androidMain/.../AndroidFileBlobStore.kt` carries the same validation
 * + atomic-write shape over `filesDir`; keep them in sync.
 *
 * Root resolution is runtime-only (`EVERYSYNC_DATA_DIR` env override, else
 * `~/.everysync/blobs`): no hardcoded absolute paths, and no Gradle
 * `Project`/`File` captured here, so this stays configuration-cache safe by
 * construction (it is runtime code, not build logic).
 */
class JvmFileBlobStore(
    private val rootDir: File = defaultRoot(),
) : BlobStore {

    companion object {
        /** Env override for the user-data dir (tests/CI point it at a temp dir). */
        const val ENV_DATA_DIR = "EVERYSYNC_DATA_DIR"

        /** Resolved at call time (not configuration time). */
        fun defaultRoot(): File {
            val override = System.getenv(ENV_DATA_DIR)?.takeIf { it.isNotBlank() }?.let(::File)
            val base = override ?: File(System.getProperty("user.home") ?: ".", ".everysync")
            return File(base, "blobs")
        }

        internal fun checkName(vaultId: String, name: String) {
            require(vaultId.isNotBlank() && '/' !in vaultId && '\\' !in vaultId &&
                vaultId != ".." && vaultId != ".") { "invalid vaultId" }
            require(name.isNotBlank() && '/' !in name && '\\' !in name &&
                name != ".." && name != ".") { "invalid blob name: $name" }
        }
    }

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
