package com.vayunmathur.contacts.ui.dialogs
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.util.ContactAccount
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.contacts.util.renameLocalAccount
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.util.AppMessages

@Composable
fun RenameAccountDialog(
    viewModel: ContactViewModel,
    target: ContactAccount,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var newName by remember(target) { mutableStateOf(target.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_account)) },
        text = {
            TextField(
                value = newName,
                onValueChange = { newName = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.account_name)) }
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val name = newName.trim()
                if (name.isNotEmpty()) {
                    viewModel.renameLocalAccount(target, name) { ok, err ->
                        if (!ok && err == "collision") {
                            AppMessages.show(context.getString(R.string.account_name_exists))
                        }
                    }
                    onDismiss()
                }
            }) { Text(stringResource(UiR.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(UiR.string.cancel))
            }
        }
    )
}
