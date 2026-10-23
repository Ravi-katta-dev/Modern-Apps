package com.vayunmathur.web.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.web.R
import com.vayunmathur.web.data.WebSearchNewsItem
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** News-result list for [SearchResultsPage]: source + date meta over snippets. */
@Composable
fun SearchNewsResults(
    items: List<WebSearchNewsItem>,
    onOpenResult: (String) -> Unit,
) {
    if (items.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.search_no_results),
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(items, key = { it.url + it.title }) { item ->
            ListItem(
                headlineContent = {
                    Text(item.title.ifBlank { item.url }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    Column {
                        val meta = listOfNotNull(
                            item.source.takeIf { it.isNotBlank() },
                            formatNewsDate(item.date),
                        ).joinToString(" • ")
                        if (meta.isNotBlank()) {
                            Text(
                                meta,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (item.snippet.isNotBlank()) {
                            Text(
                                item.snippet,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                modifier = Modifier.clickable { onOpenResult(item.url) },
            )
        }
    }
}

private fun formatNewsDate(unixSecs: Long): String? {
    if (unixSecs <= 0) return null
    return runCatching {
        Instant.fromEpochSeconds(unixSecs)
            .toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
    }.getOrNull()
}
