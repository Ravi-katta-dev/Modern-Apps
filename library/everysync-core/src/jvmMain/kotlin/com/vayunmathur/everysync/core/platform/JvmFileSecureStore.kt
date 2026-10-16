package com.vayunmathur.everysync.core.platform

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.prefs.Preferences
import kotlin.io.encoding.Base64

/**
 * File-backed [SecureStore] for desktop JVM shells (desktop/CLI targets that
 * build on the shared core).
 *
 * Layout:
 * - strings/longs live in `java.util.prefs.Preferences` under [PREFS_NODE]
 *   (registry on Windows, plist/XML elsewhere — no files to manage);
 * - bytes (PQC identity keys `personalKemPub/Priv`, `personalDsaPub/Priv`)
 *   live as individual files under [bytesDir], one file per key, written
 *   atomically (temp + rename) so a crash never leaves a half key.
 *
 * Key names are used verbatim (`personalDeviceId`, `personalKemPub`, …), the
 * same layout `SharedRelayTransport` and `RelayCursorStore` expect, so values
 * created here are adoptable by other shells unchanged.
 *
 * Path resolution is runtime-only (`EVERYSYNC_DATA_DIR` env override, else
 * `~/.everysync`): no hardcoded absolute paths, and no Gradle `Project`/`File`
 * captured here, so this stays configuration-cache safe by construction (it is
 * runtime code, not build logic).
 *
 * Failure contract: a missing key returns null; an I/O error throws (never
 * synthesized as "absent" — returning null on failure would mint a fresh PQC
 * identity and orphan every vault).
 */
class JvmFileSecureStore(
    private val prefs: Preferences = Preferences.userRoot().node(PREFS_NODE),
    private val bytesDir: File = defaultBytesDir(),
) : SecureStore {

    companion object {
        const val PREFS_NODE = "com/vayunmathur/everysync"

        /** Env override for the user-data dir (tests/CI point it at a temp dir). */
        const val ENV_DATA_DIR = "EVERYSYNC_DATA_DIR"

        /**
         * Resolved at call time (not configuration time): `$EVERYSYNC_DATA_DIR`
         * when set, else `~/.everysync`. Callers that need a custom root pass
         * [bytesDir] explicitly.
         */
        fun defaultBytesDir(): File {
            val override = System.getenv(ENV_DATA_DIR)?.takeIf { it.isNotBlank() }?.let(::File)
            val base = override ?: File(System.getProperty("user.home") ?: ".", ".everysync")
            return File(base, "securestore-bytes")
        }

        /**
         * Collision-free file name for a key: URL-safe Base64 (`-`/`_`, never
         * `/`), so any key string maps to exactly one file and no key can
         * traverse out of [bytesDir].
         */
        internal fun fileNameFor(name: String): String =
            Base64.UrlSafe.encode(name.encodeToByteArray()) + ".bin"
    }

    private val mutex = Mutex()

    private fun fileFor(name: String): File = File(bytesDir, fileNameFor(name))

    override suspend fun getBytes(name: String): ByteArray? = mutex.withLock {
        val f = fileFor(name)
        if (!f.isFile) return null
        f.readBytes()
    }

    override suspend fun setBytes(name: String, value: ByteArray, onlyIfAbsent: Boolean) {
        mutex.withLock {
            val f = fileFor(name)
            if (onlyIfAbsent && f.isFile) return
            val dir = f.parentFile ?: bytesDir
            dir.mkdirs()
            val tmp = File.createTempFile("es-", ".tmp", dir)
            try {
                tmp.writeBytes(value)
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
    }

    override suspend fun delete(name: String) {
        mutex.withLock {
            runCatching { prefs.remove(name) }
            runCatching { fileFor(name).delete() }
            runCatching { prefs.flush() }
        }
    }

    override suspend fun getString(name: String): String? = mutex.withLock {
        runCatching { prefs.get(name, null) }.getOrNull()
    }

    override suspend fun setString(name: String, value: String, onlyIfAbsent: Boolean) {
        mutex.withLock {
            if (onlyIfAbsent && runCatching { prefs.get(name, null) }.getOrNull() != null) return
            prefs.put(name, value)
            runCatching { prefs.flush() }
        }
    }

    override suspend fun getLong(name: String): Long? = mutex.withLock {
        val exists = runCatching { prefs.keys().contains(name) }.getOrDefault(false)
        if (!exists) return null
        runCatching { prefs.getLong(name, 0L) }.getOrNull()
    }

    override suspend fun setLong(name: String, value: Long) {
        mutex.withLock {
            prefs.putLong(name, value)
            runCatching { prefs.flush() }
        }
    }
}
