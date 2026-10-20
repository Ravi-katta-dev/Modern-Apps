package com.vayunmathur.youpipe.tv

import android.app.Activity
import android.media.AudioManager
import android.view.KeyEvent
import androidx.core.content.getSystemService
import com.vayunmathur.youpipe.platform.CastPlayback
import kotlin.math.roundToInt

/**
 * The TV remote map, copied from `cast/tv`'s `MirrorActivity.dispatchKeyEvent` — not imported,
 * because the receiver's commands cross a socket while these drive the local MediaController.
 *
 * Attached by [TvMainActivity] so every TV screen shares one map:
 *
 * - CENTER/ENTER: toggle play/pause (when controls are visible; otherwise reveal them first).
 * - LEFT/RIGHT: ±10s scrub preview while held, committed on release (KEY_UP).
 * - UP/DOWN: nudge the shared `STREAM_MUSIC` volume — the same level the phone owns, so it
 *   survives the session and still applies when playback comes back to the phone.
 * - NEXT/PREV: related-video next / back-stack previous, via `CastPlayback.onNavigate`
 *   (the player screen installs it; with nothing installed the keys fall through).
 * - BACK: dismiss the overlay first (transport, chapter sheet, dialog), only then navigate.
 *   The overlay registers itself via [overlayDismiss]; unregistered means "nothing to dismiss".
 *
 * Like the receiver, a press that wakes hidden controls does not also toggle: with nothing on
 * screen the press reads as "show me the controls", and pausing instead would cost the user
 * their place. Skips and volume are unambiguous and always act.
 */
object TvDpadInput {
    /**
     * Dismiss the topmost overlay if one is showing; true when something was dismissed.
     *
     * The player screen sets this while the transport, chapter sheet or a dialog is up.
     * Last writer wins — only one TV screen is ever composed at a time.
     */
    var overlayDismiss: (() -> Boolean)? = null

    /** A scrub preview the user is composing, or null when no scrub is in progress. */
    var scrubPreviewMs: Long? = null
        private set

    /**
     * Whether the player surface currently holds focus.
     *
     * Set by `TvPlayer`'s surface focus listener. The gesture map (scrub, volume, toggle) only
     * claims D-pad keys while this is true — otherwise UP/DOWN/LEFT/RIGHT belong to focus
     * traversal (related list, option rows, tab strip). Media keys (play/pause/next/prev) act
     * whenever a player is attached, wherever focus sits. This is the one difference from the
     * receiver's map, which has no focusable UI to share the remote with.
     */
    var playerFocused: Boolean = false

    private var attached: android.content.Context? = null

    fun attach(activity: Activity) {
        // Application context, not the activity: volume nudges outlive any composition and a
        // static activity reference would leak the whole window on rotation.
        attached = activity.applicationContext
    }

    fun detach(activity: Activity) {
        attached = null
    }

