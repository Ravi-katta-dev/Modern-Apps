package com.vayunmathur.contacts.ui.dialogs
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.util.ContactAccount
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.contacts.util.deleteLocalAccount
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.R as UiR

@Composable
fun DeleteAccountDialog(
    viewModel: ContactViewModel,
    target: ContactAccount,
    onDismiss: () -> Unit
) {
    ConfirmDialog(
        title = stringResource(R.string.delete_account),
        message = stringResource(R.string.delete_local_account_confirm),
        confirmLabel = stringResource(UiR.string.delete),
        dismissLabel = stringResource(UiR.string.cancel),
        destructive = true,
        onConfirm = {
            viewModel.deleteLocalAccount(target)
            onDismiss()
        },
        onDismiss = onDismiss
    )
}
