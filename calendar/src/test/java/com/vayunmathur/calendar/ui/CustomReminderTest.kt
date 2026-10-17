package com.vayunmathur.calendar.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CustomReminderTest {

    @Test
    fun eachUnitMultipliesToMinutes() {
        assertEquals(45, customReminderMinutes(45, REMINDER_UNIT_MULTIPLIERS[0]))
        assertEquals(120, customReminderMinutes(2, REMINDER_UNIT_MULTIPLIERS[1]))
        assertEquals(4320, customReminderMinutes(3, REMINDER_UNIT_MULTIPLIERS[2]))
        assertEquals(10080, customReminderMinutes(1, REMINDER_UNIT_MULTIPLIERS[3]))
    }

    @Test
    fun zeroOrNegativeAmountIsRejected() {
        assertNull(customReminderMinutes(0, 1))
        assertNull(customReminderMinutes(-3, 60))
    }

    @Test
    fun overflowIsRejected() {
        assertNull(customReminderMinutes(Long.MAX_VALUE, 7 * 24 * 60))
        assertNull(customReminderMinutes(Int.MAX_VALUE.toLong() + 1, 1))
    }
}
