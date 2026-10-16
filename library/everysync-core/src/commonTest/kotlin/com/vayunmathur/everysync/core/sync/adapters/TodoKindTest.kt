package com.vayunmathur.everysync.core.sync.adapters

import com.vayunmathur.everysync.core.data.RemoteTodo
import com.vayunmathur.everysync.core.sync.RelayMerge
import com.vayunmathur.everysync.core.sync.RelaySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 1 drift regression: [RemoteTodo.notes] and [RemoteTodo.createdAt] are
 * part of the merge hash (via [TodoKind.canonical]). A remote edit to either
 * field must pull; before the fix the native model dropped both on persist so
 * LWW on those fields never converged.
 */
class TodoKindTest {

    private val base = RemoteTodo(uid = "id1", title = "Buy milk", createdAt = 1000L, updatedAt = 1000L)

    @Test
    fun remoteNotesEditPulls() {
        val remote = base.copy(notes = "oat milk", updatedAt = 2000L)
        val plan = RelayMerge.merge(
            TodoKind,
            listOf(base),
            listOf(remote),
            mapOf("id1" to RelaySnapshot("id1", "todo", TodoKind.contentHash(base), 1000L, 1000L, 1000L)),
            now = 3000L,
        )
        assertEquals(1, plan.pulled)
        assertEquals("oat milk", plan.localUpserts.single().notes)
        assertTrue(plan.remoteOut.isEmpty())
    }

    @Test
    fun localNotesEditPushes() {
        val local = base.copy(notes = "almond milk", updatedAt = 2000L)
        val plan = RelayMerge.merge(
            TodoKind,
            listOf(local),
            listOf(base),
            mapOf("id1" to RelaySnapshot("id1", "todo", TodoKind.contentHash(base), 1000L, 1000L, 1000L)),
            now = 3000L,
        )
        assertEquals(1, plan.pushed)
        assertEquals("almond milk", plan.remoteOut.single().notes)
    }

    @Test
    fun createdAtDiffersIsAnEdit() {
        // createdAt rides the envelope (adoption content key); a change must
        // not hash equal.
        val moved = base.copy(createdAt = 9999L, updatedAt = 2000L)
        val plan = RelayMerge.merge(TodoKind, listOf(base), listOf(moved), emptyMap(), now = 3000L)
        assertTrue(plan.pulled == 1 || plan.pushed == 1)
    }

    @Test
    fun reorderIsNotAnEdit() {
        val reordered = base.copy(position = 9.0, updatedAt = 1000L)
        assertEquals(TodoKind.contentHash(base), TodoKind.contentHash(reordered))
    }

    @Test
    fun tombstoneOnlyDelete() {
        // Absent-from-log without a tombstone is never a delete (loss-prevention).
        val plan = RelayMerge.merge(
            TodoKind,
            listOf(base),
            emptyList(),
            mapOf("id1" to RelaySnapshot("id1", "todo", TodoKind.contentHash(base), 1000L, 1000L, 1000L)),
            now = 3000L,
        )
        assertTrue(plan.localDeletes.isEmpty())
        assertTrue(plan.remoteOut.none { it.deleted })
    }
}
