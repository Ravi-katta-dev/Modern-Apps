package com.vayunmathur.appstore.tv

import android.app.Application
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.CatalogRepository
import com.vayunmathur.appstore.data.DefaultRepos
import com.vayunmathur.appstore.data.InstalledAppsRepository
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.SettingsRepository
import com.vayunmathur.appstore.data.SyncStep
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.accrescent.AccrescentRepository
import com.vayunmathur.appstore.data.installer.InstallCoordinator
import com.vayunmathur.appstore.data.installer.InstallFailureBatch
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.data.play.PlayAuthState
import com.vayunmathur.appstore.data.play.PlayRepository
import com.vayunmathur.appstore.data.priority
import com.vayunmathur.appstore.data.searchCandidate
import com.vayunmathur.appstore.data.security.ApkCertificates
import com.vayunmathur.appstore.data.security.VerificationResult
import com.vayunmathur.appstore.domain.SearchRanking
import com.vayunmathur.appstore.util.SourceFilter
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Everything the TV browse and detail screens draw.
 *
 * The repositories are the phone's own — [CatalogRepository], [PlayRepository],
 * [InstalledAppsRepository], [InstallCoordinator] — reused verbatim, so the TV UI
 * can never drift into its own install path. What is TV-specific is only the state
 * derivation: the catalogue reads already filter to TV-compatible listings (see
 * `filterTvCompatible`), and this ViewModel holds the selected app plus the install
 * stages the detail screen reports.
 */
