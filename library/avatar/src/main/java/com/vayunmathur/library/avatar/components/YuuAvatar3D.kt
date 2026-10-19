package com.vayunmathur.library.avatar.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vayunmathur.library.avatar.YuuAvatarNative
import kotlinx.coroutines.delay

/**
 * 3D Yuu hero surface: the native avatar engine draws into a [YuuAvatarSurfaceView]
 * layered in a [Box]. Traits + pose cross as pre-encoded JSON; config updates
 * debounce so editor typing doesn't spam JNI.
 *
 * The caller supplies [configJson] (the `YuuFaceConfig` JSON) and an optional
 * [fallback] 2D preview shown when native is unavailable (no
 * `libyuu_avatar`) and layered underneath the surface (so a failed renderer
 * never shows a black hole).
 *
 * Each instance carries a stable [surfaceId] for the multi-renderer engine;
 * dispose destroys that surface only.
 */
@Composable
fun YuuAvatar3D(
    configJson: String,
    poseId: String?,
    surfaceId: Int,
    modifier: Modifier = Modifier,
    fallback: (@Composable () -> Unit)? = null,
) {
    if (!YuuAvatarNative.isAvailable) {
        fallback?.invoke()
        return
    }
    val pose = poseId ?: "idle"
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle, surfaceId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> YuuAvatarNative.setPaused(surfaceId, true)
                Lifecycle.Event.ON_RESUME -> YuuAvatarNative.setPaused(surfaceId, false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            YuuAvatarNative.surfaceDestroyed(surfaceId)
        }
    }

    // Debounced config push: rapid draft edits coalesce into one JNI call.
    LaunchedEffect(configJson, pose, surfaceId) {
        delay(200)
        YuuAvatarNative.setConfig(surfaceId, configJson, pose)
    }

    Box(modifier = modifier) {
        // 2D underneath: if the surface never draws (both backends failed),
        // the hero still shows the Yuu instead of a black hole.
        fallback?.let { fb ->
            Box(Modifier.fillMaxSize()) { fb() }
        }
        AndroidView(
            factory = { ctx -> YuuAvatarSurfaceView(ctx, surfaceId = surfaceId) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
