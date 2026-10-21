package com.vayunmathur.todo.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins due-date semantics with `java.time` fixtures (never platform ICU):
 * overdue means strictly before now, null means no date, and day buckets
 * come from UTC dates so they behave identically on every device.
 *
 * The app has no recurrence and no pure date helpers — the list only formats
 * via `DateString` (platform-backed, untestable here) — so these pin the
 * and comparison rules the UI needs: overdue highlighting and nulls-last
 * sorting.
 */
class TodoDueDateTest {

    private val now = Instant.parse("2026-10-21T12:00:00Z").toEpochMilli()

    private fun isOverdue(todo: Todo, at: Long): Boolean {
        val due = todo.dueAt ?: return false
        return due < at
    }

    private fun dayBucket(dueAt: Long): LocalDate =
        Instant.ofEpochMilli(dueAt).atOffset(ZoneOffset.UTC).toLocalDate()

    @Test
    fun `a past due date is overdue and a future one is not`() {
        assertTrue(isOverdue(Todo(dueAt = now - 1), now))
        assertFalse(isOverdue(Todo(dueAt = now), now))
        assertFalse(isOverdue(Todo(dueAt = now + 1), now))
    }

    @Test
    fun `a missing due date is never overdue`() {
        assertFalse(isOverdue(Todo(dueAt = null), now))
        assertFalse(isOverdue(Todo(dueAt = null), Long.MAX_VALUE))
    }

    @Test
    fun `done tasks keep their due date for the record`() {
        val todo = Todo(done = true, dueAt = now - 1_000)
        assertTrue(isOverdue(todo, now))
        assertEquals(now - 1_000, todo.dueAt)
    }

    @Test
    fun `due dates sort nulls last then earliest first`() {
        val todos = listOf(
            Todo(id = 1, dueAt = null),
            Todo(id = 2, dueAt = now + 86_400_000),
            Todo(id = 3, dueAt = now - 1),
            Todo(id = 4, dueAt = now),
        )
        val ordered = todos.sortedWith(compareBy(nullsLast(), Todo::dueAt)).map { it.id }
        assertEquals(listOf(3L, 4L, 2L, 1L), ordered)
    }

    @Test
    fun `epoch millis map to the expected UTC calendar day`() {
        assertEquals(LocalDate.of(2026, 10, 21), dayBucket(now))
        assertEquals(LocalDate.of(2026, 10, 20), dayBucket(now - 86_400_000))
        assertEquals(LocalDate.of(1970, 1, 1), dayBucket(0L))
    }

    @Test
    fun `midnight boundaries bucket adjacent days apart`() {
        val midnight = LocalDate.of(2026, 10, 22).atStartOfDay(ZoneOffset.UTC)
        val justBefore = midnight.toInstant().toEpochMilli() - 1
        val at = midnight.toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 10, 21), dayBucket(justBefore))
        assertEquals(LocalDate.of(2026, 10, 22), dayBucket(at))
    }

    @Test
    fun `far-future and epoch due dates are valid instants`() {
        assertEquals(LocalDate.of(1970, 1, 1), dayBucket(0L))
        val far = Instant.parse("2100-01-01T00:00:00Z").toEpochMilli()
        assertEquals(LocalDate.of(2100, 1, 1), dayBucket(far))
        assertFalse(isOverdue(Todo(dueAt = far), now))
    }

    @Test
    fun `unicode titles do not affect date comparisons`() {
        val todo = Todo(title = "記 😀 مرحبا", dueAt = now - 1)
        assertTrue(isOverdue(todo, now))
    }
}
