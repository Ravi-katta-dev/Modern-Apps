package com.vayunmathur.appstore.tv

import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.data.security.VerificationResult
import com.vayunmathur.appstore.util.SourceFilter

/** One titled row on the TV browse screen. */
data class TvSection(
    val id: String,
    val title: String,
    val apps: List<UnifiedApp>,
)

/** Everything the TV browse screen draws. */
data class TvHomeUiState(
    val sections: List<TvSection> = emptyList(),
    val isLoading: Boolean = false,
    val statusMessage: String = "",
    val installedPackages: Set<String> = emptySet(),
    /** Installed version code per package, so the hero can offer Update vs Open. */
    val installedVersions: Map<String, Long> = emptyMap(),
    val stages: Map<String, InstallStage> = emptyMap(),
)

/** Everything the TV library (installed apps) screen draws. */
data class TvLibraryUiState(
    val apps: List<UnifiedApp> = emptyList(),
    val filter: SourceFilter = SourceFilter.ALL,
    val counts: Map<SourceFilter, Int> = emptyMap(),
)

internal data class TvLibraryInputs(
    val filter: SourceFilter = SourceFilter.ALL,
    val playPackages: Set<String> = emptySet(),
    val accrescentPackages: Set<String> = emptySet(),
    val enabled: Set<AppSource> = emptySet(),
)

/** Everything the TV detail screen draws. */
data class TvDetailUiState(
    val app: UnifiedApp? = null,
    val installedInfo: InstalledInfo? = null,
    val stage: InstallStage? = null,
    val verification: VerificationResult? = null,
    /** True while richer Play/Accrescent details are being fetched over the network. */
    val isLoadingDetails: Boolean = false,
) {
    val isInstalled: Boolean get() = installedInfo != null

    val hasUpdate: Boolean
        get() = installedInfo != null && (app?.versionCode ?: 0L) > installedInfo.versionCode
}

/** Everything the TV search hub draws. Same shape as the phone's `SearchUiState`. */
data class TvSearchUiState(
    val query: String = "",
    val results: List<UnifiedApp> = emptyList(),
    val filter: SourceFilter = SourceFilter.ALL,
    val isSearching: Boolean = false,
    /** True once a search has run for [query], so "no results" can be told from "not yet". */
    val hasSearched: Boolean = false,
    /**
     * Play's half failed (e.g. rate-limited) while the offline half answered. Blank when
     * Play answered or was never asked, so the list alone is the whole story.
     */
    val playError: String = "",
    val installedPackages: Set<String> = emptySet(),
    val stages: Map<String, InstallStage> = emptyMap(),
)

/** Everything the TV updates hub draws. Same shape as the phone's `UpdatesUiState`. */
data class TvUpdatesUiState(
    val updates: List<UnifiedApp> = emptyList(),
    /** Currently-installed info per package, so the row can show old → new versions. */
    val installedInfos: Map<String, InstalledInfo> = emptyMap(),
    val stages: Map<String, InstallStage> = emptyMap(),
    val isChecking: Boolean = false,
    /** ms since epoch of the last completed check, 0 if never. */
    val lastCheckedAt: Long = 0L,
    val statusMessage: String = "",
)
