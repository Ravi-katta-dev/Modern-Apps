package com.vayunmathur.cast.tv.platform

import com.vayunmathur.cast.protocol.NowPlaying
import com.vayunmathur.cast.protocol.PlaybackState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The served-session correlation gate and the overlay clock, apart from any TV framework.
 *
 * The channel is ordered but the phone's producers are not, so a metadata snapshot is stored
 * unconditionally and only shown while it names the resource the player is actually playing.
 */
class ReceiverNowPlayingGateTest {

    private fun state(resourceId: String, playingResourceId: String) = ReceiverUiState(
        nowPlaying = NowPlaying(resourceId = resourceId, title = "Blue Monday"),
        playingResourceId = playingResourceId,
    )

    @Test
    fun `a snapshot naming the playing resource is shown`() {
        assertEquals("Blue Monday", state("track-1", "track-1").nowPlayingForCurrentItem?.title)
    }

    @Test
    fun `a snapshot for a skipped-past track is never shown`() {
        assertNull(state("track-1", "track-2").nowPlayingForCurrentItem)
    }

    @Test
    fun `a snapshot arriving before its play is hidden until the play lands`() {
        val early = state("track-2", "")
        assertNull(early.nowPlayingForCurrentItem)
        assertEquals("Blue Monday", early.copy(playingResourceId = "track-2").nowPlayingForCurrentItem?.title)
    }

    @Test
    fun `a mirrored snapshot with no resource id is always shown`() {
        val mirrored = ReceiverUiState(
            nowPlaying = NowPlaying(title = "Official Video"),
            playingResourceId = "",
        )
        assertEquals("Official Video", mirrored.nowPlayingForCurrentItem?.title)
    }

    @Test
    fun `no snapshot means nothing to show`() {
        assertNull(ReceiverUiState().nowPlayingForCurrentItem)
    }

    @Test
    fun `a playing snapshot advances from the moment it landed`() {
        val snapshot = PlaybackSnapshot(
            state = PlaybackState(30_000, 600_000, playing = true, buffering = false),
            receivedAtMs = 1_000,
        )
        assertEquals(30_500, snapshot.positionAt(1_500))
    }

    @Test
    fun `a paused snapshot holds still however late the panel redraws`() {
        val snapshot = PlaybackSnapshot(
            state = PlaybackState(30_000, 600_000, playing = false, buffering = false),
            receivedAtMs = 1_000,
        )
        assertEquals(30_000, snapshot.positionAt(6_000))
    }

    @Test
    fun `a fresh snapshot re-anchors rather than compounding the last one`() {
        val first = PlaybackSnapshot(
            state = PlaybackState(10_000, 600_000, playing = true, buffering = false),
            receivedAtMs = 1_000,
        )
        assertEquals(10_500, first.positionAt(1_500))
        val second = PlaybackSnapshot(
            state = PlaybackState(3_000, 600_000, playing = true, buffering = false),
            receivedAtMs = 2_000,
        )
        assertEquals(3_500, second.positionAt(2_500))
    }
}