    /**
     * Handle a key before the focus system sees it. Returns true when claimed.
     *
     * Called from `TvMainActivity.dispatchKeyEvent` — the same seam `MirrorActivity` uses —
     * so volume keys are seen before the system volume UI and BACK is seen before nav.
     * BACK-dismiss runs first, scrub-commit on release second, wake-then-act third.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return handleBack(event)
        if (event.action == KeyEvent.ACTION_UP && isScrubKey(event.keyCode)) {
            // Only a scrub this map started is committed — otherwise the release belongs to
            // whatever (or nothing) handled the press.
            if (scrubPreviewMs == null) return false
            commitScrub()
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        // Media keys act whenever a player is attached, wherever focus sits.
        if (isMediaKey(event.keyCode)) return handleMediaKey(event)
        // The D-pad gesture map only runs while the player surface holds focus; everywhere
        // else the keys belong to focus traversal (lists, option rows, tab strip).
        if (player() == null || !playerFocused) {
            // Still wake the overlay: a press anywhere says "show me the controls".
            if (player() != null) TvTransportState.reveal()
            return false
        }
        if (TvTransportState.controlsHidden) {
            TvTransportState.reveal()
            // A press that wakes the controls does not also toggle — but scrub and volume
            // reveal the very bar they move, so they still act.
            if (isToggleKey(event.keyCode)) return true
        }
        return handleKeyDown(event)
    }

    private fun handleBack(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            if (overlayDismiss?.invoke() == true) return true
        }
        return false
    }

    /** Play/pause/next/previous: no focus needed, claimed whenever a player is attached. */
    private fun isMediaKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_REWIND,
        -> true
        else -> false
    }

    private fun handleMediaKey(event: KeyEvent): Boolean {
        if (player() == null) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> toggle()
            KeyEvent.KEYCODE_MEDIA_PLAY -> setPlaying(true)
            KeyEvent.KEYCODE_MEDIA_PAUSE -> setPlaying(false)
            KeyEvent.KEYCODE_MEDIA_NEXT -> navigate(next = true)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> navigate(next = false)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> skip(forward = true)
            KeyEvent.KEYCODE_MEDIA_REWIND -> skip(forward = false)
        }
        return true
    }

    /** One D-pad press while the player surface holds focus: toggle, scrub, or volume. */
    private fun handleKeyDown(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            -> toggle()

            KeyEvent.KEYCODE_MEDIA_PLAY -> setPlaying(true)
            KeyEvent.KEYCODE_MEDIA_PAUSE -> setPlaying(false)

            KeyEvent.KEYCODE_DPAD_RIGHT -> scrubBy(SCRUB_STEP_MS)
            KeyEvent.KEYCODE_DPAD_LEFT -> scrubBy(-SCRUB_STEP_MS)

            // The remote's dedicated keys, where it has them. Discrete skips, not scrub previews.
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> skip(forward = true)
            KeyEvent.KEYCODE_MEDIA_REWIND -> skip(forward = false)

            KeyEvent.KEYCODE_MEDIA_NEXT -> navigate(next = true)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> navigate(next = false)

            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_VOLUME_UP,
            -> nudgeVolume(up = true)

            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            -> nudgeVolume(up = false)

            else -> return false
        }
        return true
    }

    private fun player() = CastPlayback.attachedPlayer

    private fun toggle() {
        val target = player() ?: return
        if (target.isPlaying) target.pause() else target.play()
    }

    private fun setPlaying(playing: Boolean) {
        val target = player() ?: return
        if (playing) target.play() else target.pause()
    }

    /**
     * Move the scrub preview, starting from live playback on the first press.
     *
     * Accumulated locally and committed once on release — one seek per gesture, not one per
     * key-repeat, which is both kinder to ExoPlayer and what holding the key means.
     */
    private fun scrubBy(deltaMs: Long) {
        val transport = CastPlayback.transport.value
        if (transport.durationMs <= 0) return
        val from = scrubPreviewMs ?: transport.positionMs
        scrubPreviewMs = (from + deltaMs).coerceIn(0, transport.durationMs)
        TvTransportState.reveal()
    }

    private fun commitScrub() {
        val target = scrubPreviewMs ?: return
        scrubPreviewMs = null
        player()?.seekTo(target)
        CastPlayback.update { it.copy(positionMs = target) }
        TvTransportState.reveal()
    }

    private fun skip(forward: Boolean) {
        val transport = CastPlayback.transport.value
        val end = transport.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = if (forward) {
            (transport.positionMs + SKIP_MS).coerceAtMost(end)
        } else {
            (transport.positionMs - SKIP_MS).coerceAtLeast(0)
        }
        player()?.seekTo(target)
        CastPlayback.update { it.copy(positionMs = target) }
        TvTransportState.reveal()
    }

    private fun navigate(next: Boolean) {
        CastPlayback.onNavigate?.invoke(next)
    }

    /**
     * Nudge the shared media volume.
     *
     * The same level the phone owns (`STREAM_MUSIC`): the remote moves it and the gain follows
     * on the next snapshot, so the level is still there when playback returns to the phone.
     */
    private fun nudgeVolume(up: Boolean) {
        val activity = attached ?: return
        val audio = activity.getSystemService<AudioManager>() ?: return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val step = (max / VOLUME_STEPS).coerceAtLeast(1)
        val next = (current + if (up) step else -step).coerceIn(0, max)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0) }
        TvTransportState.flashVolume(next.toFloat() / max)
    }

    private fun isScrubKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

    private fun isToggleKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE

    /** How far one D-pad press moves the scrub preview. Matches the receiver's aim-sized step. */
    const val SCRUB_STEP_MS = 10_000L

    /** Discrete skip for the dedicated FF/REW keys — matches the phone's own skip buttons. */
    private const val SKIP_MS = 10_000L

    /** Volume steps across the full range; the TV remote nudges rather than jumps. */
    private const val VOLUME_STEPS = 20

    /** Cancel a scrub without committing (overlay dismissed mid-scrub). */
    fun cancelScrub() {
        scrubPreviewMs = null
    }
}
