package com.vayunmathur.games.kart.domain

import com.vayunmathur.games.kart.data.CUPS

/**
 * The garage rules, as pure functions on progress counters.
 *
 * There is no race engine yet, so this is deliberately thin: it decides which cup is on screen
 * and whether a cup index is playable. Physics, items and AI will grow their own files beside
 * this one rather than bloating it.
 */
object KartRules {

    /** The cup on screen for [cupIndex], wrapping past the end of the list. */
    fun cupAt(cupIndex: Int) = CUPS[cupIndex.mod(CUPS.size)]

    /** Whether [cupIndex] names a real cup rather than a pointer past the end. */
    fun isUnlocked(cupIndex: Int, cupsCleared: Int): Boolean = cupIndex <= cupsCleared

    /** The next cup after [cupIndex], or null once the last cup is cleared. */
    fun nextCup(cupIndex: Int): Int? =
        if (cupIndex + 1 < CUPS.size) cupIndex + 1 else null
}
