package com.vayunmathur.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.calendar.R
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OdfMarkdownEditorController
import com.vayunmathur.library.ui.OdfMarkdownEditorField
import com.vayunmathur.library.ui.Text

/**
 * The event description editor: an outlined box that sizes to its content (no minimum
 * height). The [R.string.label_description] label sits inline like an [OutlinedTextField]
 * hint when empty, and floats as a chip over the top border once there is text.
 */
@Composable
fun EditEventDescription(
    descriptionText: String,
    descriptionController: OdfMarkdownEditorController,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        if (descriptionText.isEmpty()) {
            Text(
                text = stringResource(R.string.label_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(R.string.label_description),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(y = (-21).dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 4.dp),
            )
        }
        OdfMarkdownEditorField(
            controller = descriptionController,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
