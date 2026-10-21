package com.vayunmathur.everysync.auth

import com.vayunmathur.everysync.provider.DataType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountConfigTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ---- tolerant decoding ----

    @Test
    fun knownTypesDecodeNormally() {
        val raw = """{"accountName":"a","providerId":"relay","enabledTypes":["TODOS","NOTES"]}"""
        val decoded = json.decodeFromString<AccountConfig>(raw)
        assertEquals(setOf(DataType.TODOS, DataType.NOTES), decoded.enabledTypes)
        assertEquals("a", decoded.accountName)
    }

    @Test
    fun unknownTypeNamesAreDroppedInsteadOfFailing() {
        val raw = """
            {"accountName":"a","providerId":"relay",
             "enabledTypes":["TODOS","FROM_THE_FUTURE","NOTES"]}
        """.trimIndent()
        val decoded = json.decodeFromString<AccountConfig>(raw)
        assertEquals(setOf(DataType.TODOS, DataType.NOTES), decoded.enabledTypes)
    }

    @Test
    fun allUnknownTypesDecodeToAnEmptySet() {
        val raw = """{"accountName":"a","providerId":"relay","enabledTypes":["NOPE","NEVER"]}"""
        assertEquals(emptySet(), json.decodeFromString<AccountConfig>(raw).enabledTypes)
    }

    @Test
    fun nonStringElementsAreDropped() {
        val raw = """
            {"accountName":"a","providerId":"relay",
             "enabledTypes":["TODOS",42,null,true,"NOTES"]}
        """.trimIndent()
        val decoded = json.decodeFromString<AccountConfig>(raw)
        assertEquals(setOf(DataType.TODOS, DataType.NOTES), decoded.enabledTypes)
    }

    @Test
    fun nonArrayValueDecodesToAnEmptySet() {
        val raw = """{"accountName":"a","providerId":"relay","enabledTypes":"TODOS"}"""
        assertEquals(emptySet(), json.decodeFromString<AccountConfig>(raw).enabledTypes)
    }

    @Test
    fun missingEnabledTypesDefaultsToEmpty() {
        val raw = """{"accountName":"a","providerId":"relay"}"""
        val decoded = json.decodeFromString<AccountConfig>(raw)
        assertEquals(emptySet(), decoded.enabledTypes)
        assertNull(decoded.intervalMinutes)
        assertEquals(0L, decoded.lastSyncEpochMs)
        assertNull(decoded.lastSyncError)
    }

    // ---- round trips ----

    @Test
    fun roundTripPreservesEveryField() {
        val original = AccountConfig(
            accountName = "c",
            providerId = "relay",
            enabledTypes = setOf(DataType.CONTACTS, DataType.CALENDAR, DataType.NOTES),
            intervalMinutes = 30,
            lastSyncEpochMs = 1234L,
            lastSyncError = "boom",
        )
        assertEquals(original, json.decodeFromString(json.encodeToString(original)))
    }

    @Test
    fun nullsAndEmptySetsRoundTrip() {
        val original = AccountConfig(accountName = "c", providerId = "relay")
        val decoded = json.decodeFromString<AccountConfig>(json.encodeToString(original))
        assertEquals(emptySet(), decoded.enabledTypes)
        assertNull(decoded.intervalMinutes)
        assertNull(decoded.lastSyncError)
        assertTrue(decoded.lastSyncEpochMs == 0L)
    }
}
