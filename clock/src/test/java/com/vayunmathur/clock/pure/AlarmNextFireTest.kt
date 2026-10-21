package com.vayunmathur.clock.pure

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the alarm next-fire computation: repeat-day bitmask, one-shot
 * fallback, week wrap-around and daylight-saving edges.
 *
 * Mirrors platform/AlarmScheduler's wall-clock semantics (bit 0 is
 * Sunday, days == 0 is a one-shot) using java.time so the host JVM can
 * run it with explicit zones instead of the device default.
 */
class AlarmNextFireTest {
    private val utc = ZoneId.of("UTC")
    private val newYork = ZoneId.of("America/New_York")

    private fun maskOf(vararg days: DayOfWeek): Int {
        var mask = 0
        for (day in days) {
            val bit = if (day == DayOfWeek.SUNDAY) 0 else day.value
            mask = mask or (1 shl bit)
        }
        return mask
    }

    private fun nextFire(
        now: ZonedDateTime,
        alarmTime: LocalTime,
        daysMask: Int,
    ): ZonedDateTime? {
        var candidate = ZonedDateTime.of(now.toLocalDate(), alarmTime, now.zone)
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        if (daysMask == 0) return candidate
        repeat(7) {
            val bit = if (candidate.dayOfWeek == DayOfWeek.SUNDAY) 0 else candidate.dayOfWeek.value
            if ((daysMask and (1 shl bit)) != 0) return candidate
            candidate = candidate.plusDays(1)
        }
        return null
    }

    private fun at(date: LocalDate, time: LocalTime, zone: ZoneId): ZonedDateTime =
        ZonedDateTime.of(date, time, zone)

    @Test
    fun oneShotLaterTodayFiresToday() {
        val now = at(LocalDate.of(2026, 6, 1), LocalTime.of(8, 0), utc)
        val fire = nextFire(now, LocalTime.of(9, 0), 0)!!
        assertEquals(at(LocalDate.of(2026, 6, 1), LocalTime.of(9, 0), utc), fire)
    }

    @Test
    fun oneShotEarlierTodayFiresTomorrow() {
        val now = at(LocalDate.of(2026, 6, 1), LocalTime.of(10, 0), utc)
        val fire = nextFire(now, LocalTime.of(9, 0), 0)!!
        assertEquals(at(LocalDate.of(2026, 6, 2), LocalTime.of(9, 0), utc), fire)
    }

    @Test
    fun oneShotAtExactNowFiresTomorrow() {
        val now = at(LocalDate.of(2026, 6, 1), LocalTime.of(9, 0), utc)
        val fire = nextFire(now, LocalTime.of(9, 0), 0)!!
        assertEquals(at(LocalDate.of(2026, 6, 2), LocalTime.of(9, 0), utc), fire)
    }

    @Test
    fun recurringTodaySelectedFiresToday() {
        val monday = at(LocalDate.of(2026, 6, 1), LocalTime.of(8, 0), utc)
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        val fire = nextFire(monday, LocalTime.of(9, 0), maskOf(DayOfWeek.MONDAY))!!
        assertEquals(at(LocalDate.of(2026, 6, 1), LocalTime.of(9, 0), utc), fire)
    }

    @Test
    fun recurringTodayPassedAdvancesToNextSelectedDay() {
        val monday = at(LocalDate.of(2026, 6, 1), LocalTime.of(10, 0), utc)
        val mask = maskOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
        val fire = nextFire(monday, LocalTime.of(9, 0), mask)!!
        assertEquals(at(LocalDate.of(2026, 6, 3), LocalTime.of(9, 0), utc), fire)
        assertEquals(DayOfWeek.WEDNESDAY, fire.dayOfWeek)
    }

    @Test
    fun recurringWrapsAcrossWeekBoundary() {
        val saturday = at(LocalDate.of(2026, 6, 6), LocalTime.of(10, 0), utc)
        assertEquals(DayOfWeek.SATURDAY, saturday.dayOfWeek)
        val fire = nextFire(saturday, LocalTime.of(9, 0), maskOf(DayOfWeek.SUNDAY))!!
        assertEquals(at(LocalDate.of(2026, 6, 7), LocalTime.of(9, 0), utc), fire)
        assertEquals(DayOfWeek.SUNDAY, fire.dayOfWeek)
    }

    @Test
    fun allDaysBehavesLikeDaily() {
        val now = at(LocalDate.of(2026, 6, 1), LocalTime.of(10, 0), utc)
        val fire = nextFire(now, LocalTime.of(9, 0), 0x7F)!!
        assertEquals(at(LocalDate.of(2026, 6, 2), LocalTime.of(9, 0), utc), fire)
    }

