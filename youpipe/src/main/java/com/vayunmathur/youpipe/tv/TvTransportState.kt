package com.vayunmathur.youpipe.tv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

/**
 * Whether the player transport overlay is on screen.
 *
 * Owned here rather than in a composable so the activity-level [TvDpadInput] key map can read
 * and reveal it without a composition: any D-pad press brings the controls up and re-arms the
 * auto-hide, exactly like the receiver's `overlayVisible`/`revealOverlay` pair.
 */
object TvTransportState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _visible = MutableStateFlow(true)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /** True while the user has asked controls to stay up (accessibility keep-visible). */
    var pinned: Boolean = false

    /** Last volume flash 0f..1f, or null when no flash is showing. */
    private val _volumeFlash = MutableStateFlow<Float?>(null)
    val volumeFlash: StateFlow<Float?> = _volumeFlash.asStateFlow()

    private var hideJob: Job? = null
    private var volumeJob: Job? = null

    val controlsHidden: Boolean get() = !_visible.value && !pinned

    /** Bring the controls up and re-arm the auto-hide timer. */
    fun reveal() {
        _visible.value = true
        hideJob?.cancel()
        hideJob = null
        if (pinned) return
        hideJob = scope.launch {
            delay(AUTO_HIDE_MS)
            // A scrub in progress is an interaction, however long the key has been held.
            if (TvDpadInput.scrubPreviewMs == null) _visible.value = false
        }
    }

    /** Put the controls away now (BACK, or playback ended). Cancels any pending scrub. */
    fun hide() {
        hideJob?.cancel()
        hideJob = null
        TvDpadInput.cancelScrub()
        _visible.value = false
    }

    /** Briefly show the volume level after a UP/DOWN nudge. */
    fun flashVolume(level: Float) {
        _volumeFlash.value = level.coerceIn(0f, 1f)
        volumeJob?.cancel()
        volumeJob = scope.launch {
            delay(VOLUME_FLASH_MS)
            _volumeFlash.value = null
        }
    }

    /** Matches the phone's 2s auto-hide, doubled: a remote aims slower than a finger. */
    private const val AUTO_HIDE_MS = 4_000L
    private const val VOLUME_FLASH_MS = 1_200L
}
