package com.vayunmathur.securemail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.securemail.platform.MailViewModel
import com.vayunmathur.securemail.ui.ComposeScreen
import com.vayunmathur.securemail.ui.InboxScreen
import com.vayunmathur.securemail.ui.LockScreen
import com.vayunmathur.securemail.ui.OnboardingScreen
import com.vayunmathur.securemail.ui.OutboxScreen
import com.vayunmathur.securemail.ui.ReaderScreen
import com.vayunmathur.securemail.ui.SettingsScreen

@Composable
fun Navigation(viewModel: MailViewModel, initialRoute: Route? = null) {
    val state = viewModel.state
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsState(emptyList())
    val outbox by viewModel.outboxCount.collectAsState(0)
    val outboxMessages by viewModel.outboxMessages.collectAsState(emptyList())
    // The stack follows the account flag, not navigation events: once an address
    // is claimed there is no going back to onboarding, and a locked key gates
    // everything. reset() rather than add() for the same reason as backup/.
    val start = when {
        !state.onboarded -> Route.Onboarding
        state.locked -> Route.Lock
        else -> Route.Inbox
    }
    val backStack = rememberNavBackStack<Route>(start)

    LaunchedEffect(state.onboarded, state.locked) {
        val target = when {
            !state.onboarded -> Route.Onboarding
            state.locked -> Route.Lock
            backStack.last() == Route.Onboarding || backStack.last() == Route.Lock -> Route.Inbox
            else -> return@LaunchedEffect
        }
        if (backStack.last() != target) backStack.reset(target)
    }

    LaunchedEffect(initialRoute) {
        if (initialRoute != null) backStack.add(initialRoute)
    }

    MainNavigation(backStack) {
        entry<Route.Onboarding> {
            OnboardingScreen(
                state = state,
                onClaim = viewModel::claimAddress,
                onDismissMessages = viewModel::dismissMessages,
            )
        }
        entry<Route.Lock> {
            LockScreen(
                state = state,
                onUnlock = {
                    (context as? FragmentActivity)?.let { viewModel.unlock(it) }
                },
                onDismissMessages = viewModel::dismissMessages,
                onPassphrase = viewModel::providePassphrase,
            )
        }
        entry<Route.Inbox> {
            InboxScreen(
                state = state,
                onOpen = { backStack.add(Route.Reader(it)) },
                onCompose = { backStack.add(Route.Composer()) },
                onOutbox = { backStack.add(Route.Outbox) },
                onSettings = { backStack.add(Route.Settings) },
                onRefresh = viewModel::refresh,
                onConsumeMessage = viewModel::dismissMessages,
                messages = messages,
                outbox = outbox,
            )
        }
        entry<Route.Composer> { route ->
            ComposeScreen(
                state = state,
                initialTo = route.to,
                initialSubject = route.subject,
                initialBody = route.body,
                onSend = viewModel::send,
                onBack = { backStack.pop() },
                onSent = { backStack.pop() },
                onConsumeMessage = viewModel::dismissMessages,
            )
        }
        entry<Route.Reader> { route ->
            val msg = remember(route.messageId, messages) {
                messages.firstOrNull { it.id == route.messageId }
            }
            LaunchedEffect(route.messageId) {
                viewModel.decryptStored(route.messageId)
                viewModel.markRead(route.messageId)
            }
            ReaderScreen(
                state = state,
                messageId = route.messageId,
                onReply = { m ->
                    backStack.add(
                        Route.Composer(
                            to = m.from,
                            subject = "Re: ${m.decryptedSubject ?: ""}",
                            inReplyTo = m.id,
                        ),
                    )
                },
                onBack = { backStack.pop() },
                message = msg,
                onProvidePassphrase = viewModel::providePassphrase,
                onRetryDecrypt = { viewModel.decryptStored(route.messageId) },
            )
        }
        entry<Route.Outbox> {
            OutboxScreen(
                state = state,
                onRetry = viewModel::retryOutbox,
                onBack = { backStack.pop() },
                pending = outboxMessages,
            )
        }
        entry<Route.Settings> {
            SettingsScreen(
                state = state,
                onExportPublicKey = viewModel::exportPublicKey,
                onForget = viewModel::forgetIdentity,
                onBack = { backStack.pop() },
                onDismissMessages = viewModel::dismissMessages,
            )
        }
    }
}
