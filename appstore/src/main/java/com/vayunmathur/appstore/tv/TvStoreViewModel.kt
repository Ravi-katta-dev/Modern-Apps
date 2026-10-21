package com.vayunmathur.appstore.tv

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.CatalogRepository
import com.vayunmathur.appstore.data.DefaultRepos
import com.vayunmathur.appstore.data.InstalledAppsRepository
import com.vayunmathur.appstore.data.SyncStep
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.accrescent.AccrescentRepository
import com.vayunmathur.appstore.data.installer.InstallCoordinator
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.data.play.PlayRepository
import com.vayunmathur.appstore.data.priority
import com.vayunmathur.appstore.data.security.ApkCertificates
import com.vayunmathur.appstore.R
import com.vayunmathur.library.util.AppMessages
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
    internal val installer = InstallCoordinator(context, db, play, accrescent, viewModelScope) {
        ApkCertificates.selfSigners(context)
    }

    val enabledSourcesFlow = MutableStateFlow(AppSource.TOGGLEABLE.toSet())
    internal val sectionsFlow = MutableStateFlow<List<TvSection>>(emptyList())
    internal val isLoadingFlow = MutableStateFlow(true)
    internal val statusMessageFlow = MutableStateFlow("")
    internal val selectedAppFlow = MutableStateFlow<UnifiedApp?>(null)

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
        installedRepo.apps,
        installer.stages,
        installer.verification,
    ) { app, installed, stages, verification ->
        val pkg = app?.packageName
        TvDetailUiState(
            app = app,
            installedInfo = installed.find { it.packageName == pkg },
            stage = stages[pkg],
            verification = verification[pkg],
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TvDetailUiState())

    init {
        viewModelScope.launch {
            installedRepo.refresh()
            if (AppSource.PLAYSTORE in enabledSourcesFlow.value) play.restore()
            syncIfNeverSynced()
            loadHome()
        }
        viewModelScope.launch {
            combine(catalog.packageIndex, installedRepo.updatable) { _, _ -> Unit }
                .collect { loadHome() }
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
            catalog.recentlyUpdatedBySource(AppSource.MODERN_APPS, 1).isNotEmpty()
        ) {
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
        selectedAppFlow.value = app
    }

    fun clearSelection() {
        selectedAppFlow.value = null
    }

    fun install(app: UnifiedApp) {
        viewModelScope.launch {
            val outcome = installer.install(app)
            val verdict = outcome.verification
            AppMessages.show(
                when (verdict) {
                    is com.vayunmathur.appstore.data.security.VerificationResult.Rejected ->
                        context.getString(
                            com.vayunmathur.appstore.R.string.install_blocked,
                            app.name,
                            verdict.reason,
                        )
                    else ->
                        if (outcome.started) context.getString(
                            com.vayunmathur.appstore.R.string.install_started,
                            app.name,
                        )
                        else context.getString(
                            com.vayunmathur.appstore.R.string.install_failed,
                            app.name,
                        )
                },
            )
            if (outcome.started) installedRepo.refresh()
        }
    }

    fun openApp(packageName: String) {
        val launchIntent = runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName)
        }.getOrNull() ?: return
        runCatching {
            context.startActivity(launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onCleared() {
        accrescent.shutdown()
        super.onCleared()
    }

    private companion object {
        const val ROW_LIMIT = 20
        const val CLUSTER_LIMIT = 4
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
) {
    val isInstalled: Boolean get() = installedInfo != null

    val hasUpdate: Boolean
        get() = installedInfo != null && (app?.versionCode ?: 0L) > installedInfo.versionCode
}

class TvStoreViewModelFactory(
    private val context: android.content.Context,
    private val db: AppDatabase,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        TvStoreViewModel(context.applicationContext as Application, db) as T
}
