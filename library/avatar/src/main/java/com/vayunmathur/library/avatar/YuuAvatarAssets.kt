package com.vayunmathur.library.avatar

import android.content.res.AssetManager
import android.os.ParcelFileDescriptor
import com.vayunmathur.library.log.Log

/**
 * Loads `models/base_upperbody.glb` from this module's assets and hands the
 * detached fd to native (ClipHandle/MaiaHandle pattern in `:library:ml`).
 * Best-effort: absent asset keeps the procedural fallback mesh.
 */
object YuuAvatarAssets {
    /** Asset path of the rigged upper-body base mesh. */
    const val BASE_MESH_PATH = "models/base_upperbody.glb"

    /**
     * Adopt the bundled mesh, if present. Call once at startup (e.g. from
     * the first avatar surface) — the engine parses on next surface creation.
     */
    fun adoptBundledMesh(assets: AssetManager) {
        if (!YuuAvatarNative.isAvailable) return
        try {
            assets.openFd(BASE_MESH_PATH).use { afd ->
                val fd = afd.parcelFileDescriptor.detachFd()
                var handed = false
                try {
                    YuuAvatarNative.setBundledMeshFd(fd, afd.startOffset, afd.length)
                    handed = true
                } finally {
                    if (!handed) closeFd(fd)
                }
            }
        } catch (e: Exception) {
            Log.status("YuuAvatarAssets", "no bundled mesh - procedural fallback (${e.message})")
        }
    }

    private fun closeFd(fd: Int) {
        runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
    }
}
