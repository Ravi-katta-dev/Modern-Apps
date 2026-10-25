package com.vayunmathur.auto.ui

import android.content.ComponentName
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.auto.domain.PinnedAppRow
import com.vayunmathur.auto.platform.AudioSinkState
import com.vayunmathur.auto.platform.AudioSinkStatus
import com.vayunmathur.auto.platform.AutoConnectionState
import com.vayunmathur.auto.platform.DiscoveredApp
import com.vayunmathur.auto.platform.NowPlayingInfo
import com.vayunmathur.auto.platform.VideoInfo
import com.vayunmathur.auto.protocol.AudioGain
import com.vayunmathur.auto.protocol.AudioSinkRole
import com.vayunmathur.auto.protocol.TransportKind
import com.vayunmathur.auto.protocol.UsbSessionState
import com.vayunmathur.auto.protocol.VideoFocus
import com.vayunmathur.auto.protocol.WirelessSessionState
import com.vayunmathur.auto.protocol.gal.AudioFocusState
import com.vayunmathur.library.ui.DynamicTheme

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store-listing images for `:auto`, rendered from Compose previews instead of an
 * instrumented test on a device (projection needs a car or the Desktop Head Unit
 * loopback and cannot run here).
 *
 * `./gradlew :auto:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/auto/`, where `release.sh` picks them up.
 *
 * Each preview must carry @PreviewTest as well as @Preview and be a member of a class
 * (not a top-level function) or the screenshot engine silently skips it. Everything is
 * a literal so the output is reproducible from a clean checkout. The car templates
 * (AppScreen/LauncherRoot) are out of scope: their geometry comes from the head unit.
 */
class MetadataPreviews {

    private val session = SessionSnapshot(
        video = VideoInfo(width = 1280, height = 720, frameRate = 60, configIndex = 0),
        focusMode = "VIDEO_FOCUS_PROJECTED",
        videoFocus = VideoFocus.PROJECTED,
        audioFocus = AudioFocusState.AUDIO_FOCUS_STATE_GAIN,
        inputAllowed = true,
        framesSent = 12_480L,
        acksSeen = 12_475L,
        ackMismatches = 0L,
        encodedFps = 59.8,
        ackFps = 59.7,
        // Null so the live-age rows render their dash placeholder instead of a
        // wall-clock-dependent value; keeps the render deterministic.
        lastAckAt = null,
        lastAckSeq = 214L,
        avgEncodeLatencyUs = 8_400L,
        encoderDrains = 312L,
        surfaceValid = true,
        credentialDaysLeft = 320L,
        sessionStartedAt = null,
        nowPlaying = NowPlayingInfo(
            title = "Midnight Drive",
            artist = "Night Signals",
            playing = true,
            positionMs = 95_000L,
            durationMs = 214_000L,
        ),
        threadsPosted = 3L,
        messagesPosted = 11L,
        repliesReceived = 2L,
        sinkStatus = mapOf(
            AudioSinkRole.SYSTEM to AudioSinkStatus(
                role = AudioSinkRole.SYSTEM,
                state = AudioSinkState.STARTED,
                gain = AudioGain.FULL,
            ),
            AudioSinkRole.MEDIA to AudioSinkStatus(
                role = AudioSinkRole.MEDIA,
                state = AudioSinkState.STARTED,
                gain = AudioGain.DUCKED,
            ),
        ),
        audioBytesSent = 4_194_304L,
        micTurns = 5L,
        micAcks = 5L,
        ttsSpoken = 3L,
        touchesInjected = 42L,
        keysInjected = 7L,
        scrollsInjected = 12L,
        inputDropped = 0L,
        musicBytesCaptured = 2_097_152L,
    )

    private val pinnedRows = listOf(
        PinnedAppRow(
            app = DiscoveredApp(
                component = ComponentName(
                    "com.vayunmathur.maps",
                    "com.vayunmathur.maps.service.car.MapsCarAppService",
                ),
                label = "Maps",
                icon = null,
                categories = emptySet(),
            ),
            rank = 0,
        ),
        PinnedAppRow(
            app = DiscoveredApp(
                component = ComponentName(
                    "com.vayunmathur.music",
                    "com.vayunmathur.music.service.car.MusicCarAppService",
                ),
                label = "Music",
                icon = null,
                categories = emptySet(),
            ),
            rank = 1,
        ),
        PinnedAppRow(
            app = DiscoveredApp(
                component = ComponentName(
                    "com.vayunmathur.communicate",
                    "com.vayunmathur.communicate.service.car.CommunicateCarAppService",
                ),
                label = "Communicate",
                icon = null,
                categories = emptySet(),
            ),
            rank = null,
        ),
    )

    @PreviewTest
    @Preview(name = "1-status", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Status() {
        DynamicTheme(darkTheme = true) {
            AutoScreen(
                state = AutoConnectionState.Projecting(
                    carName = "Demo Head Unit",
                    videoFocus = VideoFocus.PROJECTED,
                ),
                session = session,
                onPairing = {},
                onPinnedApps = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-pairing", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Pairing() {
        DynamicTheme(darkTheme = true) {
            PairingScreen(
                usbState = UsbSessionState.CONNECTED,
                usbLabel = "Demo Head Unit",
                wirelessState = WirelessSessionState.CONNECTED,
                roleHeld = true,
                selected = TransportKind.USB,
                onNavigateBack = {},
                onUsbRetry = {},
                onWirelessSearch = {},
                onWirelessCancel = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-pinned-apps", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3PinnedApps() {
        DynamicTheme(darkTheme = true) {
            PinnedAppsScreen(
                onNavigateBack = {},
                rows = pinnedRows,
                onToggle = {},
                onMoveUp = {},
                onMoveDown = {},
            )
        }
    }
}
