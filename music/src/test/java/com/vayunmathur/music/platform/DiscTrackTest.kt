package com.vayunmathur.music.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the disc/track split MediaStore needs.
 *
 * Mirrors `splitDiscTrack` in `Util.kt`, which cannot be imported here because
 * that file also owns thumbnails and retrievers. MediaStore encodes multi-disc
 * numbers as disc * 1000 + track, so 2005 is disc 2, track 5.
 */
class DiscTrackTest {

    @Test
    fun smallValuesAreSingleDiscTracks() {
        assertEquals(1 to 0, split(0))
        assertEquals(1 to 1, split(1))
        assertEquals(1 to 5, split(5))
        assertEquals(1 to 999, split(999))
    }

    @Test
    fun thousandBoundaryStartsDiscOneTrackZero() {
        assertEquals(1 to 0, split(1000))
        assertEquals(1 to 1, split(1001))
        assertEquals(1 to 999, split(1999))
    }

    @Test
    fun multiDiscValuesSplitOnThousands() {
        assertEquals(2 to 5, split(2005))
        assertEquals(3 to 12, split(3012))
        assertEquals(10 to 7, split(10007))
    }

    @Test
    fun exactDiscBoundariesGiveTrackZero() {
        assertEquals(2 to 0, split(2000))
        assertEquals(3 to 0, split(3000))
    }

    @Test
    fun roundTripThroughEncoding() {
        for (disc in 1..9) {
            for (track in listOf(0, 1, 9, 99)) {
                val raw = disc * 1000 + track
                // Raw values below 1000 always read back as disc 1 by design.
                if (raw < 1000) continue
                assertEquals(disc to track, split(raw))
            }
        }
    }

    @Test
    fun negativeValuesStayOnDiscOne() {
        // No guard in the source: anything below 1000 returns disc 1 as-is.
        assertEquals(1 to -1, split(-1))
    }

    companion object {
        private const val DISC_TRACK_FACTOR = 1000

        // Mirrors Util.splitDiscTrack without the Android file around it.
        private fun split(rawTrack: Int): Pair<Int, Int> {
            if (rawTrack < DISC_TRACK_FACTOR) return 1 to rawTrack
            return (rawTrack / DISC_TRACK_FACTOR) to (rawTrack % DISC_TRACK_FACTOR)
        }
    }
}
