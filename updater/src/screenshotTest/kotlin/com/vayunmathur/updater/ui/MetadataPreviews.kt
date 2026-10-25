package com.vayunmathur.updater.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.updater.domain.UpdateMetadata
import com.vayunmathur.updater.platform.UpdaterUiState

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store-listing images for `:updater`, rendered from Compose previews instead of an
 * instrumented test on a device (the install path needs a privileged, platform-signed
 * install and cannot run here).
 *
 * `./gradlew :updater:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/updater/`, where `release.sh` picks them up.
 *
 * Each preview must carry @PreviewTest as well as @Preview and be a member of a class
 * (not a top-level function) or the screenshot engine silently skips it. Everything is
 * a literal so the output is reproducible from a clean checkout.
 */
class MetadataPreviews {

    private val current = UpdaterUiState(
        currentBuild = "v3.2.1-maos",
        autoInstall = true,
        meteredAllowed = false,
    )

    @PreviewTest
    @Preview(name = "1-never-checked", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1NeverChecked() {
        DynamicTheme(darkTheme = true) {
            UpdaterScreen(
                state = current,
                onCheckNow = {},
                onAutoInstallChanged = {},
                onMeteredAllowedChanged = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-checking", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Checking() {
        DynamicTheme(darkTheme = true) {
            UpdaterScreen(
                state = current.copy(checking = true),
                onCheckNow = {},
                onAutoInstallChanged = {},
                onMeteredAllowedChanged = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-up-to-date", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3UpToDate() {
        DynamicTheme(darkTheme = true) {
            UpdaterScreen(
                state = current.copy(lastCheckedMillis = 1_786_500_000_000L),
                onCheckNow = {},
                onAutoInstallChanged = {},
                onMeteredAllowedChanged = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-available", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview4Available() {
        DynamicTheme(darkTheme = true) {
            UpdaterScreen(
                state = current.copy(
                    lastCheckedMillis = 1_786_500_000_000L,
                    available = UpdateMetadata(
                        build = "v3.3.0-maos",
                        buildDateUtcSeconds = 1_786_585_200L,
                    ),
                ),
                onCheckNow = {},
                onAutoInstallChanged = {},
                onMeteredAllowedChanged = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "5-failed", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview5Failed() {
        DynamicTheme(darkTheme = true) {
            UpdaterScreen(
                state = current.copy(
                    lastCheckedMillis = 1_786_500_000_000L,
                    lastFailure = "could not reach https://ota.example.com/shiba-stable",
                ),
                onCheckNow = {},
                onAutoInstallChanged = {},
                onMeteredAllowedChanged = {},
            )
        }
    }
}
