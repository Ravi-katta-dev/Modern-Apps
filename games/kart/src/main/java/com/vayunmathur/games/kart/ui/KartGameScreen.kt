package com.vayunmathur.games.kart.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vayunmathur.games.kart.R
import com.vayunmathur.games.kart.data.GameMode
import com.vayunmathur.games.kart.platform.KartGameActions
import com.vayunmathur.games.kart.platform.KartUiState
import com.vayunmathur.games.kart.platform.RacePhase
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.game.DailyStreakText
import com.vayunmathur.library.ui.game.GameModeChooser
import com.vayunmathur.library.ui.game.GameTopBarActions

/**
 * The garage and the race, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`.
 */
@Composable
fun KartGameScreen(
    state: KartUiState,
    phase: RacePhase,
    actions: KartGameActions,
    onOpenSettings: () -> Unit,
    onOpenGameCenter: () -> Unit,
) {
    val daily = state.mode == GameMode.DAILY
    AppScaffold(
        title = {
            GameModeChooser(
                selected = state.mode,
                options = GameMode.entries,
                // The ladder mode folds the cup into its label, so the bar needs no second title.
                label = { mode ->
                    when (mode) {
                        GameMode.CASUAL -> state.cup?.let {
                            stringResource(R.string.cup_title, it.name)
                        } ?: stringResource(R.string.app_name)

                        GameMode.DAILY -> stringResource(R.string.mode_daily)
                    }
                },
                menuLabel = { mode ->
                    stringResource(
                        when (mode) {
                            GameMode.CASUAL -> R.string.mode_casual
                            GameMode.DAILY -> R.string.mode_daily
                        },
                    )
                },
                onSelect = actions::setGameMode,
            )
        },
        alignment = AppBarAlignment.Center,
        actions = {
            GameTopBarActions(
                onOpenGameCenter = onOpenGameCenter,
                onOpenSettings = onOpenSettings,
            )
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (daily) {
                DailyStreakText(state.dailyStreak)
            }

            when (val p = phase) {
                is RacePhase.Lobby -> LobbyBody(state, p, daily, actions)
                is RacePhase.Countdown -> CountdownBody(p)
                is RacePhase.Racing -> RacingBody(p)
                is RacePhase.Results -> ResultsBody(p, actions)
            }
        }
    }
}

@Composable
private fun LobbyBody(
    state: KartUiState,
    phase: RacePhase.Lobby,
    daily: Boolean,
    actions: KartGameActions,
) {
    val cup = state.cup
    if (cup == null) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(Spacing.xxl),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.garage_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        Text(
            stringResource(R.string.cup_tracks, cup.trackCount),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (state.progress.racesWon > 0) {
            Text(
                stringResource(R.string.races_won, state.progress.racesWon),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val seats = phase.lobby.slots.size
        if (seats > 0) {
            Text(
                stringResource(R.string.lobby_seats, seats),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (daily && state.dailyDone) {
            Text(
                stringResource(R.string.daily_come_back_tomorrow),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        } else {
            Button(
                onClick = { actions.startRace() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.start_race)) }
            if (!daily) {
                Text(
                    stringResource(R.string.how_to_play),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun CountdownBody(phase: RacePhase.Countdown) {
    Text(
        "${phase.secondsLeft}",
        style = MaterialTheme.typography.displayLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        stringResource(R.string.countdown_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun RacingBody(phase: RacePhase.Racing) {
    // The native 3D viewport sits behind the standings HUD. With no
    // libkart_engine it is never composed and the HUD is the whole screen;
    // in a @Preview (Layoutlib) a SurfaceView is a blank rect, so listing
    // images keep rendering the HUD exactly as before.
    if (com.vayunmathur.games.kart.util.KartNative.isAvailable) {
        AndroidView(
            factory = { ctx ->
                // No opaque background: a SurfaceView shows its Vulkan layer
                // through a transparent hole in the window.
                KartSurfaceView(ctx)
            },
            modifier = Modifier.fillMaxWidth().height(240.dp),
        )
    }
    Text(
        stringResource(R.string.race_lap, phase.playerLap + 1, phase.totalLaps),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    phase.order.forEachIndexed { place, id ->
        Column(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.race_place, place + 1, displayName(id)),
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { phase.lapProgress[id] ?: 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Short label for a seat: players keep their join name, AI karts show as rivals. */
private fun displayName(id: String): String = if (id.startsWith("ai-")) "Rival ${id.removePrefix("ai-")}" else "You"

@Composable
private fun ResultsBody(phase: RacePhase.Results, actions: KartGameActions) {
    Text(
        stringResource(R.string.results_title),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    phase.order.forEachIndexed { place, id ->
        Text(
            stringResource(R.string.race_place, place + 1, displayName(id)),
            style = MaterialTheme.typography.bodyMedium,
            color = if (id == phase.winner) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
    }
    OutlinedButton(
        onClick = { actions.backToLobby() },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.back_to_lobby)) }
}
