package com.vayunmathur.games.hub.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.vayunmathur.games.hub.util.YuuAvatarNative
import com.vayunmathur.library.log.Log

/**
 * The native 3D avatar viewport: a SurfaceView the Rust engine draws into.
 *
 * Same arrangement as kart's `KartSurfaceView`: the view owns nothing but
 * the surface lifecycle, the engine owns the render thread, and every JNI
 * call is guarded by [YuuAvatarNative.isAvailable] so a device without
 * `libyuu_avatar` shows the 2D fallback instead of crashing.
 */
class YuuAvatarSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    private var ready = false

    init {
        holder.addCallback(this)
    }

    // JNI boundary: native throws unchecked exceptions, all logged here.
    @Suppress("TooGenericExceptionCaught")
    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!YuuAvatarNative.isAvailable) return
        // JNI boundary: native throws unchecked exceptions, all logged here.
        try {
            YuuAvatarNative.surfaceCreated(holder.surface)
            ready = true
        } catch (e: RuntimeException) {
            Log.error("YuuAvatarSurface", "surfaceCreated failed", e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!YuuAvatarNative.isAvailable || !ready) return
        try {
            YuuAvatarNative.surfaceChanged(width, height)
        } catch (e: RuntimeException) {
            Log.error("YuuAvatarSurface", "surfaceChanged failed", e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!YuuAvatarNative.isAvailable) return
        try {
            YuuAvatarNative.surfaceDestroyed()
            ready = false
        } catch (e: RuntimeException) {
            Log.error("YuuAvatarSurface", "surfaceDestroyed failed", e)
        }
    }
}
