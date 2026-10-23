package com.vayunmathur.contacts.data

import kotlinx.datetime.LocalDate
import kotlinx.datetime.format

/**
 * Portable string form of an event date for external stores: full ISO when the
 * year is known, "--MM-DD" when it isn't.
 *
 * Both the Android provider contract (Event.START_DATE, as read by Google
 * Contacts and third-party readers such as KashCal) and vCard BDAY/X-EVENT
 * expect the year-less "--MM-DD" form. Writing the internal 1604 sentinel year
 * instead (e.g. "1604-05-04") makes the birthday invisible to those readers,
 * even though our own read path accepts both forms.
 */
fun LocalDate.toExternalDateString(): String {
    if (hasYear) return format(LocalDate.Formats.ISO)
    // monthNumber/dayOfMonth: deprecated upstream, but the accessors used
    // across this module (see ContactDetailsFormat, EventDatePickerDialog).
    @Suppress("DEPRECATION")
    val month = monthNumber.toString().padStart(2, '0')
    @Suppress("DEPRECATION")
    val day = dayOfMonth.toString().padStart(2, '0')
    return "--$month-$day"
}

/**
 * Rewrites a stored "1604-MM-DD" no-year date to the "--MM-DD" external form.
 * Returns null when [stored] is not a zero-padded "1604-MM-DD" date, so dated
 * events and already-migrated rows are left untouched.
 */
fun rewriteNoYearSentinel(stored: String): String? {
    if (!stored.startsWith(NO_YEAR_SENTINEL_PREFIX)) return null
    val monthDay = stored.removePrefix(NO_YEAR_SENTINEL_PREFIX)
    if (!NO_YEAR_MONTH_DAY.matches(monthDay)) return null
    return "--$monthDay"
}

private const val NO_YEAR_SENTINEL_PREFIX = "1604-"

private val NO_YEAR_MONTH_DAY = Regex("\\d{2}-\\d{2}")
