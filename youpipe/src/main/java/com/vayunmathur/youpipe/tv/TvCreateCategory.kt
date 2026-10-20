package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Checkbox
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.util.decodeHtml
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlinx.coroutines.launch

/**
 * TV create/update subscription group: name field + focusable member checklist.
 *
 * Same validation as the phone dialog (non-blank name, non-empty selection, no duplicate on
 * create). Each subscription row is a focusable `Surface` with a checkbox; the confirm row
 * applies `replaceCategory` and pops. BACK dismisses without applying.
 */
@Composable
fun TvCreateCategoryPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
    id: String?,
) {
    val subscriptions by ypvm.subscriptions.collectAsState()
    val categories by ypvm.subscriptionCategories.collectAsState()
    val categoryNames = categories.map { it.category }
    var categoryName by remember { mutableStateOf(id ?: "") }
    val subscriptionsAlreadyInCategory by remember(categoryName) {
        ypvm.subscriptionsInCategory(categoryName)
    }.collectAsState(initial = emptyList())
    var selectedSubscriptions by remember(subscriptionsAlreadyInCategory) {
        mutableStateOf(subscriptionsAlreadyInCategory)
    }
    val scope = rememberCoroutineScope()
    val confirmFocus = remember { FocusRequester() }

    val canConfirm = categoryName.isNotBlank() && selectedSubscriptions.isNotEmpty() &&
        (id != null || categoryName !in categoryNames)

    TvInitialFocus(confirmFocus)
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Text(
            text = if (id == null) {
                stringResource(R.string.title_create_subscription_category)
            } else {
                stringResource(R.string.title_update_subscription_category)
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        androidx.compose.foundation.text.BasicTextField(
            value = categoryName,
            onValueChange = { categoryName = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            textStyle = MaterialTheme.typography.titleMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
        )
        Text(
            text = stringResource(R.string.label_select_subscriptions),
            style = MaterialTheme.typography.titleMedium,
        )
        LazyColumn(
            modifier = Modifier.weight(1f).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(subscriptions, key = { it.id }) { subscription ->
                val checked = subscription in selectedSubscriptions
                Surface(
                    onClick = {
                        selectedSubscriptions = if (checked) {
                            selectedSubscriptions - subscription
                        } else {
                            selectedSubscriptions + subscription
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(
                            text = subscription.name.decodeHtml(),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Button(
                onClick = {
                    scope.launch {
                        ypvm.replaceCategory(id, categoryName, selectedSubscriptions.map { it.id })
                        backStack.pop()
                    }
                },
                enabled = canConfirm,
                modifier = Modifier.focusRequester(confirmFocus),
            ) {
                Text(
                    if (id == null) {
                        stringResource(UiR.string.create)
                    } else {
                        stringResource(R.string.action_update)
                    },
                )
            }
            Button(onClick = { backStack.pop() }) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    }
}
