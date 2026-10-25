package com.vayunmathur.email.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.email.R
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.EmailFolder
import com.vayunmathur.email.data.accountColor
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconInbox
import com.vayunmathur.library.ui.IconMail
import com.vayunmathur.library.ui.IconSend
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalDrawerSheet
import com.vayunmathur.library.ui.NavigationDrawerItem
import com.vayunmathur.library.ui.NavigationDrawerItemDefaults
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.contentColorOn
import com.vayunmathur.library.ui.R as UiR

/**
 * Navigation-drawer content for `:email`, decoupled from the ViewModel and the
 * nav back-stack: pure data in, lambdas out, so previews can render it with
 * literals. The [onOpenSystem] key is `"settings"` or `"drafts"`.
 */
@Composable
internal fun EmailDrawerContent(
    accounts: List<EmailAccount>,
    selectedAccountEmail: String?,
    folders: List<EmailFolder>,
    selectedFolderName: String,
    outboxCount: Int,
    onSelectAccount: (String) -> Unit,
    onSelectFolder: (String) -> Unit,
    onOpenOutbox: () -> Unit,
    onOpenSystem: (String) -> Unit,
    onLogout: () -> Unit,
    onAddAccount: () -> Unit,
) {
    ModalDrawerSheet {
        Column(Modifier.fillMaxHeight()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(R.string.unified_inbox_label),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.all_accounts)) },
                    selected = selectedAccountEmail == null,
                    onClick = { onSelectAccount("") },
                    icon = { IconInbox() },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(R.string.accounts),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                accounts.forEach { account ->
                    NavigationDrawerItem(
                        label = { Text(account.email) },
                        selected = account.email == selectedAccountEmail,
                        onClick = { onSelectAccount(account.email) },
                        icon = {
                            val accountColor = Color(accountColor(account.email))
                            Surface(
                                shape = CircleShape,
                                color = accountColor,
                                modifier = Modifier.size(24.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    IconMail(
                                        modifier = Modifier.size(16.dp),
                                        tint = contentColorOn(accountColor),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.add_account)) },
                    selected = false,
                    onClick = onAddAccount,
                    icon = { IconAdd() },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                if (selectedAccountEmail != null) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(
                        stringResource(R.string.folders),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    FolderList(folders, selectedFolderName, onSelectFolder)
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                NavigationDrawerItem(
                    label = {
                        Text(
                            if (outboxCount == 0) {
                                stringResource(R.string.outbox)
                            } else {
                                stringResource(R.string.outbox_with_count, outboxCount)
                            },
                        )
                    },
                    selected = false,
                    onClick = onOpenOutbox,
                    icon = { IconSend() },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(UiR.string.settings)) },
                    selected = false,
                    onClick = { onOpenSystem("settings") },
                    icon = { IconSettings() },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.drafts)) },
                    selected = false,
                    onClick = { onOpenSystem("drafts") },
                    icon = { IconEdit() },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
            if (selectedAccountEmail != null) {
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.logout_current_account)) },
                    selected = false,
                    onClick = onLogout,
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    }
}