class TvStoreViewModel(
    internal val context: Application,
    db: AppDatabase,
) : ViewModel() {

    internal val catalog = CatalogRepository(context, db, viewModelScope)
    internal val play = PlayRepository(context)
    internal val accrescent = AccrescentRepository(context, db)
    internal val installedRepo = InstalledAppsRepository(context)
    internal val settings = SettingsRepository(context, viewModelScope)
    internal val installer = InstallCoordinator(context, db, play, accrescent, viewModelScope) {
        ApkCertificates.selfSigners(context)
    }

    val enabledSourcesFlow = MutableStateFlow(AppSource.DEFAULT_ENABLED)
    internal val sectionsFlow = MutableStateFlow<List<TvSection>>(emptyList())
    internal val isLoadingFlow = MutableStateFlow(true)
    internal val statusMessageFlow = MutableStateFlow("")
    internal val selectedAppFlow = MutableStateFlow<UnifiedApp?>(null)
    internal val isLoadingDetailsFlow = MutableStateFlow(false)
    internal var detailJob: Job? = null

    // --- Search hub state -----------------------------------------------------------
    internal val queryFlow = MutableStateFlow("")
    internal val searchResultsFlow = MutableStateFlow<List<UnifiedApp>>(emptyList())
    internal val searchFilterFlow = MutableStateFlow(SourceFilter.ALL)
    internal val isSearchingFlow = MutableStateFlow(false)
    internal val hasSearchedFlow = MutableStateFlow(false)
    /** Play's half of the last search failed (e.g. 429); shown above the list. */
    internal val searchPlayErrorFlow = MutableStateFlow("")
    internal var searchJob: Job? = null

    // --- Updates hub state ----------------------------------------------------------
    internal val catalogUpdatesFlow = MutableStateFlow<List<UnifiedApp>>(emptyList())
    internal val playUpdatesFlow = MutableStateFlow<List<UnifiedApp>>(emptyList())
    internal val accrescentUpdatesFlow = MutableStateFlow<List<UnifiedApp>>(emptyList())
    internal val isCheckingUpdatesFlow = MutableStateFlow(false)
    internal val lastUpdateCheckFlow = MutableStateFlow(0L)
    internal var updateAllJob: Job? = null

    // --- Accrescent home row --------------------------------------------------------
    internal val accrescentAppsFlow = MutableStateFlow<List<UnifiedApp>>(emptyList())

    val home: StateFlow<TvHomeUiState> = combine(
        sectionsFlow,
        isLoadingFlow,
        statusMessageFlow,
        installedRepo.apps,
        installer.stages,
    ) { sections, loading, message, installed, stages ->
        TvHomeUiState(
            sections = sections,
            isLoading = loading,
            statusMessage = message,
            installedPackages = installed.map { it.packageName }.toSet(),
            stages = stages,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TvHomeUiState())

    val detail: StateFlow<TvDetailUiState> = combine(
        selectedAppFlow,
        isLoadingDetailsFlow,
        installedRepo.apps,
        installer.stages,
        installer.verification,
    ) { app, loading, installed, stages, verification ->
        val pkg = app?.packageName
        TvDetailUiState(
            app = app,
            installedInfo = installed.find { it.packageName == pkg },
            stage = stages[pkg],
            verification = verification[pkg],
            isLoadingDetails = loading,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TvDetailUiState())

    /**
     * Everything the TV search hub draws.
     *
     * Same shape as the phone's `SearchUiState`: the raw two-wave results plus the
     * selected [SourceFilter], so the screen is a pure filter over the ranked merge.
     */
    val search: StateFlow<TvSearchUiState> = combine(
        queryFlow,
        combine(searchResultsFlow, searchFilterFlow) { results, filter ->
            results to filter
        },
        isSearchingFlow,
        hasSearchedFlow,
        combine(searchPlayErrorFlow, installedRepo.apps, installer.stages) { error, installed, stages ->
            Triple(error, installed.map { it.packageName }.toSet(), stages)
        },
    ) { query, (results, filter), searching, searched, (playError, installed, stages) ->
        TvSearchUiState(
            query = query,
            results = results.filter { filter.source == null || it.source == filter.source },
            filter = filter,
            isSearching = searching,
            hasSearched = searched,
            playError = playError,
            installedPackages = installed,
            stages = stages,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TvSearchUiState())

    /**
     * Everything the TV updates hub draws.
     *
     * Same shape as the phone's `UpdatesUiState`: the merged update list plus the
     * installed versions behind it, so each row can show old → new.
     */
    val updatesUi: StateFlow<TvUpdatesUiState> = combine(
        catalogUpdatesFlow,
        playUpdatesFlow,
        accrescentUpdatesFlow,
        combine(installedRepo.apps, installer.stages) { installed, stages ->
            installed to stages
        },
        combine(isCheckingUpdatesFlow, lastUpdateCheckFlow, statusMessageFlow) { checking, checkedAt, message ->
            Triple(checking, checkedAt, message)
        },
    ) { catalogUpdates, playUpdates, accrescentUpdates, (installed, stages), (checking, checkedAt, message) ->
        // The surviving row's source decides which download-and-verify path the update
        // takes, so it has to be the same precedence search and the home rows use.
        // Re-check against what is on the device rather than trusting the lists: the
        // Play half is a snapshot from the last network check.
        val installedVersions = installed.associate { it.packageName to it.versionCode }
        val list = (catalogUpdates + playUpdates + accrescentUpdates)
            .sortedBy { it.source.priority }
            .distinctBy { it.packageName }
            .filter { app ->
                val installedVersion = installedVersions[app.packageName] ?: return@filter false
                app.versionCode > installedVersion
            }
            .sortedBy { it.name.lowercase() }
        TvUpdatesUiState(
            updates = list,
            installedInfos = installed.associateBy { it.packageName },
            stages = stages,
            isChecking = checking,
            lastCheckedAt = checkedAt,
            statusMessage = message,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TvUpdatesUiState())

    init {
        viewModelScope.launch {
            enabledSourcesFlow.value = settings.readEnabledSources()
            // Drop disabled sources' cached rows before anything reads the catalogue:
            // sync only purges when it runs, so a disabled offline source's rows would
            // otherwise survive every restart underneath the toggle (#845).
            catalog.purgeDisabled(enabledSourcesFlow.value)
            installedRepo.refresh()
            if (AppSource.PLAYSTORE in enabledSourcesFlow.value) play.restore()
            syncIfNeverSynced()
            loadHome()
        }
        viewModelScope.launch { loadAccrescent(enabledSourcesFlow.value) }
        viewModelScope.launch {
            combine(catalog.packageIndex, installedRepo.updatable) { _, _ -> Unit }
                .collect {
                    loadHome()
                    catalogUpdatesFlow.value = catalog.updatesFor(installedRepo.updatable.value)
                }
        }
    }

    /**
     * Populate the offline catalogues the first time the TV store is opened.
     *
     * Same contract as the phone's `syncIfNeverSynced`: keyed off the catalogue being
     * empty rather than a first-run flag, so it also recovers a store whose first sync
     * failed or whose data was cleared — and stays quiet on every later launch, when
     * re-fetching full catalogues would be a poor trade for at-most-hours-stale data.
     * Progress reports through the existing status line the browse screen already shows.
     */
    private suspend fun syncIfNeverSynced() {
        val enabled = enabledSourcesFlow.value
        val offlineSources = setOf(
            DefaultRepos.FDROID.source, DefaultRepos.MODERN_APPS.source,
            DefaultRepos.PROPRIETARY.source,
        )
        if (enabled.intersect(offlineSources).isEmpty()) return
        if (catalog.recentlyUpdatedBySource(AppSource.FDROID, 1).isNotEmpty() ||
            catalog.recentlyUpdatedBySource(AppSource.MODERN_APPS, 1).isNotEmpty() ||
            catalog.recentlyUpdatedBySource(AppSource.PROPRIETARY, 1).isNotEmpty()
        ) {
            // The offline gate stays quiet, but Accrescent is network-backed: refresh its
            // allowlist + listings on every cold start that reaches this far, same as the phone.
            loadAccrescent(enabled)
            return
        }
        isLoadingFlow.value = true
        val report = catalog.sync(enabled) { step ->
            statusMessageFlow.value = context.getString(
                when (step) {
                    SyncStep.FDROID -> R.string.sync_step_fdroid
                    SyncStep.MODERN_APPS -> R.string.sync_step_modern_apps
                    SyncStep.PROPRIETARY -> R.string.sync_step_proprietary
                },
            )
        }
        statusMessageFlow.value = ""
        isLoadingFlow.value = false
        AppMessages.show(
            when {
                report.allSkipped -> context.getString(R.string.sync_all_sources_off)
                !report.anyFailed -> context.getString(
                    R.string.sync_done,
                    (report.fdroidCount ?: 0) + (report.modernCount ?: 0) +
                        (report.proprietaryCount ?: 0),
                )
                report.fdroidCount == null && report.modernCount == null &&
                    report.proprietaryCount == null ->
                    context.getString(R.string.sync_failed_all)
                report.fdroidCount == null -> context.getString(R.string.sync_failed_fdroid)
                report.proprietaryCount == null ->
                    context.getString(R.string.sync_failed_proprietary)
                else -> context.getString(R.string.sync_failed_modern_apps)
            },
        )
    }

    /** Fill the browse rows from the TV-filtered catalogue. */
    fun loadHome() {
        viewModelScope.launch {
            isLoadingFlow.value = true
            val enabled = enabledSourcesFlow.value
            val sections = buildList {
                if (AppSource.MODERN_APPS in enabled) {
                    catalog.recentlyUpdatedBySource(AppSource.MODERN_APPS, ROW_LIMIT)
                        .takeIf { it.isNotEmpty() }?.let {
                            add(TvSection("modern", "From Modern Apps", it))
                        }
                }
                if (AppSource.PROPRIETARY in enabled) {
                    catalog.recentlyUpdatedBySource(AppSource.PROPRIETARY, ROW_LIMIT)
                        .takeIf { it.isNotEmpty() }?.let {
                            add(TvSection("proprietary", context.getString(R.string.section_proprietary_recent), it))
                        }
                }
                if (AppSource.PLAYSTORE in enabled) {
                    play.homeClusters()
                        .filter { it.apps.isNotEmpty() }
                        .take(CLUSTER_LIMIT)
                        .forEach { cluster ->
                            add(TvSection("play-${cluster.title}", cluster.title, cluster.apps))
                        }
                }
                if (AppSource.FDROID in enabled) {
                    catalog.recentlyUpdatedBySource(AppSource.FDROID, ROW_LIMIT)
                        .takeIf { it.isNotEmpty() }?.let {
                            add(TvSection("fdroid", "New on F-Droid", it))
                        }
                }
                if (AppSource.ACCRESCENT in enabled) {
                    accrescentAppsFlow.value.takeIf { it.isNotEmpty() }?.let {
                        add(
                            TvSection(
                                "accrescent",
                                context.getString(R.string.source_chip_accrescent),
                                it,
                            )
                        )
                    }
                }
            }
            // Same precedence the phone uses: the surviving row's source decides the
            // download-and-verify path, so cross-source duplicates resolve identically.
            sectionsFlow.value = sections.map { section ->
                section.copy(
                    apps = section.apps.sortedBy { it.source.priority }
                        .distinctBy { it.packageName }
                )
            }
            isLoadingFlow.value = false
        }
    }

    fun selectApp(app: UnifiedApp) {
        detailJob?.cancel()
        selectedAppFlow.value = app
        detailJob = viewModelScope.launch {
            // The catalogue row wins whenever there is one, even if the user tapped a Play
            // tile for the same package. It is the row an install would actually use — it
            // carries the signer and hash an authenticated index published — so showing
            // the Play listing here would describe a download this store is not going to
            // make. Mirrors the phone's selectApp.
            val cached = catalog.byPackage(app.packageName)
            if (cached != null) {
                selectedAppFlow.value = cached
                return@launch
            }
            // Accrescent listings from the home carousel are shells (no version code, no
            // signer yet); fetch the full listing before the page settles.
            if (app.source == AppSource.ACCRESCENT) {
                isLoadingDetailsFlow.value = true
                val details = accrescent.details(app.packageName)
                if (details != null) selectedAppFlow.value = details
                isLoadingDetailsFlow.value = false
                return@launch
            }
            // Play listings from a cluster are shells: no description, no screenshots, no
            // version code. Fill them in before the page settles.
            if (app.source == AppSource.PLAYSTORE && app.screenshots.isEmpty() &&
                AppSource.PLAYSTORE in enabledSourcesFlow.value
            ) {
                isLoadingDetailsFlow.value = true
                val details = play.details(app.packageName)
                if (details != null) selectedAppFlow.value = details
                isLoadingDetailsFlow.value = false
            }
        }
    }

    fun clearSelection() {
        detailJob?.cancel()
        selectedAppFlow.value = null
    }

    // --- Search hub -------------------------------------------------------------------

    /**
     * Two-wave search: the on-disk catalogues answer instantly, then Accrescent's cached
     * listings and Play's reply merge in. Mirrors the phone's `setSearchImpl`: the local
     * half is published first so the user never waits on the network to see something.
     */
    fun setSearch(query: String) {
        queryFlow.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            searchResultsFlow.value = emptyList()
            hasSearchedFlow.value = false
            isSearchingFlow.value = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            isSearchingFlow.value = true
            searchPlayErrorFlow.value = ""
            val enabled = enabledSourcesFlow.value

            val local = catalog.searchLocal(query)
            searchResultsFlow.value = SearchRanking.rank(local, query) { it.searchCandidate() }

            // Accrescent has no search RPC: client-side over the listings already cached
            // from the home carousel, so no extra network round-trip.
            val accrescentResults =
                if (AppSource.ACCRESCENT in enabled) accrescent.search(query) else emptyList()
            // Play may refuse (429): the auth state records the refusal and the local half
            // stays on screen — surfaced above the list, never as a silent gap.
            val remote = if (AppSource.PLAYSTORE in enabled) play.search(query) else emptyList()
            searchPlayErrorFlow.value = (play.authState.value as? PlayAuthState.Error)
                ?.let { context.getString(R.string.play_unavailable, it.message) }
                .orEmpty()
            searchResultsFlow.value = SearchRanking.rank(
                (local + remote + accrescentResults)
                    .sortedBy { it.source.priority }
                    .distinctBy { it.packageName },
                query,
            ) { it.searchCandidate() }
            isSearchingFlow.value = false
            hasSearchedFlow.value = true
        }
    }

    fun setSearchFilter(filter: SourceFilter) {
        searchFilterFlow.value = filter
    }

    // --- Updates hub ------------------------------------------------------------------

    /**
     * Recheck every enabled source for newer builds of installed packages. Mirrors the
     * phone's `checkForUpdatesImpl`: catalogue first, Play only for packages no offline
     * source lists, Accrescent's allowlist refresh then per-package update info.
     */
    fun checkForUpdates() {
        if (isCheckingUpdatesFlow.value) return
        viewModelScope.launch {
            try {
                val enabled = enabledSourcesFlow.value
                isCheckingUpdatesFlow.value = true
                statusMessageFlow.value = context.getString(R.string.updates_checking)

                installedRepo.refresh()
                catalogUpdatesFlow.value = catalog.updatesFor(installedRepo.updatable.value)

                // Direct DB read, not packageIndex.value: the flow's StateFlow cache starts
                // empty until Room emits, so a cold-start check reading .value sees no
                // packages and asks Play about every installed app.
                val index = catalog.indexSnapshot()
                val installed = installedRepo.updatable.value
                if (AppSource.PLAYSTORE in enabled) {
                    val playCandidates = installed
                        .filter { it.packageName !in index }
                        .map { it.packageName }
                    // Fail soft: a refused/failed lookup (null) keeps the previous Play
                    // rows rather than wiping valid updates. Only an answered lookup
                    // (even an empty one) clears.
                    val remote = play.detailsOrNull(playCandidates)
                    if (remote != null) {
                        val byPackage = remote.associateBy { it.packageName }
                        playUpdatesFlow.value = installed.mapNotNull { inst ->
                            byPackage[inst.packageName]?.takeIf { it.versionCode > inst.versionCode }
                        }
                    }
                } else {
                    // A check with Play off must not leave a stale snapshot behind (#845).
                    playUpdatesFlow.value = emptyList()
                }

                if (AppSource.ACCRESCENT in enabled) {
                    accrescent.refreshRepoData()
                    val accrescentIds = accrescent.appIds()
                    accrescentUpdatesFlow.value = installed
                        .filter { it.packageName in accrescentIds }
                        .mapNotNull { inst -> accrescentUpdate(inst.packageName, inst.versionCode) }
                } else {
                    accrescentUpdatesFlow.value = emptyList()
                }

                lastUpdateCheckFlow.value = System.currentTimeMillis()
                statusMessageFlow.value = ""
            } finally {
                // An escaping failure must not wedge the tab mid-check.
                isCheckingUpdatesFlow.value = false
            }
        }
    }

    /**
     * Update everything, sequentially, and report the run once when it is over. Mirrors the
     * phone's `updateAllImpl`: concurrent system dialogs would bury the user in prompts,
     * and failures are collected and summarised instead of one snackbar per app.
     */
    fun updateAll() {
        if (updateAllJob?.isActive == true) return
        updateAllJob = viewModelScope.launch {
            val batch = updatesUi.value.updates
            if (batch.isEmpty()) return@launch
            var started = 0
            val failures = mutableListOf<String>()
            var repeats = 0
            var previousReason: String? = null
            InstallFailureBatch.begin()
            try {
                for ((index, app) in batch.withIndex()) {
                    statusMessageFlow.value = context.getString(
                        R.string.updates_progress, app.name, index + 1, batch.size
                    )
                    val outcome = installer.install(app)
                    if (outcome.started) {
                        started++
                        previousReason = null
                        repeats = 0
                    } else {
                        val failure = when (val verdict = outcome.verification) {
                            is VerificationResult.Rejected -> verdict.reason
                            is VerificationResult.Unverified -> verdict.reason
                            is VerificationResult.Verified ->
                                context.getString(R.string.install_failure_unknown)
                        }
                        failures += failure
                        repeats = if (failure == previousReason) repeats + 1 else 1
                        previousReason = failure
                        if (repeats >= REPEATED_FAILURE_LIMIT) break
                    }
                }
                // PackageInstaller commits asynchronously: wait for the last verdict before
                // summarising, or it lands on its own afterwards.
                delay(INSTALL_SETTLE_MS)
            } finally {
                statusMessageFlow.value = ""
                failures += InstallFailureBatch.end()
            }

            AppMessages.show(
                if (failures.isEmpty()) {
                    context.resources.getQuantityString(
                        R.plurals.updates_batch_installed, started, started
                    )
                } else {
                    context.getString(
                        R.string.updates_batch_partial,
                        started,
                        batch.size,
                        failures.first(),
                    )
                },
                duration = if (failures.isEmpty()) {
                    AppMessages.Duration.Short
                } else {
                    AppMessages.Duration.Long
                },
            )
            installedRepo.refresh()
        }
    }

    /**
     * The available Accrescent update for [packageName] as an installable listing, or null
     * when there is none. Same shape as the phone's helper: the version code is the
     * update's, so the updates filter keeps it only while genuinely newer.
     */
    private suspend fun accrescentUpdate(packageName: String, currentVersionCode: Long): UnifiedApp? {
        val update = runCatching {
            accrescent.updateInfo(packageName, currentVersionCode)
        }.getOrNull() ?: return null
        val details = accrescent.details(packageName) ?: UnifiedApp(
            packageName = packageName,
            source = AppSource.ACCRESCENT,
            name = packageName.substringAfterLast('.'),
        )
        return details.copy(versionCode = update.versionCode, versionName = update.versionName)
    }

    // --- Accrescent home row ------------------------------------------------------------

    /**
     * Refresh Accrescent's signed allowlist and its home listings. Fail soft like the
     * phone's `loadAccrescent`: a network blip leaves the previous rows in place rather
     * than emptying them.
     */
    private suspend fun loadAccrescent(enabled: Set<AppSource> = enabledSourcesFlow.value) {
        if (AppSource.ACCRESCENT !in enabled) return
        accrescent.refreshRepoData()
        val page = accrescent.listApps()
        if (page.apps.isNotEmpty()) accrescentAppsFlow.value = page.apps.take(ACCRow_LIMIT)
    }

    fun install(app: UnifiedApp) {
        viewModelScope.launch {
            val outcome = installer.install(app)
            val verdict = outcome.verification
            AppMessages.show(
                when (verdict) {
                    is com.vayunmathur.appstore.data.security.VerificationResult.Rejected ->
                        context.getString(
                            R.string.install_blocked,
                            app.name,
                            verdict.reason,
                        )
                    else ->
                        if (outcome.started) context.getString(
                            R.string.install_started,
                            app.name,
                        )
                        else context.getString(
                            R.string.install_failed,
                            app.name,
                        )
                },
            )
            if (outcome.started) installedRepo.refresh()
        }
    }

    fun openApp(packageName: String) {
        val pm = context.packageManager
        val leanbackIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            setPackage(packageName)
        }
        val intent = if (leanbackIntent.resolveActivity(pm) != null) leanbackIntent
            else runCatching { pm.getLaunchIntentForPackage(packageName) }.getOrNull() ?: return
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * Uninstall via the system dialog. Mirrors the phone's uninstall: the store never
     * uninstalls silently, and a missing uninstaller surfaces once via AppMessages.
     */
    fun uninstallApp(packageName: String) {
        val started = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$packageName"))
                    .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
        if (!started) AppMessages.show(context.getString(R.string.uninstaller_unavailable))
    }

    /** Clear a failed install's stage so the detail screen offers install again. */
    fun dismissInstallFailure(packageName: String) =
        installer.dismissFailure(packageName)

    override fun onCleared() {
        accrescent.shutdown()
        super.onCleared()
    }

    private companion object {
        const val ROW_LIMIT = 20
        const val CLUSTER_LIMIT = 4
        const val ACCRow_LIMIT = 20
        const val SEARCH_DEBOUNCE_MS = 350L
        const val INSTALL_SETTLE_MS = 1_500L
        /** How many times in a row an update may fail for the same reason before the run ends. */
        const val REPEATED_FAILURE_LIMIT = 3
    }
}

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
    val stages: Map<String, InstallStage> = emptyMap(),
)

/** Everything the TV detail screen draws. */
data class TvDetailUiState(
    val app: UnifiedApp? = null,
    val installedInfo: com.vayunmathur.appstore.data.InstalledInfo? = null,
    val stage: InstallStage? = null,
    val verification: com.vayunmathur.appstore.data.security.VerificationResult? = null,
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

class TvStoreViewModelFactory(
    private val context: android.content.Context,
    private val db: AppDatabase,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        TvStoreViewModel(context.applicationContext as Application, db) as T
}
