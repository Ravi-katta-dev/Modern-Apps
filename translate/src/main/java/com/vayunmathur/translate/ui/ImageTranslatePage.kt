package com.vayunmathur.translate.ui

import androidx.compose.runtime.Composable
import com.vayunmathur.translate.platform.TranslateViewModel

/**
 * Binds [TranslateViewModel] to the stateless [ImageTranslateScreen].
 *
 * The gallery pickers both live here (the page owns the activity-result launchers):
 * the entry picker that brought this image in, and the retry picker behind the
 * screen's failure state. Retrying swaps the route in place so the stack stays at
 * Text → Image no matter how many times the user re-picks.
 */
@Composable
fun ImageTranslatePage(
    viewModel: TranslateViewModel,
    imageUri: String,
    onReplaceImage: (String) -> Unit,
    onBack: () -> Unit,
    onOpenLanguagePicker: (Boolean) -> Unit,
) {
    val pickImage = rememberImagePickerLauncher(onPicked = onReplaceImage)
    ImageTranslateScreen(
        viewModel = viewModel,
        imageUri = imageUri,
        onBack = onBack,
        onPickAnother = pickImage,
        onOpenLanguagePicker = onOpenLanguagePicker,
    )
}
