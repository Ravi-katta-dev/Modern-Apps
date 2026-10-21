package com.vayunmathur.music.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the year extraction the tag backfill runs on every file.
 *
 * Mirrors `getAudioYear` in `Util.kt`, which cannot be imported here because
 * that file also owns thumbnails and retrievers. The rule is one regex: the
 * first four-digit run in YEAR, else in DATE, else 0.
 */
class YearParseTest {

    private fun parseYearTag(raw: String?): Int {
        return raw?.let { value -> Regex("\\d{4}").find(value)?.value?.toIntOrNull() } ?: 0
    }

    @Test
    fun plainYearParses() {
        assertEquals(2023, parseYearTag("2023"))
        assertEquals(1999, parseYearTag("1999"))
    }

    @Test
    fun nullAndBlankGiveZero() {
        assertEquals(0, parseYearTag(null))
        assertEquals(0, parseYearTag(""))
        assertEquals(0, parseYearTag("   "))
    }

    @Test
    fun dateStringsGiveTheirFirstYear() {
        assertEquals(2023, parseYearTag("2023-05-01"))
        assertEquals(1977, parseYearTag("1977/06/23"))
        assertEquals(2009, parseYearTag("2009-12-31T23:59:59"))
    }

    @Test
    fun freeTextGivesItsFirstFourDigitRun() {
        assertEquals(1999, parseYearTag("Released 1999 Remaster"))
        assertEquals(1977, parseYearTag("Year: 1977 (Remastered 2009)"))
    }

    @Test
    fun shortOrNonNumericTextGivesZero() {
        assertEquals(0, parseYearTag("99"))
        assertEquals(0, parseYearTag("abcd"))
        assertEquals(0, parseYearTag("no year here"))
    }

    @Test
    fun fiveDigitRunsStillYieldTheirFirstFourDigits() {
        // Regex takes the first four of a longer run rather than refusing it.
        assertEquals(2023, parseYearTag("20234"))
    }
}
