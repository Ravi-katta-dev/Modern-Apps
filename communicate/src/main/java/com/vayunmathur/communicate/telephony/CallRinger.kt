package com.vayunmathur.communicate.telephony

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.vayunmathur.library.log.Log

/**
 * Audible + haptic ringing for incoming calls, shared by every line.
 *
 * Self-managed Telecom connections ([InAppCallConnectionService],
 * [GoogleVoiceConnectionService]) post an incoming-call notification — but no notification (even a
 * `CallStyle` one) loops the ringtone by itself, and none of the call paths
 * played anything. The result was exactly the reported bug: a silent heads-up
 * notification instead of a ringing call. Carrier/SIM calls are excluded: the
 * platform already plays the ringtone for dialer-role calls, and a second
 * loop would double it.
 *
 * One singleton rather than one per service because only one call can ring at
 * a time, and because answer/decline can arrive from the system surface, the
 * notification action, or the in-app screen — whichever path leaves
 * `Incoming` first stops the same ringer. Start is idempotent; stop always
 * releases.
 *
 * Sound is the user's default ringtone looped through
 * `USAGE_NOTIFICATION_RINGTONE` (so silent/DND modes are honoured by the platform), plus the
 * standard ring-vibrate waveform. The incoming-call notifications themselves stay
 * silent — the ringer owns audio, otherwise the channel's ding would double
 * up with the loop.
 */
object CallRinger {
    private const val TAG = "CallRinger"

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var vibrator: Vibrator? = null

    /** Idempotent: a second incoming call while ringing keeps the same loop. */
    @Synchronized
    fun start(context: Context) {
        if (player != null) return
        val app = context.applicationContext
        startVibration(app)
        startSound(app)
    }

    @Synchronized
    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    private fun startVibration(app: Context) {
        runCatching {
            val vib = app.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
            vibrator = vib
            vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 1000, 1000), 0))
        }.onFailure {
            Log.status(TAG, "could not start call vibration", it)
        }
    }

    private fun startSound(app: Context) {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        runCatching {
            MediaPlayer().apply {
                setDataSource(app, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                isLooping = true
                setOnPreparedListener { mp ->
                    // stop() may have run while prepare was in flight; only start the
                    // current generation, otherwise a stale callback re-rings after hangup.
                    if (mp === player) runCatching { mp.start() } else runCatching { mp.release() }
                }
                setOnErrorListener { _, _, _ ->
                    Log.status(TAG, "ringtone playback failed for $uri")
                    true
                }
                // Async: prepare() blocks (content-resolver IO) and this is
                // called from the service's main-thread state collector.
                prepareAsync()
            }.also { player = it }
        }.onFailure {
            Log.status(TAG, "could not start ringtone for $uri", it)
            player = null
        }
    }
}
