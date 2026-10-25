package com.vayunmathur.uniflix.ui.tv

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.vayunmathur.library.tv.TvShelf
import com.vayunmathur.uniflix.domain.MediaItem

/**
 * One catalog shelf inside a [TvShelf]: posters that open detail on CENTER and
 * report focus so a hero can follow. Empty lists render no shelf at all.
 */
internal fun LazyListScope.catalogShelf(
    key: String,
    title: String,
    eyebrow: String?,
    items: List<MediaItem>,
    firstKey: String?,
    firstFocus: FocusRequester,
    onFocus: (MediaItem) -> Unit,
    onOpenDetail: (MediaItem) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = key) {
        TvShelf(title = title) {
            items(items, key = ::mediaKey) { item ->
                val itemKey = mediaKey(item)
                MediaPosterCard(
                    item = item,
                    onOpen = { onOpenDetail(item) },
                    modifier = if (itemKey == firstKey) Modifier.focusRequester(firstFocus) else Modifier,
                    onFocus = { onFocus(item) },
                )
            }
        }
    }
}
