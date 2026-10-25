package com.vayunmathur.youpipe.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.util.YouPipeViewModel
import com.vayunmathur.youpipe.util.applyDownloadedStreams
import com.vayunmathur.youpipe.util.loadVideo
import kotlinx.coroutines.flow.first

/**
 * Fullscreen playback for one video, pushed above the detail screen. BACK returns to the
 * detail (the overlay dismisses first via [TvDpadInput]).
 *
 * Reuses the phone-equivalent load path (`loadVideo` + downloaded streams into
 * `videoState`) and renders the untouched [TvPlayer] core: MediaController/PlaybackService
 * setup, position loop, chapter/quality/language/caption/speed sheets. Only the transport
 * overlay it draws was restyled ([TvTransportOverlay]).
 */
@Composable
fun TvPlayerPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
    videoID: Long,
) {
    val videoState by ypvm.videoState.collectAsState()
    val downloadedFlow = remember(videoID) { ypvm.downloadedById(videoID) }
    val downloadedVideo by downloadedFlow.collectAsState(initial = null)

    LaunchedEffect(videoID) {
        // The detail screen already loaded this video in the common push path; reload only
        // if the state belongs to something else (stale after process death).
        if (videoState.data == null) {
            val downloaded = downloadedFlow.first()
            ypvm.loadVideo(videoID, downloaded)
        }
    }
    LaunchedEffect(downloadedVideo) {
        downloadedVideo?.let { ypvm.applyDownloadedStreams(it) }
    }

    val data = videoState.data
    val videoStreams = videoState.videoStreams
    when {
        data == null || videoStreams.isEmpty() -> TvLoading()
        else -> TvPlayer(
            ypvm = ypvm,
            videoInfo = VideoInfo(
                data.title, videoID, data.duration, data.views,
                data.uploadDate, data.thumbnailURL, data.author,
            ),
            videoStreams = videoStreams,
            audioStreams = videoState.audioStreams,
            subtitles = videoState.subtitles,
            segments = videoState.segments,
        )
    }
}
