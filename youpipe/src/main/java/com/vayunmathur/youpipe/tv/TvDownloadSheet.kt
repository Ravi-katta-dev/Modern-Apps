package com.vayunmathur.youpipe.tv

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.ui.AudioStream
import com.vayunmathur.youpipe.ui.VideoStream
import com.vayunmathur.youpipe.ui.getVideoCodecName

/**
 * D-pad download picker: quality (with size + codec) then audio language, then confirm.
 * Replaces the TV one-tap-best with the phone `ui/VideoDetails.kt` DownloadOptionsDialog
 * choices — quality+size+codec, language, best-opus default — as focusable rows.
 */
@Composable
fun TvDownloadSheet(
    videoStreams: List<VideoStream>,
    audioStreams: List<AudioStream>,
    onConfirm: (videoUrl: String, audioUrl: String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var selectedVideoStream by remember(videoStreams) {
        mutableStateOf(videoStreams.maxByOrNull { it.height } ?: videoStreams.first())
    }
    // Always highest quality opus for selected language in download as well.
    var selectedAudioStream by remember(audioStreams) {
        mutableStateOf(audioStreams.maxByOrNull { it.bitrate })
    }
    val languageEntries = remember(audioStreams) {
        audioStreams.map { it.language to (it.displayName ?: it.language) }
            .distinctBy { it.first }
            .sortedBy { it.first }
    }
    var selectedLanguage by remember(audioStreams) {
        mutableStateOf(selectedAudioStream?.language ?: languageEntries.firstOrNull()?.first.orEmpty())
    }
    TvOptionSheet(title = stringResource(R.string.download_options), onDismiss = onDismiss, modifier = modifier) {
        item(key = "resolution-label") {
            Text(
                text = stringResource(R.string.resolution),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(videoStreams, key = { it.url }) { stream ->
            Surface(
                onClick = { selectedVideoStream = stream },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Row(
                    Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(text = stream.quality, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${getVideoCodecName(stream.codec)} - " +
                                Formatter.formatShortFileSize(context, stream.size),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (stream == selectedVideoStream) IconCheck()
                }
            }
        }
        if (languageEntries.size > 1) {
            item(key = "language-label") {
                Text(
                    text = stringResource(R.string.language),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(languageEntries, key = { it.first }) { (code, display) ->
                Surface(
                    onClick = {
                        selectedLanguage = code
                        selectedAudioStream = audioStreams.filter { it.language == code }.maxByOrNull { it.bitrate }
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = display, style = MaterialTheme.typography.titleMedium)
                        if (code == selectedLanguage) IconCheck()
                    }
                }
            }
        }
        item(key = "confirm") {
            Button(
                onClick = { onConfirm(selectedVideoStream.url, selectedAudioStream?.url) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) {
                Text(stringResource(R.string.download))
            }
        }
    }
}
