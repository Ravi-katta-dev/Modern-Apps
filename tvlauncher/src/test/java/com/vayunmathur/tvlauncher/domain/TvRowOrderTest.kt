package com.vayunmathur.tvlauncher.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class TvRowOrderTest {
    private val installed = listOf(
        "com.google.android.youtube.tv",
        "com.netflix.ninja",
        "com.example.game",
        "com.example.other",
    )

    @Test
    fun savedOrderWinsVerbatimMinusMissing() {
        val saved = listOf("com.netflix.ninja", "com.gone.app", "com.example.game")
        assertEquals(
            listOf("com.netflix.ninja", "com.example.game"),
            TvRowOrder.mergeOrder(saved, installed),
        )
    }

    @Test
    fun noSavedOrderTopsUpDefaultsWithInstalled() {
        assertEquals(
            listOf(
                "com.google.android.youtube.tv",
                "com.netflix.ninja",
                "com.example.game",
                "com.example.other",
            ),
            TvRowOrder.mergeOrder(
                saved = null,
                installed = installed,
                defaults = listOf("com.google.android.youtube.tv", "com.netflix.ninja"),
            ),
        )
    }

    @Test
    fun topUpStopsAtOneFullRow() {
        val many = (1..10).map { "com.example.app$it" } + installed
        val row = TvRowOrder.mergeOrder(null, many, defaults = emptyList())
        assertEquals(TvRowOrder.PER_ROW, row.size)
    }

    @Test
    fun moveSlidesEntry() {
        val order = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), TvRowOrder.move(order, 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), TvRowOrder.move(order, 3, 0))
        assertEquals(order, TvRowOrder.move(order, 1, 1))
        assertEquals(order, TvRowOrder.move(order, 9, 0))
    }

    @Test
    fun replaceSwapsWithoutDuplicating() {
        val order = listOf("a", "b", "c")
        assertEquals(listOf("a", "x", "c"), TvRowOrder.replaceAt(order, 1, "x"))
        // Replacing with a package already on the row moves it rather than duplicating.
        assertEquals(listOf("b", "a"), TvRowOrder.replaceAt(order.take(2), 5, "a"))
        assertEquals(order, TvRowOrder.replaceAt(order, 1, ""))
    }

    @Test
    fun hdmiSlotsLeaveRoomForStoreSettingsAdd() {
        assertEquals(3, TvRowOrder.maxHdmiSlots(hasStore = true))
        assertEquals(4, TvRowOrder.maxHdmiSlots(hasStore = false))
    }
}
