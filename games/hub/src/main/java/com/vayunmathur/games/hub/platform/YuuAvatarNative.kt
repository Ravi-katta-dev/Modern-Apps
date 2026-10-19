// PACKAGE STRUCTURE EXCEPTION (JNI): FQN frozen for native RegisterNatives/symbol mangling
// NOTE: file lives under platform/ but package is ...games.hub.util to match the native symbol
@file:Suppress("InvalidPackageDeclaration")
package com.vayunmathur.games.hub.util

import com.vayunmathur.library.log.Log

/**
 * The 3D avatar engine bridge, shaped like kart's `KartNative`.
 *
 * Native calls are no-ops behind [isAvailable] = false (no `libyuu_avatar`
 * on this device, or the Vulkan swapchain bring-up hasn't landed), and hero
 * surfaces fall back to the 2D [com.vayunmathur.games.hub.ui.components.YuuFace]
 * preview instead. The signatures below are the contract the Rust engine
 * implements, so the Kotlin side never changes shape when native lands — only
 * this object's body does.
 *
 * Surface lifecycle mirrors kart/voxels: the view calls surfaceCreated/
 * Changed/Destroyed, the engine spawns its own 60 Hz render thread and ticks
 * the avatar there. Traits + pose cross as JSON strings; the pose gallery
 * reads back the preset ids.
 */
object YuuAvatarNative {
    /** False until `libyuu_avatar` ships. Checked once per surface, like kart. */
    val isAvailable: Boolean = try {
        System.loadLibrary("yuu_avatar")
        nativeInit()
        Log.status("YuuAvatarNative", "libyuu_avatar loaded")
        true
    } catch (t: Throwable) {
        Log.status("YuuAvatarNative", "libyuu_avatar absent - using 2D Yuu preview")
        false
    }

    /**
     * Set traits + pose. `configJson` is the `YuuFaceConfig` JSON;
     * `poseJson` is a preset id (e.g. "wave") or `{"id": ...}`. No-op
     * without the library.
     */
    fun setConfig(configJson: String, poseJson: String) {
        if (!isAvailable) return
        nativeSetConfig(configJson, poseJson)
    }

    /** Pose preset ids as JSON (`["idle","wave",...]`) for the gallery. */
    fun getPosesJson(): String = if (isAvailable) nativeGetPosesJson() else "[]"

    /** Surface lifecycle, called by the avatar surface view. */
    fun surfaceCreated(surface: android.view.Surface) {
        if (!isAvailable) return
        nativeSurfaceCreated(surface)
    }

    fun surfaceChanged(width: Int, height: Int) {
        if (!isAvailable) return
        nativeSurfaceChanged(width, height)
    }

    fun surfaceDestroyed() {
        if (!isAvailable) return
        nativeSurfaceDestroyed()
    }

    fun setPaused(paused: Boolean) {
        if (!isAvailable) return
        nativeSetPaused(paused)
    }

    fun onDestroy() {
        if (!isAvailable) return
        nativeOnDestroy()
    }

    private external fun nativeInit(): Boolean
    private external fun nativeSetConfig(configJson: String, poseJson: String)
    private external fun nativeGetPosesJson(): String
    private external fun nativeSurfaceCreated(surface: android.view.Surface)
    private external fun nativeSurfaceChanged(width: Int, height: Int)
    private external fun nativeSurfaceDestroyed()
    private external fun nativeSetPaused(paused: Boolean)
    private external fun nativeOnDestroy()
}
