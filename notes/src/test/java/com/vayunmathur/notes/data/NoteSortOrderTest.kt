package com.vayunmathur.notes.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the position ordering the list renders: `NotesListScreen` re-sorts the
 * filtered rows by `position`, and drag moves assign fractional slots between
 * neighbours (midpoint) or +/- 50 past an edge.
 *
 * The midpoint/edge constants live inline in the screen, so they are mirrored
 * here rather than called. The mirror is exact: midpoint, `- 50.0`, `+ 50.0`.
 */
class NoteSortOrderTest {

    private fun midpoint(prev: Double?, next: Double?): Double = when {
        prev == null -> (next ?: 0.0) - 50.0
        next == null -> prev + 50.0
        else -> (prev + next) / 2.0
    }

    private fun note(id: Long, position: Double) =
        Note(id = id, title = "t$id", content = "", position = position)

    @Test
    fun `rows render sorted by position`() {
        val notes = listOf(note(1, 30.0), note(2, 10.0), note(3, 20.0))
        assertEquals(listOf(2L, 3L, 1L), notes.sortedBy { it.position }.map { it.id })
    }

    @Test
    fun `a drop between two rows lands exactly halfway`() {
        assertEquals(15.0, midpoint(10.0, 20.0))
        assertEquals(0.0, midpoint(-10.0, 10.0))
    }

    @Test
    fun `a drop past an edge steps 50 beyond it`() {
        assertEquals(-40.0, midpoint(null, 10.0))
        assertEquals(60.0, midpoint(10.0, null))
        assertEquals(-50.0, midpoint(null, null))
    }

    @Test
    fun `repeated midpoints keep a strict order without colliding`() {
        var lo = 0.0
        var hi = 100.0
        val slots = List(20) {
            val mid = midpoint(lo, hi)
            hi = mid
            mid
        }
        assertEquals(slots.sortedDescending(), slots)
        assertTrue(slots.toSet().size == slots.size)
        assertTrue(slots.all { it in 0.0..100.0 })
    }

    @Test
    fun `negative and fractional positions sort stably`() {
        val notes = listOf(note(1, -5.5), note(2, 0.0), note(3, -5.25), note(4, 1e15))
        assertEquals(listOf(1L, 3L, 2L, 4L), notes.sortedBy { it.position }.map { it.id })
    }

    @Test
    fun `withPosition slots a note between neighbours`() {
        val notes = listOf(note(1, 10.0), note(2, 20.0), note(3, 30.0))
        val moved = note(4, 0.0).withPosition(midpoint(20.0, 30.0))
        val ordered = (notes + moved).sortedBy { it.position }.map { it.id }
        assertEquals(listOf(1L, 2L, 4L, 3L), ordered)
    }

    @Test
    fun `sorting an empty list stays empty`() {
        assertEquals(emptyList(), emptyList<Note>().sortedBy { it.position })
    }
}
