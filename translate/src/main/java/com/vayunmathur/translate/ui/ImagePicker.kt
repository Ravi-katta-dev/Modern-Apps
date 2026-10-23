package com.vayunmathur.translate.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * System gallery picker: the Android 13 photo picker where available, the classic
 * document picker elsewhere (no media permission needed either way — the user grants
 * access to exactly the image they pick). Returns a launch callback; [onPicked] fires
 * with the content uri of the chosen image.
 */
@Composable
internal fun rememberImagePickerLauncher(onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) onPicked(uri.toString())
    }
    val legacyPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) onPicked(uri.toString())
    }
    return {
        if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)) {
            photoPicker.launch(
                PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                ),
            )
        } else {
            legacyPicker.launch("image/*")
        }
    }
}
