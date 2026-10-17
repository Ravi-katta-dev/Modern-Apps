package com.vayunmathur.games.kart

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.games.kart.data.CUPS
import com.vayunmathur.games.kart.data.GameMode
import com.vayunmathur.games.kart.data.KartProgress
import com.vayunmathur.games.kart.platform.KartGameActions
import com.vayunmathur.games.kart.platform.KartUiState
import com.vayunmathur.games.kart.platform.RacePhase
import com.vayunmathur.games.kart.ui.KartGameScreen
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.sdk.controller.LobbyState
import com.vayunmathur.sdk.controller.PlayerSlot

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * The store listing images.
 *
 * Previews must be members of a class and carry both `@PreviewTest` and `@Preview`, or the screenshot
 * engine discovers no tests. The `PreviewN` names set the order they appear in the listing, because
 * the collector sorts by filename. Run `./gradlew :games:kart:metadata`.
 *
 * The garage screen has no polling `LaunchedEffect`, so a preview settles on the first frame.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-garage", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Garage() {
        // The opening cup with a few wins on the board, so the shot shows the
        // progress line under the track count.
        Garage(
            KartUiState(
                cup = CUPS[0],
                progress = KartProgress(currentCup = 0, racesWon = 6),
            ),
            RacePhase.Lobby(LobbyState(gameId = "kart")),
        )
    }

    @PreviewTest
    @Preview(name = "2-racing", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Racing() {
        // Mid-race: the standings the placeholder engine produces, so the listing shows the
        // game beyond the garage.
        Garage(
            KartUiState(cup = CUPS[0]),
            RacePhase.Racing(
                order = listOf("you", "ai-0", "ai-1"),
                lapProgress = mapOf("you" to 0.6f, "ai-0" to 0.7f, "ai-1" to 0.4f),
                playerLap = 1,
                totalLaps = 3,
            ),
        )
    }

    @PreviewTest
    @Preview(name = "3-late-cup", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3LateCup() {
        // The last cup, so the listing shows the progression reaches past the opener.
        Garage(
            KartUiState(
                cup = CUPS[2],
                progress = KartProgress(currentCup = 2, cupsCleared = 2, racesWon = 11),
            ),
            RacePhase.Lobby(
                LobbyState(
                    slots = listOf(PlayerSlot("p1", "You", remote = false)),
                    gameId = "kart",
                ),
            ),
        )
    }

    @PreviewTest
    @Preview(name = "4-daily", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview4Daily() {
        Garage(
            KartUiState(
                cup = CUPS[1],
                mode = GameMode.DAILY,
            ),
            RacePhase.Lobby(LobbyState(gameId = "kart")),
            streak = 4,
        )
    }

    @Composable
    private fun Garage(state: KartUiState, phase: RacePhase, streak: Long = 0) {
        // Layoutlib has no wallpaper to sample, so Material You falls back to the default palette.
        DynamicTheme(darkTheme = true) {
            KartGameScreen(
                state = state.copy(dailyStreak = streak),
                phase = phase,
                actions = KartGameActions.Noop,
                onOpenSettings = {},
                onOpenGameCenter = {},
            )
        }
    }
}
