package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvDimens
import com.vayunmathur.library.tv.TvIconButton
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvShelf
import com.vayunmathur.library.ui.IconClosedCaption
import com.vayunmathur.library.ui.IconList
import com.vayunmathur.library.ui.IconSchedule
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconSkipNext
import com.vayunmathur.library.ui.IconSkipPrevious
import com.vayunmathur.library.ui.animatedDp
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.platform.CastPlayback
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * The player overlay, laid out like YouTube for TV: title and byline top-left; elapsed/total
 * over a thin full-width seek bar; one control row - channel, Details and Subscribe on the left,
 * previous / play-pause / next centred, save and caption/settings icons on the right; related
 * videos peeking along the bottom, sliding up when DOWN moves focus into them.
 *
 * Focus starts on play/pause (or on the seek bar when the overlay was summoned by a scrub).
 * UP reaches the seek bar, where LEFT/RIGHT scrub ([TvDpadInput]). Mounted only while
 * [TvTransportState.visible], so every reveal re-runs the initial focus.
 */
@Composable
internal fun TvTransportOverlay(
    title: String,
    chrome: TvPlayerChrome,
    hasNext: Boolean,
    hasPrevious: Boolean,
    hasCaptions: Boolean,
    captionsOn: Boolean,
    onCaptions: () -> Unit,
    settings: TvPlayerSettings,
    ypvm: YouPipeViewModel,
    modifier: Modifier = Modifier,
) {
    val playFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }
    var relatedFocused by remember { mutableStateOf(false) }
    var relatedHeightPx by remember { mutableStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val hiddenRelated = with(density) { relatedHeightPx.toDp() } - RELATED_PEEK
    val lift = animatedDp(if (relatedFocused || relatedHeightPx == 0) 0.dp else hiddenRelated)

    TvInitialFocus(if (TvDpadInput.scrubPreviewMs != null) seekFocus else playFocus)
    Box(modifier) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.75f),
                    0.3f to Color.Transparent,
                    0.55f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.9f),
                ),
            ),
        )
        Column(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(0.65f)
                .padding(start = TvDimens.SafeHorizontal, top = 40.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = chrome.byline,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .offset(y = lift),
        ) {
            Column(Modifier.padding(horizontal = TvDimens.SafeHorizontal)) {
                TvPlayerSeekBar(ypvm = ypvm, focusRequester = seekFocus)
                TvPlayerControlRow(
                    chrome = chrome,
                    hasNext = hasNext,
                    hasPrevious = hasPrevious,
                    hasCaptions = hasCaptions,
                    captionsOn = captionsOn,
                    onCaptions = onCaptions,
                    onSettings = { showSettings = true },
                    playFocus = playFocus,
                    modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
                )
            }
            if (chrome.related.isNotEmpty()) {
                TvShelf(
                    title = null,
                    modifier = Modifier
                        .onSizeChanged { relatedHeightPx = it.height }
                        .onFocusChanged { relatedFocused = it.hasFocus },
                ) {
                    items(chrome.related.size, key = { chrome.related[it].row.videoID }) { index ->
                        val item = chrome.related[index]
                        TvVideoCard(
                            item = item,
                            onOpen = { chrome.onPlayRelated(item.row.videoID) },
                            modifier = Modifier.width(RELATED_CARD_WIDTH),
                        )
                    }
                }
            }
        }
    }
    if (showSettings) TvPlayerSettingsDialog(settings, onDismiss = { showSettings = false })
}

/** Channel + Details + Subscribe | previous, play/pause, next | save, captions, settings. */
@Composable
private fun TvPlayerControlRow(
    chrome: TvPlayerChrome,
    hasNext: Boolean,
    hasPrevious: Boolean,
    hasCaptions: Boolean,
    captionsOn: Boolean,
    onCaptions: () -> Unit,
    onSettings: () -> Unit,
    playFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvChannelAvatarButton(url = chrome.avatarUrl, onClick = chrome.onChannel)
            TvButton(text = stringResource(R.string.tv_action_details), onClick = chrome.onDetails)
            TvButton(
                text = stringResource(if (chrome.subscribed) R.string.action_unsubscribe else R.string.action_subscribe),
                onClick = chrome.onToggleSubscribe,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (hasPrevious) {
                TvIconButton(onClick = { CastPlayback.onNavigate?.invoke(false) }, icon = { IconSkipPrevious() })
            }
            TvPlayPauseButton(Modifier.focusRequester(playFocus))
            if (hasNext) {
                TvIconButton(onClick = { CastPlayback.onNavigate?.invoke(true) }, icon = { IconSkipNext() })
            }
        }
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvIconButton(onClick = chrome.onWatchLater, icon = { IconSchedule() })
            TvIconButton(onClick = chrome.onSaveToPlaylist, icon = { IconList() })
            if (hasCaptions) {
                TvCaptionsButton(on = captionsOn, onClick = onCaptions)
            }
            TvIconButton(onClick = onSettings, icon = { IconSettings() })
        }
    }
}

private val RELATED_CARD_WIDTH = 300.dp
private val RELATED_PEEK = 72.dp
