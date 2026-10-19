package com.vayunmathur.games.hub.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vayunmathur.games.hub.data.entities.YuuFaceConfig
import com.vayunmathur.games.hub.data.entities.encode
import com.vayunmathur.games.hub.util.YuuAvatarNative
import kotlinx.coroutines.delay

/**
 * 3D Yuu hero surface: the native avatar engine draws into a [YuuAvatarSurfaceView]
 * layered in a [Box]. Traits + pose cross as JSON; config updates debounce so
 * typing in the editor doesn't spam JNI.
 *
 * Falls back to the 2D [YuuFace] preview when native is unavailable (no
 * `libyuu_avatar`, or the Vulkan swapchain bring-up hasn't landed) — hero
 * surfaces never render blank.
 *
 * Single hero instance only: the engine owns one renderer. The plaza grid and
 * editor variant cells keep 2D thumbnails (see the plan's perf gate).
 */
@Composable
fun YuuAvatar3D(
    config: YuuFaceConfig,
    poseId: String?,
    modifier: Modifier = Modifier,
    withFallback: Boolean = true,
) {
    if (!YuuAvatarNative.isAvailable) {
        if (withFallback) {
            YuuFace(config = config, withBody = true, modifier = modifier)
        }
        return
    }
    val configJson = remember(config) { config.encode() }
    val pose = poseId ?: "idle"
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> YuuAvatarNative.setPaused(true)
                Lifecycle.Event.ON_RESUME -> YuuAvatarNative.setPaused(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            YuuAvatarNative.surfaceDestroyed()
        }
    }

    // Debounced config push: rapid draft edits coalesce into one JNI call.
    LaunchedEffect(configJson, pose) {
        delay(200)
        YuuAvatarNative.setConfig(configJson, pose)
    }

    Box(modifier = modifier) {
        // 2D underneath: if the surface never draws (both backends failed),
        // the hero still shows the Yuu instead of a black hole.
        YuuFace(config = config, withBody = true, modifier = Modifier.fillMaxSize())
        AndroidView(
            factory = { ctx -> YuuAvatarSurfaceView(ctx) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
