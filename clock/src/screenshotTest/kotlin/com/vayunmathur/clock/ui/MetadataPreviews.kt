package com.vayunmathur.clock.ui

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.clock.platform.AlarmActions
import com.vayunmathur.clock.platform.AlarmUiState
import com.vayunmathur.clock.platform.ClockUiState
import com.vayunmathur.clock.platform.StopwatchActions
import com.vayunmathur.clock.platform.StopwatchUiState
import com.vayunmathur.clock.platform.TimerActions
import com.vayunmathur.clock.platform.TimerUiState
import com.vayunmathur.clock.platform.WorldClock
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconAccessTime
import com.vayunmathur.library.ui.IconAlarm
import com.vayunmathur.library.ui.IconHourglass
import com.vayunmathur.library.ui.IconTimer
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavResultRegistry
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Weekday bitmasks: bit 0 = Sunday ... bit 6 = Saturday. */
private const val WEEKDAYS = 0b0111110
private const val EVERY_DAY = 0b1111111
private const val WEEKEND = 0b1000001

/**
 * A Friday morning, fixed so every render is identical: 09:41:09 in San Francisco.
 * Everything the clock tab draws is derived from this instant plus an explicit zone, so
 * nothing here reads the host machine's clock or time zone.
 */
private val MORNING = Instant.parse("2026-02-13T17:41:09Z")

/**
 * Store listing images for `:clock`, rendered from Compose previews instead of from an
 * instrumented test on a device. See `common-conventions-preview-metadata`.
 *
 * One image per bottom-nav tab, in tab order. Each preview needs @PreviewTest as well as
 * @Preview (@Preview alone renders in Studio but is not collected as a screenshot test),
 * previews must be members of a class rather than top-level functions, and listing order
 * comes from the function names — `Preview1…`, `Preview2…`. All three failure modes
 * surface as the same unhelpful "did not discover any tests".
 *
 * Previews render through [ChromePreview] — the real [TabbedPagerScaffold] with the
 * app's real tabs — not bare screens, so the bottom bar users see is in the shot.
 * Only the selected tab is literal.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-alarm", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Alarm() {
        ChromePreview(selectedTab = 0)
    }

    @PreviewTest
    @Preview(name = "2-clock", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Clock() {
        ChromePreview(selectedTab = 1)
    }

    @PreviewTest
    @Preview(name = "3-timer", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Timer() {
        ChromePreview(selectedTab = 2)
    }

    @PreviewTest
    @Preview(name = "4-stopwatch", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview4Stopwatch() {
        ChromePreview(selectedTab = 3)
    }
}

@Composable
private fun AlarmContent() {
    // An alarm card receives its time-picker result through ResultEffect, which
    // reads LocalNavResultRegistry. Outside MainNavigation nothing provides it and
    // composition fails, so hand it a throwaway one — no preview sends a result.
    CompositionLocalProvider(LocalNavResultRegistry provides remember { NavResultRegistry() }) {
        AlarmScreen(
            backStack = rememberNavBackStack<Route>(Route.Alarm),
            state = AlarmUiState(
                alarms = listOf(
                    Alarm(time = LocalTime(6, 30), name = "Wake up", enabled = true, days = WEEKDAYS, id = 1),
                    Alarm(time = LocalTime(8, 0), name = "Gym", enabled = true, days = EVERY_DAY, id = 2),
                    Alarm(time = LocalTime(9, 30), name = "Weekend lie-in", enabled = false, days = WEEKEND, id = 3),
                ),
            ),
            actions = AlarmActions.Noop,
            initialExpandedAlarmId = 1L,
        )
    }
}

@Composable
private fun ClockContent() {
    ClockScreen(
        backStack = rememberNavBackStack<Route>(Route.Clock),
        state = ClockUiState(
            now = { MORNING },
            zone = TimeZone.of("America/Los_Angeles"),
            worldClocks = listOf(
                WorldClock("London", TimeZone.of("Europe/London")),
                WorldClock("Paris", TimeZone.of("Europe/Paris")),
                WorldClock("Tokyo", TimeZone.of("Asia/Tokyo")),
                WorldClock("Sydney", TimeZone.of("Australia/Sydney")),
            ),
        ),
    )
}

@Composable
private fun TimerContent() {
    TimerScreen(
        // No timers yet, which is what puts the keypad up; "1000" is the digits
        // for a ten-minute timer, mid-entry.
        state = TimerUiState(now = { MORNING }),
        actions = TimerActions.Noop,
        initialKeypadInput = "1000",
    )
}

@Composable
private fun StopwatchContent() {
    StopwatchScreen(
        state = StopwatchUiState(
            isRunning = true,
            countingTime = 3.minutes + 42.seconds + 810.milliseconds,
            // Cumulative, oldest first — the screen derives the splits, and colours
            // the fastest green and the slowest red.
            lapTimes = listOf(
                1.minutes + 12.seconds + 350.milliseconds,
                2.minutes + 28.seconds + 900.milliseconds,
                3.minutes + 30.seconds + 120.milliseconds,
            ),
        ),
        actions = StopwatchActions.Noop,
    )
}

/**
 * The app's real tab chrome around preview screen content: the same
 * [TabbedPagerScaffold] in [TabStyle.BottomNav] that `ClockTabs` uses, with the
 * same four tabs in the same order — the pager starts on [selectedTab], so the
 * bar highlights the shown screen. Labels/icons are the app's own.
 */
@Composable
private fun ChromePreview(selectedTab: Int) {
    DynamicTheme(darkTheme = true) {
        // TabbedPagerScaffold hosts a HorizontalPager (no NavDisplay), so no
        // dispatcher owner is needed — unlike MainNavigation-based chrome.
        val tabs = listOf(
            PagerTab("Alarm", { IconAlarm() }) { AlarmContent() },
            PagerTab("Clock", { IconAccessTime() }) { ClockContent() },
            PagerTab("Timer", { IconHourglass() }) { TimerContent() },
            PagerTab("Stopwatch", { IconTimer() }) { StopwatchContent() },
        )
        TabbedPagerScaffold(
            tabs = tabs,
            pagerState = rememberPagerState(initialPage = selectedTab, pageCount = { tabs.size }),
            tabStyle = TabStyle.BottomNav,
        )
    }
}
