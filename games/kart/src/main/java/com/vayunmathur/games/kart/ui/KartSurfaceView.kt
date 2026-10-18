package com.vayunmathur.games.kart.ui

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.vayunmathur.games.kart.util.KartNative
import com.vayunmathur.library.log.Log

/**
 * The native 3D viewport: a SurfaceView the Rust engine draws into.
 *
 * Same arrangement as the voxels `VoxelSurfaceView`: the view owns nothing but
 * the surface lifecycle, the engine owns the render thread, and every JNI
 * call is guarded by [KartNative.isAvailable] so a device without
 * `libkart_engine` shows the placeholder race instead of crashing.
 */
class KartSurfaceView @JvmOverloads constructor(
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
        if (!KartNative.isAvailable) return
        // JNI boundary: native throws unchecked exceptions, all logged here.
        try {
            KartNative.surfaceCreated(holder.surface)
            ready = true
        } catch (e: RuntimeException) {
            Log.error("KartSurface", "surfaceCreated failed", e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!KartNative.isAvailable || !ready) return
        try {
            KartNative.surfaceChanged(width, height)
        } catch (e: RuntimeException) {
            Log.error("KartSurface", "surfaceChanged failed", e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!KartNative.isAvailable) return
        try {
            KartNative.surfaceDestroyed()
            ready = false
        } catch (e: RuntimeException) {
            Log.error("KartSurface", "surfaceDestroyed failed", e)
        }
    }
}
