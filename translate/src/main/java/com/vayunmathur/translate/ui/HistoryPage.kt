package com.vayunmathur.translate.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.translate.data.TranslationRecord
import com.vayunmathur.translate.platform.HistoryActions
import com.vayunmathur.translate.platform.HistoryUiState
import com.vayunmathur.translate.platform.TranslateViewModel

/**
 * Binds [TranslateViewModel] to the stateless [HistoryScreen].
 *
 * The ViewModel exposes the history and favorites as hot flows; this page
 * collects them and assembles the actions. [onReuse] loads the record back
 * into the text screen via the navigation back-stack.
 */
@Composable
fun HistoryPage(
    viewModel: TranslateViewModel,
    onReuse: (TranslationRecord) -> Unit,
    onBack: () -> Unit,
) {
    val history by viewModel.history.collectAsState()

    HistoryScreen(
        state = HistoryUiState(records = history),
        actions = object : HistoryActions {
            override fun toggleFavorite(id: Long, favorite: Boolean) =
                viewModel.setFavorite(id, favorite)

            override fun delete(record: TranslationRecord) =
                viewModel.deleteHistory(record)

            override fun clearHistory() = viewModel.clearHistory()

            override fun reuse(record: TranslationRecord) = onReuse(record)

            override fun goBack() = onBack()
        },
    )
}
