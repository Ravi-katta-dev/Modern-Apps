# AGENTS.md — youpipe/ (':youpipe')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `youpipe/` + allowed shared modules. Do not root-scan.

_Install: ./install youpipe (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :youpipe / dir: youpipe/
- Package roots present: ui, data, platform
- Entry files: MainActivity.kt (no YouPipeApplication.kt — listed file does not exist; Route inline)
- Deps: :library:image, :youpipe:extractor, :sdk:cast, :library:room, :library:work, :library:network
- Metadata: metadata_data/youpipe.md (present)
- Rust: yes / screenshotTest: yes

## Overview
**Purpose:** An ad-free Material 3 YouTube client (NewPipe extractor lineage) with subscriptions, history, downloads, and recommendations. Requires internet.
**Key features:**
- Subscription feed with categories, auto-refresh (`SubscriptionFetchTask`), watch history + resume, playlists, and Watch Later
- Video playback via Media3/ExoPlayer with SABR streaming, SponsorBlock, chapters, background audio, and Picture-in-Picture
- Offline downloads (`DownloadManager`/`DownloadWorker`) with import/export, plus Cast-to-TV via `:sdk:cast`
- Local recommendations engine with tunable interests/keywords and impression tracking
**Architecture / data-flow:** Tabbed-pager `ui/` (`Main` tabs + `VideoPage`/`ChannelPage`/dialog routes) over `util/YouPipeViewModel` → `data/` Room (`SubscriptionDatabase`, repository + per-entity stores) fed by `SubscriptionFetchTask` and the Rust InnerTube extractor (`rustNativeLib youpipe_extractor`) bridged via `nativeext/` + `util/Extractor.kt`; playback runs through `util/PlaybackService` + the `util/sabr/` session stack. `onCreateBeforeContent` touches the VM so its hourly task arms, then resolves the launch backstack (share-target → watch link → persisted default page).
**Entry points + reading order:** `Route` (`Main(tab)` / `VideoPage` / `ChannelPage` / `SubscriptionVideosPage` / `PlaylistDetail` / `Downloads` + dialogs, inline) → `MainActivity.kt` (`resolveInitialBackStack`) → `ui/VideoPage.kt` → `util/YouPipeViewModel.kt` → `util/Extractor.kt` → `data/SubscriptionRepository.kt`.
**Gotchas:**
- Card drift fixed: `YouPipeApplication.kt` never existed — deleted from Entry files; card also gains `data/PlaylistStore`, `RecommendationStore`, `SubscriptionCategoryStore`, `util/YouPipePlaylistOps`, `util/YouPipeRecommendationSettings`, `ui/VideoPlayerNotificationProgressBar`
- `resolveInitialBackStack` runs synchronously in `onCreate` (before `setContent`) — keep it side-effect free
- PiP close and activity destroy deliberately do NOT stop `PlaybackService`; audio keeps playing and switches to audio-only
- SABR attestation goes through the `sabr/` WebView/token stack — changes there break playback long before the player UI does

## Key files
### Root — entry + inline navigation
- com/vayunmathur/youpipe/MainActivity.kt — Route, share/deep-link backstack, default page
### data/ — subscriptions, playlists, recommendations (Room)
- com/vayunmathur/youpipe/data/SubscriptionDatabase.kt
- com/vayunmathur/youpipe/data/SubscriptionRepository.kt
- com/vayunmathur/youpipe/data/Subscription.kt
- com/vayunmathur/youpipe/data/SubscriptionVideo.kt
- com/vayunmathur/youpipe/data/SubscriptionCategory.kt
- com/vayunmathur/youpipe/data/SubscriptionCategoryStore.kt
- com/vayunmathur/youpipe/data/Playlist.kt
- com/vayunmathur/youpipe/data/PlaylistStore.kt
- com/vayunmathur/youpipe/data/HistoryVideo.kt
- com/vayunmathur/youpipe/data/DownloadedVideo.kt
- com/vayunmathur/youpipe/data/CachedRelatedVideo.kt
- com/vayunmathur/youpipe/data/ChannelPreference.kt
- com/vayunmathur/youpipe/data/KeywordPreference.kt
- com/vayunmathur/youpipe/data/RecommendationPreferences.kt
- com/vayunmathur/youpipe/data/RecommendationImpression.kt
- com/vayunmathur/youpipe/data/RecommendationStore.kt
### nativeext/ — Rust extractor bridge
- com/vayunmathur/youpipe/nativeext/YouPipeNative.kt
- com/vayunmathur/youpipe/nativeext/NativeExtractor.kt
### platform/ — cast playback
- com/vayunmathur/youpipe/platform/CastPlayback.kt
### ui/ — tabs, feed, search, library
- com/vayunmathur/youpipe/ui/VideoPage.kt
- com/vayunmathur/youpipe/ui/VideoPlayer.kt
- com/vayunmathur/youpipe/ui/VideoPlayerViewState.kt
- com/vayunmathur/youpipe/ui/VideoPlayerPlaybackState.kt
- com/vayunmathur/youpipe/ui/VideoPlayerPlaybackEffects.kt
- com/vayunmathur/youpipe/ui/VideoPlayerSeekRow.kt
- com/vayunmathur/youpipe/ui/VideoPlayerTopControls.kt
- com/vayunmathur/youpipe/ui/VideoPlayerChapterSheet.kt
- com/vayunmathur/youpipe/ui/VideoPlayerCasting.kt
- com/vayunmathur/youpipe/ui/VideoPlayerNotificationProgressBar.kt
- com/vayunmathur/youpipe/ui/VideoDetails.kt
- com/vayunmathur/youpipe/ui/VideoDetailScreen.kt
- com/vayunmathur/youpipe/ui/VideoFormatting.kt
- com/vayunmathur/youpipe/ui/VideoSections.kt
- com/vayunmathur/youpipe/ui/VideoRow.kt
- com/vayunmathur/youpipe/ui/FeedGrid.kt
- com/vayunmathur/youpipe/ui/SearchPage.kt
- com/vayunmathur/youpipe/ui/ChannelPage.kt
- com/vayunmathur/youpipe/ui/SubscriptionsPage.kt
- com/vayunmathur/youpipe/ui/SubscriptionVideosPage.kt
- com/vayunmathur/youpipe/ui/HistoryPage.kt
- com/vayunmathur/youpipe/ui/SavedPage.kt
- com/vayunmathur/youpipe/ui/PlaylistDetailPage.kt
- com/vayunmathur/youpipe/ui/DownloadedVideosPage.kt
- com/vayunmathur/youpipe/ui/LinkifiedText.kt
- com/vayunmathur/youpipe/ui/dialogs/AddToPlaylist.kt
- com/vayunmathur/youpipe/ui/dialogs/AddToWatchLater.kt
- com/vayunmathur/youpipe/ui/dialogs/CreatePlaylist.kt
- com/vayunmathur/youpipe/ui/dialogs/CreateSubscriptionCategory.kt
### ui/ — settings
- com/vayunmathur/youpipe/ui/SettingsPage.kt
- com/vayunmathur/youpipe/ui/GeneralSettingsPage.kt
- com/vayunmathur/youpipe/ui/SponsorBlockSettingsPage.kt
- com/vayunmathur/youpipe/ui/RecommendationsSettingsPage.kt
- com/vayunmathur/youpipe/ui/ManageInterestsPage.kt
- com/vayunmathur/youpipe/ui/DataSettingsPage.kt
### util/ — ViewModel, extraction, playback, sync (migration pending)
- com/vayunmathur/youpipe/util/YouPipeViewModel.kt
- com/vayunmathur/youpipe/util/YouPipeUiContract.kt
- com/vayunmathur/youpipe/util/YouPipeVideoLoading.kt
- com/vayunmathur/youpipe/util/Extractor.kt
- com/vayunmathur/youpipe/util/MyDownloader.kt
- com/vayunmathur/youpipe/util/PlaybackService.kt
- com/vayunmathur/youpipe/util/DownloadManager.kt
- com/vayunmathur/youpipe/util/DownloadWorker.kt
- com/vayunmathur/youpipe/util/SubscriptionFetchTask.kt
- com/vayunmathur/youpipe/util/Recommender.kt
- com/vayunmathur/youpipe/util/YouPipeRecommendations.kt
- com/vayunmathur/youpipe/util/YouPipeRecommendationSettings.kt
- com/vayunmathur/youpipe/util/YouPipePlaylistOps.kt
- com/vayunmathur/youpipe/util/YouPipeImportExport.kt
### util/sabr/ — SABR session + attestation stack
- com/vayunmathur/youpipe/util/sabr/SabrNgSession.kt
- com/vayunmathur/youpipe/util/sabr/SabrNgSessionStore.kt
- com/vayunmathur/youpipe/util/sabr/SabrNgSourceSpec.kt
- com/vayunmathur/youpipe/util/sabr/SabrNgDashMediaSource.kt
- com/vayunmathur/youpipe/util/sabr/SabrNgDownloadHelper.kt
- com/vayunmathur/youpipe/util/sabr/SabrNgSegmentDataSource.kt
- com/vayunmathur/youpipe/util/sabr/SabrRequestCoordinator.kt
- com/vayunmathur/youpipe/util/sabr/SabrFfmpegMuxer.kt
- com/vayunmathur/youpipe/util/sabr/SabrAttestationRetryHandler.kt
- com/vayunmathur/youpipe/util/sabr/SabrDownloadException.kt
- com/vayunmathur/youpipe/util/sabr/LocalDomPoTokenProvider.kt
- com/vayunmathur/youpipe/util/sabr/LocalDomPoTokenGenerator.kt
- com/vayunmathur/youpipe/util/sabr/SabrLocalDomPoTokenUtil.kt
- com/vayunmathur/youpipe/util/sabr/SharedWebViewRuntime.kt
- com/vayunmathur/youpipe/util/sabr/YoutubePageAttestationBootstrap.kt

## Verify (this module only)
```
./gradlew :youpipe:compileDevKotlin
./gradlew :youpipe:lint
./gradlew :youpipe:checkMetadata
```
