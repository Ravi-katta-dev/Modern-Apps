package com.vayunmathur.globalesim.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.globalesim.data.api.ProfileDto
import com.vayunmathur.globalesim.data.api.RateDto
import com.vayunmathur.globalesim.platform.EsimUiState
import com.vayunmathur.library.ui.DynamicTheme

private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:globalesim`, rendered from Compose previews.
 * `./gradlew :globalesim:metadata` renders these into
 * `metadata_data/photos/globalesim/`. Literal state only — no ViewModel,
 * no network. Every preview needs both @PreviewTest and @Preview, must be
 * a class member, and is named Preview<N>… for listing order.
 */
class MetadataPreviews {
    private val demoProfile = ProfileDto(
        iccid = "8999201200000000001",
        status = "active",
        activationCode = "LPA:1$demo.example$CODE",
        smdpAddress = "demo.example",
        walletUsdCents = 1250,
    )
    private val demoRates = listOf(
        RateDto("US", "United States", 100),
        RateDto("JP", "Japan", 138),
        RateDto("FR", "France", 52),
        RateDto("GB", "United Kingdom", 95),
    )

    @PreviewTest
    @Preview(name = "1-home", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Home() {
        DynamicTheme(darkTheme = true) {
            HomeScreen(
                state = EsimUiState(profile = demoProfile, budgetCents = 2000),
                onTopUp = {},
                onStartCardPurchase = { _, _, _, _ -> },
                onCardPurchaseDone = {},
                onOpenInstall = {},
                onOpenRates = {},
                onOpenBudget = {},
                onOpenTransfer = {},
                onRefresh = {},
                onDismissError = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-rates", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Rates() {
        DynamicTheme(darkTheme = true) {
            RatesScreen(
                state = EsimUiState(rates = demoRates),
                onNavigateBack = {},
                onRefresh = {},
                onDismissError = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-install", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Install() {
        DynamicTheme(darkTheme = true) {
            InstallScreen(
                state = EsimUiState(profile = demoProfile),
                onNavigateBack = {},
                onDismissError = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-budget", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview4Budget() {
        DynamicTheme(darkTheme = true) {
            BudgetScreen(
                state = EsimUiState(profile = demoProfile, budgetCents = 2000),
                onNavigateBack = {},
                onRefresh = {},
                onDismissError = {},
            )
        }
    }
}
