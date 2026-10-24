package com.vayunmathur.globalesim

import androidx.compose.runtime.Composable
import com.vayunmathur.globalesim.platform.EsimViewModel
import com.vayunmathur.globalesim.ui.BudgetScreen
import com.vayunmathur.globalesim.ui.HomeScreen
import com.vayunmathur.globalesim.ui.InstallScreen
import com.vayunmathur.globalesim.ui.RatesScreen
import com.vayunmathur.globalesim.ui.TransferScreen
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun Navigation(viewModel: EsimViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Home)

    MainNavigation(backStack) {
        entry<Route.Home> {
            HomeScreen(
                state = viewModel.state,
                onTopUp = viewModel::topUp,
                onStartCardPurchase = viewModel::startCardPurchase,
                onCardPurchaseDone = {
                    viewModel.clearPendingPurchase()
                    viewModel.refreshAll()
                },
                onOpenInstall = { backStack.add(Route.Install) },
                onOpenRates = { backStack.add(Route.Rates) },
                onOpenBudget = { backStack.add(Route.Budget) },
                onOpenTransfer = { backStack.add(Route.Transfer) },
                onRefresh = viewModel::refreshAll,
                onDismissError = viewModel::dismissError,
            )
        }
        entry<Route.Rates> {
            RatesScreen(
                state = viewModel.state,
                onNavigateBack = { backStack.pop() },
                onRefresh = viewModel::refreshRates,
                onDismissError = viewModel::dismissError,
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
                    viewModel.transferIn(oldId, code) { backStack.reset(Route.Home) }
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
