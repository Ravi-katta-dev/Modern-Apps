package com.vayunmathur.globalesim

import android.content.Intent
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.vayunmathur.globalesim.platform.EsimViewModel
import com.vayunmathur.globalesim.ui.BudgetScreen
import com.vayunmathur.globalesim.ui.HomeScreen
import com.vayunmathur.globalesim.ui.InstallScreen
import com.vayunmathur.globalesim.ui.PricingScreen
import com.vayunmathur.globalesim.ui.TransferScreen
import com.vayunmathur.library.ui.IconGlobe
import com.vayunmathur.library.ui.IconSim
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.coroutines.launch

@Composable
fun Navigation(viewModel: EsimViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Tabs)

    MainNavigation(backStack) {
        entry<Route.Tabs> {
            EsimTabs(
                viewModel = viewModel,
                onOpenInstall = { backStack.add(Route.Install) },
                onOpenBudget = { backStack.add(Route.Budget) },
                onOpenTransfer = { backStack.add(Route.Transfer) },
            )
        }
        entry<Route.Install> {
            InstallScreen(
                state = viewModel.state,
                onNavigateBack = { backStack.pop() },
                onDismissError = viewModel::dismissError,
            )
        }
        entry<Route.Transfer> {
            TransferScreen(
                state = viewModel.state,
                onTransferOut = viewModel::transferOut,
                onTransferIn = { oldId, code ->
                    viewModel.transferIn(oldId, code) { backStack.reset(Route.Tabs) }
                },
                onNavigateBack = { backStack.pop() },
            )
        }
        entry<Route.Budget> {
            BudgetScreen(
                state = viewModel.state,
                onNavigateBack = { backStack.pop() },
                onRefresh = viewModel::refreshBudget,
                onDismissError = viewModel::dismissError,
            )
        }
    }
}

/**
 * The two bottom-nav tabs: Purchase (your eSIM + top-ups) and Pricing
 * (searchable per-country rates). Install, Transfer, and Budget push on
 * top as ordinary routes.
 */
@Composable
private fun EsimTabs(
    viewModel: EsimViewModel,
    onOpenInstall: () -> Unit,
    onOpenBudget: () -> Unit,
    onOpenTransfer: () -> Unit,
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { 2 })
    val tabs = listOf(
        PagerTab("Purchase", { IconSim() }) {
            HomeScreen(
                state = viewModel.state,
                onTopUp = viewModel::topUp,
                onCreateEsim = viewModel::createEsim,
                onStartCardPurchase = viewModel::startCardPurchase,
                onCardPurchaseDone = {
                    viewModel.clearPendingPurchase()
                    viewModel.refreshAll()
                },
                onStartCryptoPurchase = { cents ->
                    viewModel.startCryptoPurchase(cents) { url ->
                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    }
                },
                onCheckCryptoStatus = {
                    viewModel.checkCryptoStatus { status ->
                        if (status == "finished") viewModel.refreshAll()
                    }
                },
                onClearCryptoInvoice = viewModel::clearPendingInvoice,
                onOpenInstall = onOpenInstall,
                onOpenBudget = onOpenBudget,
                onOpenTransfer = onOpenTransfer,
                onDismissError = viewModel::dismissError,
            )
        },
        PagerTab("Pricing", { IconGlobe() }) {
            PricingScreen(
                state = viewModel.state,
                onDismissError = viewModel::dismissError,
            )
        },
    )
    TabbedPagerScaffold(tabs = tabs, pagerState = pagerState, tabStyle = TabStyle.BottomNav)
}
