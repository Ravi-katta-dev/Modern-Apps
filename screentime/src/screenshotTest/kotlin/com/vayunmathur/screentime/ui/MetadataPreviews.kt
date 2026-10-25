package com.vayunmathur.screentime.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.screentime.data.AppTimer
import com.vayunmathur.screentime.data.WindDownSchedule
import com.vayunmathur.screentime.platform.AppDetailsUiState
import com.vayunmathur.screentime.platform.ScreenTimeUiState
import com.vayunmathur.screentime.platform.TimedApp
import com.vayunmathur.screentime.platform.UsageBar
import com.vayunmathur.screentime.platform.UsagePeriod
import java.time.LocalDate

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Fixed anchor so the renders are reproducible from a clean checkout (a Monday). */
private val ANCHOR = LocalDate.of(2026, 10, 19)

/**
 * Store-listing images for `:screentime`, rendered from Compose previews instead of an
 * instrumented test on a device (usage stats need a real grant and real history).
 *
 * `./gradlew :screentime:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/screentime/`, where `release.sh` picks them up.
 *
 * Each preview must carry @PreviewTest as well as @Preview and be a member of a class
 * (not a top-level function) or the screenshot engine silently skips it. Everything is
 * a literal so the output is reproducible from a clean checkout. Launcher icons render
 * as blank space under Layoutlib (no PackageManager there) — rows show without icons.
 */
class MetadataPreviews {

    private val weekBars = listOf(
        UsageBar("Mon", 5_400_000L),
        UsageBar("Tue", 7_200_000L),
        UsageBar("Wed", 3_600_000L),
        UsageBar("Thu", 9_000_000L),
        UsageBar("Fri", 6_300_000L),
        UsageBar("Sat", 10_800_000L),
        UsageBar("Sun", 4_500_000L),
    )

    private val sampleApps = listOf(
        TimedApp(
            packageName = "com.example.social",
            label = "Social App",
            usedMillis = 12_600_000L,
            timer = AppTimer("com.example.social", 60),
        ),
        TimedApp(
            packageName = "com.example.video",
            label = "Video App",
            usedMillis = 9_000_000L,
            timer = null,
        ),
        TimedApp(
            packageName = "com.example.mail",
            label = "Mail App",
            usedMillis = 3_000_000L,
            timer = AppTimer("com.example.mail", 30),
        ),
    )

    private val dashboardState = ScreenTimeUiState(
        period = UsagePeriod.Week,
        anchorDate = ANCHOR,
        chart = weekBars,
        totalMillis = 46_800_000L,
        averageMillis = 6_685_714L,
        apps = sampleApps,
        windDown = WindDownSchedule(
            enabled = true,
            startMinute = 22 * 60,
            endMinute = 7 * 60,
            daysMask = WindDownSchedule.ALL_DAYS,
            grayscale = true,
            doNotDisturb = true,
        ),
        loading = false,
        hasUsageAccess = true,
        installedLoaded = true,
    )

    private val detailsState = AppDetailsUiState(
        packageName = "com.example.social",
        label = "Social App",
        period = UsagePeriod.Week,
        anchorDate = ANCHOR,
        chart = weekBars,
        totalMillis = 12_600_000L,
        averageMillis = 1_800_000L,
        timerMinutes = 60,
        appPaused = false,
        hasUsageAccess = true,
    )

    private val noActions = DashboardActions(
        onSetPeriod = {},
        onStep = {},
        onOpenApp = {},
    )

    @PreviewTest
    @Preview(name = "1-dashboard", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Dashboard() {
        DynamicTheme(darkTheme = true) {
            DashboardScreen(state = dashboardState, actions = noActions)
        }
    }

    @PreviewTest
    @Preview(name = "2-app-details", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2AppDetails() {
        DynamicTheme(darkTheme = true) {
            AppDetailsScreen(
                state = detailsState,
                onBack = {},
                onSetPeriod = {},
                onStep = {},
                onTimerChange = {},
                onPausedChange = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-wind-down", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3WindDown() {
        DynamicTheme(darkTheme = true) {
            WindDownScreen(
                state = dashboardState,
                onEnabledChange = {},
                onStartChange = { _, _ -> },
                onEndChange = { _, _ -> },
                onToggleDay = {},
                onGrayscaleChange = {},
                onDndChange = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-friction", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview4Friction() {
        DynamicTheme(darkTheme = true) {
            FrictionScreen(
                appLabel = "Social App",
                nudge = true,
                onContinue = {},
                onTakeABreak = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "5-no-access", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview5NoAccess() {
        DynamicTheme(darkTheme = true) {
            DashboardScreen(
                state = ScreenTimeUiState(
                    period = UsagePeriod.Week,
                    anchorDate = ANCHOR,
                    loading = false,
                    hasUsageAccess = false,
                    installedLoaded = true,
                ),
                actions = noActions,
            )
        }
    }

    @PreviewTest
    @Preview(name = "6-loading", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview6Loading() {
        DynamicTheme(darkTheme = true) {
            DashboardScreen(
                state = ScreenTimeUiState(
                    period = UsagePeriod.Week,
                    anchorDate = ANCHOR,
                    loading = true,
                    hasUsageAccess = true,
                    installedLoaded = false,
                ),
                actions = noActions,
            )
        }
    }
}
