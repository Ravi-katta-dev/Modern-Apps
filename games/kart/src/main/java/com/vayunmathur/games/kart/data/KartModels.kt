package com.vayunmathur.games.kart.data

/**
 * A single grand-prix event.
 *
 * The scaffold ships no tracks yet: the cup is a named slot the race flow will fill in once the
 * engine lands.
 */
data class KartCup(
    val id: Int,
    val name: String,
    val trackCount: Int,
)

/** The three cups the full game will ship. Placeholder names until tracks are modelled. */
val CUPS = listOf(
    KartCup(id = 0, name = "Mushroom Cup", trackCount = 4),
    KartCup(id = 1, name = "Flower Cup", trackCount = 4),
    KartCup(id = 2, name = "Star Cup", trackCount = 4),
)

/**
 * Where the player stands.
 *
 * [currentCup] is the cup on screen; [racesWon] and [bestLapMs] feed the garage rows.
 * All start empty: nothing here pretends a race has happened.
 */
data class KartProgress(
    val currentCup: Int = 0,
    val racesWon: Int = 0,
    val bestLapMs: Long = 0L,
)
