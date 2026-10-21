package com.vayunmathur.everysync.sync

import com.vayunmathur.everysync.data.RemoteTodo
import com.vayunmathur.everysync.sync.adapters.PasswordKind
import com.vayunmathur.everysync.sync.adapters.TodoKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelayMergeTest {

    private fun todo(
        uid: String,
        title: String,
        updatedAt: Long,
        deleted: Boolean = false,
        position: Double = 0.0,
    ): RemoteTodo = RemoteTodo(
        uid = uid,
        title = title,
        position = position,
        createdAt = 7L,
        deleted = deleted,
        updatedAt = updatedAt,
    )

    private fun todoBaseline(e: RemoteTodo, now: Long = 1000L): RelaySnapshot =
        RelaySnapshot(e.uid, "todo", TodoKind.contentHash(e), e.updatedAt, e.updatedAt, now)

    // ---- empty and one-sided merges ----

    @Test
    fun emptyMergeProducesAnEmptyPlan() {
        val plan = RelayMerge.merge(TodoKind, emptyList(), emptyList(), emptyMap(), 1000L)
        assertTrue(plan.localUpserts.isEmpty())
        assertTrue(plan.localDeletes.isEmpty())
        assertTrue(plan.remoteOut.isEmpty())
        assertTrue(plan.snapshots.isEmpty())
        assertTrue(plan.removedSyncIds.isEmpty())
        assertEquals(0, plan.pushed)
        assertEquals(0, plan.pulled)
        assertEquals(false, plan.remoteChanged)
    }

    @Test
    fun localOnlyRecordWithoutBaselineIsPushed() {
        val local = todo("a", "Buy milk", 100L)
        val plan = RelayMerge.merge(TodoKind, listOf(local), emptyList(), emptyMap(), 1000L)
        assertEquals(1, plan.remoteOut.size)
        assertEquals("a", plan.remoteOut[0].uid)
        assertEquals(1, plan.pushed)
        assertEquals(0, plan.pulled)
        assertEquals(true, plan.remoteChanged)
        assertEquals(1, plan.snapshots.size)
        assertEquals("a", plan.snapshots[0].syncId)
    }

    @Test
    fun remoteOnlyRecordWithoutBaselineIsPulled() {
        val remote = todo("b", "Read", 50L)
        val plan = RelayMerge.merge(TodoKind, emptyList(), listOf(remote), emptyMap(), 1000L)
        assertEquals(1, plan.localUpserts.size)
        assertEquals("b", plan.localUpserts[0].uid)
        assertEquals(1, plan.pulled)
        assertEquals(0, plan.pushed)
        assertTrue(plan.remoteOut.isEmpty())
        assertEquals(1, plan.snapshots.size)
    }

    // ---- last-writer-wins conflict resolution ----

    @Test
    fun newerLocalEditWinsTheConflict() {
        val local = todo("a", "v2", 200L)
        val remote = todo("a", "v1", 100L)
        val plan = RelayMerge.merge(TodoKind, listOf(local), listOf(remote), emptyMap(), 1000L)
        assertEquals(1, plan.remoteOut.size)
        assertEquals("v2", plan.remoteOut[0].title)
        assertEquals(1, plan.pushed)
        assertEquals(0, plan.pulled)
    }

    @Test
    fun newerRemoteEditWinsTheConflict() {
        val local = todo("a", "v1", 100L)
        val remote = todo("a", "v2", 200L)
        val plan = RelayMerge.merge(TodoKind, listOf(local), listOf(remote), emptyMap(), 1000L)
        assertEquals(1, plan.localUpserts.size)
        assertEquals("v2", plan.localUpserts[0].title)
        assertEquals(1, plan.pulled)
        assertEquals(0, plan.pushed)
        assertTrue(plan.remoteOut.isEmpty())
    }

    @Test
    fun equalTimestampsResolveDeterministicallyByHash() {
        val local = todo("a", "aaa", 100L)
        val remote = todo("a", "zzz", 100L)
        assertTrue(TodoKind.contentHash(local) != TodoKind.contentHash(remote))
        val expectLocalWins = TodoKind.contentHash(local) >= TodoKind.contentHash(remote)
        val first = RelayMerge.merge(TodoKind, listOf(local), listOf(remote), emptyMap(), 1000L)
        val second = RelayMerge.merge(TodoKind, listOf(local), listOf(remote), emptyMap(), 1000L)
        assertEquals(expectLocalWins, first.pushed == 1)
        assertEquals(!expectLocalWins, first.pulled == 1)
        assertEquals(first.pushed, second.pushed)
        assertEquals(first.pulled, second.pulled)
    }

    // ---- tombstones ----

    @Test
    fun newerRemoteTombstoneDeletesTheLocalCopy() {
        val local = todo("a", "Buy milk", 100L)
        val remote = todo("a", "Buy milk", 200L, deleted = true)
        val plan = RelayMerge.merge(TodoKind, listOf(local), listOf(remote), emptyMap(), 1000L)
        assertEquals(1, plan.localUpserts.size)
        assertEquals(true, plan.localUpserts[0].deleted)
        assertEquals(1, plan.snapshots.size)
        assertEquals(true, plan.snapshots[0].deleted)
        assertEquals(1, plan.deletedLocal)
        assertEquals(1, plan.deletedRemote)
    }

    @Test
    fun newerLocalEditResurrectsOverAStaleDelete() {
        val local = todo("a", "Buy milk", 300L)
        val remote = todo("a", "Buy milk", 100L, deleted = true)
        val plan = RelayMerge.merge(TodoKind, listOf(local), listOf(remote), emptyMap(), 1000L)
        assertEquals(1, plan.localUpserts.size)
        assertEquals(false, plan.localUpserts[0].deleted)
        assertEquals(1, plan.remoteOut.size)
        assertEquals(false, plan.remoteOut[0].deleted)
        assertEquals(1, plan.pulled)
        assertEquals(true, plan.remoteChanged)
    }

    // ---- loss prevention: absence is never a delete ----

    @Test
    fun presentLocallyButMissingRemotelyIsNeverATombstone() {
        val agreed = todo("a", "Buy milk", 100L)
        val baseline = mapOf("a" to todoBaseline(agreed))
        val plan = RelayMerge.merge(TodoKind, listOf(agreed), emptyList(), baseline, 1000L)
        assertTrue(plan.remoteOut.isEmpty())
        assertTrue(plan.localDeletes.isEmpty())
        assertTrue(plan.localUpserts.isEmpty())
        assertTrue(plan.snapshots.isEmpty())
    }

    @Test
    fun missingLocallyWithBaselinePullsTheRemoteCopyBack() {
        val agreed = todo("a", "Buy milk", 100L)
        val baseline = mapOf("a" to todoBaseline(agreed))
        val plan = RelayMerge.merge(TodoKind, emptyList(), listOf(agreed), baseline, 1000L)
        assertEquals(1, plan.localUpserts.size)
        assertEquals("a", plan.localUpserts[0].uid)
        assertEquals(1, plan.pulled)
        assertTrue(plan.remoteOut.isEmpty())
    }

    @Test
    fun hardDeleteStorePropagatesALocalDeleteAsATombstone() {
        val live = PasswordKind.password(uid = "p1", name = "Bank", username = "u", updatedAt = 100L)
        val hash = PasswordKind.contentHash(live)
        val baseline = mapOf("p1" to RelaySnapshot("p1", "password", hash, 100L, 100L, 900L))
        val plan = RelayMerge.merge(PasswordKind, emptyList(), listOf(live), baseline, 1000L)
        assertEquals(1, plan.remoteOut.size)
        assertEquals(true, plan.remoteOut[0].deleted)
        assertEquals(true, plan.remoteChanged)
        assertEquals(1, plan.deletedRemote)
        assertEquals(1, plan.snapshots.size)
        assertEquals(true, plan.snapshots[0].deleted)
    }

    // ---- identity, ordering, hashing ----

    @Test
    fun blankLocalIdIsAssignedAndPushed() {
        val local = todo("", "No id yet", 100L)
        val plan = RelayMerge.merge(TodoKind, listOf(local), emptyList(), emptyMap(), 1000L)
        assertEquals(1, plan.remoteOut.size)
        assertEquals(1, plan.localUpserts.size)
        assertTrue(plan.remoteOut[0].uid.isNotBlank())
        assertEquals(plan.remoteOut[0].uid, plan.localUpserts[0].uid)
        assertEquals(1, plan.pushed)
    }

    @Test
    fun reorderAloneIsNotAnEdit() {
        val local = todo("a", "Buy milk", 100L, position = 1.0)
        val remote = todo("a", "Buy milk", 100L, position = 9.0)
        val plan = RelayMerge.merge(TodoKind, listOf(local), listOf(remote), emptyMap(), 1000L)
        assertEquals(1, plan.snapshots.size)
        assertEquals(0, plan.pushed)
        assertEquals(0, plan.pulled)
        assertTrue(plan.remoteOut.isEmpty())
    }

    @Test
    fun contentHashIsStableLowercaseHex() {
        val first = relayContentHash("hello")
        val second = relayContentHash("hello")
        assertEquals(first, second)
        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(relayContentHash("hello!") != first)
    }

    @Test
    fun newSyncIdsAreUniqueHexStrings() {
        val first = newRelaySyncId()
        val second = newRelaySyncId()
        assertEquals(32, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(first != second)
    }
}