    @Test
    fun sundayBitZeroMapsToSunday() {
        val monday = at(LocalDate.of(2026, 6, 1), LocalTime.of(8, 0), utc)
        val fire = nextFire(monday, LocalTime.of(9, 0), 1 shl 0)!!
        assertEquals(DayOfWeek.SUNDAY, fire.dayOfWeek)
        assertEquals(LocalDate.of(2026, 6, 7), fire.toLocalDate())
    }

    @Test
    fun saturdayBitSixMapsToSaturday() {
        val monday = at(LocalDate.of(2026, 6, 1), LocalTime.of(8, 0), utc)
        val fire = nextFire(monday, LocalTime.of(9, 0), 1 shl 6)!!
        assertEquals(DayOfWeek.SATURDAY, fire.dayOfWeek)
        assertEquals(LocalDate.of(2026, 6, 6), fire.toLocalDate())
    }

    @Test
    fun invalidMaskWithOnlyHighBitsReturnsNull() {
        val now = at(LocalDate.of(2026, 6, 1), LocalTime.of(8, 0), utc)
        assertNull(nextFire(now, LocalTime.of(9, 0), 1 shl 9))
    }

    @Test
    fun mixedValidAndHighBitsStillMatchesValidDay() {
        val monday = at(LocalDate.of(2026, 6, 1), LocalTime.of(8, 0), utc)
        val mask = maskOf(DayOfWeek.MONDAY) or (1 shl 12)
        val fire = nextFire(monday, LocalTime.of(9, 0), mask)!!
        assertEquals(at(LocalDate.of(2026, 6, 1), LocalTime.of(9, 0), utc), fire)
    }

    @Test
    fun midnightAlarmJustBeforeMidnightFiresOneMinuteLater() {
        val now = at(LocalDate.of(2026, 6, 1), LocalTime.of(23, 59), utc)
        val fire = nextFire(now, LocalTime.MIDNIGHT, 0)!!
        assertEquals(at(LocalDate.of(2026, 6, 2), LocalTime.MIDNIGHT, utc), fire)
    }

    @Test
    fun noonAndMidnightAreDistinctFires() {
        val now = at(LocalDate.of(2026, 6, 1), LocalTime.of(0, 1), utc)
        val noon = nextFire(now, LocalTime.NOON, 0)!!
        assertEquals(at(LocalDate.of(2026, 6, 1), LocalTime.NOON, utc), noon)
        val midnight = nextFire(now, LocalTime.MIDNIGHT, 0)!!
        assertEquals(at(LocalDate.of(2026, 6, 2), LocalTime.MIDNIGHT, utc), midnight)
    }

    @Test
    fun springForwardGapResolvesForwardWithoutThrowing() {
        val eve = at(LocalDate.of(2026, 3, 7), LocalTime.of(10, 0), newYork)
        val fire = nextFire(eve, LocalTime.of(2, 30), 0)!!
        assertEquals(LocalDate.of(2026, 3, 8), fire.toLocalDate())
        assertTrue(fire.toInstant().isAfter(eve.toInstant()), "gap fire must be in the future")
        assertEquals("-04:00", fire.offset.id)
    }

    @Test
    fun fallBackOverlapUsesEarlierOffsetByDefault() {
        val morning = at(LocalDate.of(2026, 11, 1), LocalTime.of(0, 30), newYork)
        val fire = nextFire(morning, LocalTime.of(1, 30), 0)!!
        assertEquals(LocalTime.of(1, 30), fire.toLocalTime())
        assertEquals("-04:00", fire.offset.id)
    }

    @Test
    fun dailyAcrossSpringForwardIs23Hours() {
        val before = at(LocalDate.of(2026, 3, 7), LocalTime.of(9, 0), newYork)
        val fire = nextFire(before, LocalTime.of(9, 0), 0x7F)!!
        val hours = java.time.Duration.between(before.toInstant(), fire.toInstant()).toHours()
        assertEquals(23L, hours)
    }

    @Test
    fun dailyAcrossFallBackIs25Hours() {
        val before = at(LocalDate.of(2026, 10, 31), LocalTime.of(9, 0), newYork)
        val fire = nextFire(before, LocalTime.of(9, 0), 0x7F)!!
        val hours = java.time.Duration.between(before.toInstant(), fire.toInstant()).toHours()
        assertEquals(25L, hours)
    }
}
