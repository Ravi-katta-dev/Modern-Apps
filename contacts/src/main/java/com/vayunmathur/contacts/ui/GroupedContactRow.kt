package com.vayunmathur.contacts.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface

/**
 * One row of a grouped card, as its own lazy item.
 *
 * The list used to render a whole letter group as a single lazy item wrapping a column of rows, which
 * meant no individual contact was ever a lazy item and `animateItem` had nothing to attach to - so
 * adding or deleting a contact snapped. Splitting them keeps the same rounded-end card look, since
 * only the first and last row of a group are rounded, and [groupShape] already works from an index.
 */
@Composable
fun GroupedContactRow(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = groupShape(index, count),
        color = containerColor,
        content = content,
    )
}
