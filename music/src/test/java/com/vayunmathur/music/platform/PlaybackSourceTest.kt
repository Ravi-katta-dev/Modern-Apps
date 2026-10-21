package com.vayunmathur.music.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the queue-origin encoding in [PlaybackSource].
 *
 * The queue itself lives behind a service controller, so what is testable on the
 * host is the id scheme every queue carries: parse must invert id for every
 * variant and refuse anything it did not write.
 */
class PlaybackSourceTest {

    @Test
    fun allSongsRoundTrips() {
        assertEquals(PlaybackSource.AllSongs, PlaybackSource.parse("all_songs"))
        assertEquals("all_songs", PlaybackSource.AllSongs.id)
    }

    @Test
    fun albumPlaylistAndArtistRoundTrip() {
        assertEquals(PlaybackSource.Album(7), PlaybackSource.parse("album_7"))
        assertEquals(PlaybackSource.Playlist(12), PlaybackSource.parse("playlist_12"))
        assertEquals(PlaybackSource.Artist(3), PlaybackSource.parse("artist_3"))
        assertEquals("album_7", PlaybackSource.Album(7).id)
        assertEquals("playlist_12", PlaybackSource.Playlist(12).id)
        assertEquals("artist_3", PlaybackSource.Artist(3).id)
    }

    @Test
    fun nullAndUnknownIdsParseToNull() {
        assertNull(PlaybackSource.parse(null))
        assertNull(PlaybackSource.parse(""))
        assertNull(PlaybackSource.parse("songs"))
        assertNull(PlaybackSource.parse("ALBUM_1"))
        assertNull(PlaybackSource.parse("album"))
        assertNull(PlaybackSource.parse("playlist"))
    }

    @Test
    fun nonNumericSuffixParsesToNull() {
        assertNull(PlaybackSource.parse("album_"))
        assertNull(PlaybackSource.parse("album_abc"))
        assertNull(PlaybackSource.parse("playlist_12_extra"))
        assertNull(PlaybackSource.parse("artist_xyz"))
        assertNull(PlaybackSource.parse("album_1.5"))
    }

    @Test
    fun zeroAndLargeIdsRoundTrip() {
        assertEquals(PlaybackSource.Album(0), PlaybackSource.parse("album_0"))
        assertEquals(PlaybackSource.Playlist(0), PlaybackSource.parse("playlist_0"))
        val big = Long.MAX_VALUE
        assertEquals(PlaybackSource.Artist(big), PlaybackSource.parse("artist_$big"))
    }

    @Test
    fun negativeSuffixParsesWithoutValidation() {
        // No range check in parse: a negative id round-trips rather than failing.
        assertEquals(PlaybackSource.Album(-5), PlaybackSource.parse("album_-5"))
    }
}
