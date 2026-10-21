package com.vayunmathur.email.sync

import com.vayunmathur.email.data.EmailSyncState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the process-wide sync indicator: starting resets progress, progress
 * stays inside 0..1 no matter what the worker reports, and finishing parks
 * the bar at full before hiding it.
 */
class EmailSyncStateTest {

    @Test
    fun `starting sync resets progress and shows the indicator`() {
        EmailSyncState.start()
        assertTrue(EmailSyncState.isSyncing.value)
        assertEquals(0f, EmailSyncState.progress.value)
        EmailSyncState.finish()
    }

    @Test
    fun `progress is clamped into range`() {
        EmailSyncState.start()
        EmailSyncState.setProgress(-2f)
        assertEquals(0f, EmailSyncState.progress.value)
        EmailSyncState.setProgress(2f)
        assertEquals(1f, EmailSyncState.progress.value)
        EmailSyncState.setProgress(0.25f)
        assertEquals(0.25f, EmailSyncState.progress.value)
        EmailSyncState.finish()
    }

    @Test
    fun `finishing parks at full and hides the indicator`() {
        EmailSyncState.start()
        EmailSyncState.setProgress(0.3f)
        EmailSyncState.finish()
        assertFalse(EmailSyncState.isSyncing.value)
        assertEquals(1f, EmailSyncState.progress.value)
    }
}
