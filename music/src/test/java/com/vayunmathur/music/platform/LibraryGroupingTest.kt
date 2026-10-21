package com.vayunmathur.music.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the library grouping and ordering the browse tree relies on.
 *
 * Mirrors `albumArtistPairs` plus the album and playlist sorts in
 * `MusicLibraryTree`, which cannot be imported here because both files also
 * own thumbnails, cursors and service state. The rules pinned are the ones a
 * reader checks: unmatched songs drop, pairs dedupe, album tracks sort by
 * disc then track then title, playlists keep library order.
 */
class LibraryGroupingTest {

    private data class Song(val id: Long, val albumId: Long, val artistId: Long)
    private data class Album(val id: Long, val name: String)
    private data class Artist(val id: Long, val name: String)
    private data class Track(val disc: Int, val track: Int, val title: String)
    private data class Matching(val leftId: Long, val rightId: Long, val type: Int)

    private fun pairs(
        songs: List<Song>,
        artists: List<Artist>,
        albums: List<Album>,
    ): List<Pair<Album, Artist>> {
        val albumMap = albums.associateBy { it.id }
        val artistMap = artists.associateBy { it.id }
        return songs.mapNotNull { song ->
            val album = albumMap[song.albumId]
            val artist = artistMap[song.artistId]
            if (album == null || artist == null) null else album to artist
        }.distinct()
    }

    private fun sortedForAlbum(items: List<Track>): List<Track> {
        return items.sortedWith(compareBy({ it.disc }, { it.track }, { it.title.lowercase() }))
    }

    private fun songsInPlaylist(songs: List<Song>, matchings: List<Matching>, playlistId: Long): List<Song> {
        val ids = matchings.filter { it.type == 300 && it.rightId == playlistId }.map { it.leftId }.toSet()
        return songs.filter { it.id in ids }
    }

    @Test
    fun matchedSongsPairAndUnmatchedDrop() {
        val songs = listOf(Song(1, 10, 100), Song(2, 99, 100), Song(3, 10, 199))
        val albums = listOf(Album(10, "Blue"))
        val artists = listOf(Artist(100, "New Order"))
        assertEquals(listOf(albums[0] to artists[0]), pairs(songs, artists, albums))
    }

    @Test
    fun duplicatePairsDedupe() {
        val songs = listOf(Song(1, 10, 100), Song(2, 10, 100))
        val albums = listOf(Album(10, "Blue"))
        val artists = listOf(Artist(100, "New Order"))
        assertEquals(1, pairs(songs, artists, albums).size)
    }

    @Test
    fun emptyLibraryGivesNoPairs() {
        assertTrue(pairs(emptyList(), emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun albumTracksSortByDiscThenTrackThenTitle() {
        val tracks = listOf(
            Track(1, 2, "b"),
            Track(1, 1, "a"),
            Track(2, 1, "c"),
            Track(1, 1, "B"),
        )
        val sorted = sortedForAlbum(tracks)
        assertEquals(listOf(1, 1, 1, 2), sorted.map { it.disc })
        assertEquals(listOf(1, 1, 2, 1), sorted.map { it.track })
        assertEquals("a", sorted[0].title)
    }

    @Test
    fun titleTieBreakIsCaseInsensitive() {
        val tracks = listOf(Track(1, 1, "banana"), Track(1, 1, "Apple"))
        assertEquals(listOf("Apple", "banana"), sortedForAlbum(tracks).map { it.title })
    }

    @Test
    fun playlistKeepsLibraryOrderAndIgnoresOtherLists() {
        val songs = listOf(Song(1, 10, 100), Song(2, 10, 100), Song(3, 10, 100))
        val matchings = listOf(Matching(3, 7, 300), Matching(1, 7, 300), Matching(2, 8, 300))
        assertEquals(listOf(1L, 3L), songsInPlaylist(songs, matchings, 7).map { it.id })
        assertTrue(songsInPlaylist(songs, matchings, 999).isEmpty())
    }
}
