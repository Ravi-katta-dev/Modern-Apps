package com.vayunmathur.appstore.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text as TvText
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.ui.formatSize
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.tv.TvBackdrop
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvHeroText
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvProgressStrip
import com.vayunmathur.library.tv.TvShelf
import com.vayunmathur.library.tv.tvContentStart
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.R as UiR

/**
 * One app's immersive TV page: backdrop, hero with install actions and the security
 * panel, screenshots, description, facts. Same install pipeline as everywhere else —
 * [TvStoreViewModel.install] runs the ordinary coordinator verbatim, including the
 * TV-compat gate.
 *
 * Order mirrors the phone's detail page — hero, screenshots, description, facts — with
 * the trust card promoted next to the actions: signature verification is the product,
 * and on TV it sits beside the Install button rather than below the fold.
 */
@Composable
fun TvStoreDetail(
    viewModel: TvStoreViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.detail.collectAsState()
    BackHandler(onBack = onBack)
    TvStoreDetailScreen(
        state = state,
        onInstall = viewModel::install,
        onOpen = viewModel::openApp,
        onUninstall = viewModel::uninstallApp,
        onDismissFailure = viewModel::dismissInstallFailure,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun TvStoreDetailScreen(
    state: TvDetailUiState,
    modifier: Modifier = Modifier,
    onInstall: (UnifiedApp) -> Unit = {},
    onOpen: (String) -> Unit = {},
    onUninstall: (String) -> Unit = {},
    onDismissFailure: (String) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val app = state.app
    val primaryFocus = remember(app?.packageName) { FocusRequester() }
    if (app == null) {
        TvEmptyState(
            title = stringResource(R.string.home_empty_title),
            icon = { IconPackage() },
            action = { TvButton(text = stringResource(R.string.tv_detail_back), onClick = onBack) },
            modifier = modifier,
        )
        return
    }
    val stage = state.stage
    val busy = stage != null && stage !is InstallStage.Failed
    val shots = app.tvScreenshots.ifEmpty { app.screenshots }
    Box(modifier.fillMaxSize()) {
        TvBackdrop(key = app.packageName) {
            val art = app.featureGraphic ?: shots.firstOrNull()
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                TvStoreHeroFallback(app = app)
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("back") {
                TvButton(
                    text = stringResource(R.string.tv_detail_back),
                    onClick = onBack,
                    icon = { IconBack() },
                    modifier = Modifier.padding(
                        start = tvContentStart(),
                        top = 40.dp,
                        bottom = 8.dp,
                    ),
                )
            }
            item("hero") {
                TvStoreDetailHero(
                    app = app,
                    state = state,
                    stage = stage,
                    busy = busy,
                    focusRequester = primaryFocus,
                    onInstall = { onInstall(app) },
                    onOpen = { onOpen(app.packageName) },
                    onUninstall = { onUninstall(app.packageName) },
                    onDismissFailure = { onDismissFailure(app.packageName) },
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
            if (busy && stage is InstallStage.Downloading) {
                item("progress") {
                    TvProgressStrip(
                        progress = stage.fraction.coerceIn(0f, 1f),
                        modifier = Modifier.padding(
                            start = tvContentStart(),
                            end = 48.dp,
                            bottom = 8.dp,
                        ),
                    )
                }
            }
            if (stage is InstallStage.Failed) {
                item("failure") {
                    TvStoreDetailFailure(
                        reason = stage.reason,
                        onRetry = {
                            onDismissFailure(app.packageName)
                            onInstall(app)
                        },
                    )
                }
            }
            if (shots.isNotEmpty()) {
                item("screenshots") {
                    TvStoreScreenshotShelf(urls = shots)
                }
            }
            item("body") {
                TvStoreDetailBody(app = app)
            }
            item("bottom") { Spacer(Modifier.height(48.dp)) }
        }
        TvInitialFocus(primaryFocus, app.packageName)
    }
}

/** Hero block: name, developer, source, version/size meta, tier chip, actions + trust card. */
@Composable
private fun TvStoreDetailHero(
    app: UnifiedApp,
    state: TvDetailUiState,
    stage: InstallStage?,
    busy: Boolean,
    focusRequester: FocusRequester,
    onInstall: (UnifiedApp) -> Unit,
    onOpen: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onDismissFailure: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val meta = buildList {
        app.versionName?.let { add(it) }
        if (app.sizeBytes > 0) add(formatSize(app.sizeBytes))
        app.categories.firstOrNull()?.let { add(it) }
    }
    Row(
        modifier.padding(start = tvContentStart(), end = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Column(Modifier.weight(1f)) {
            TvHeroText(
                title = app.name,
                eyebrow = app.tvSourceLabel(),
                meta = meta.ifEmpty { listOf(app.author.orEmpty()) },
                description = when {
                    state.isLoadingDetails -> stringResource(R.string.sync_in_progress)
                    app.summary.isNotBlank() -> app.summary
                    else -> null
                },
                chips = {
                    TvTierBadge(text = app.tvTierLabel())
                    if (app.reproducible) TvTierBadge(text = stringResource(R.string.chip_reproducible))
                    if (app.containsAds) TvTierBadge(text = stringResource(R.string.chip_contains_ads))
                },
                actions = {
                    TvStorePrimaryAction(
                        isInstalled = state.isInstalled,
                        hasUpdate = state.hasUpdate,
                        stage = stage,
                        busy = busy,
                        focusRequester = focusRequester,
                        onInstall = { onInstall(app) },
                        onOpen = { onOpen(app.packageName) },
                    )
                    TvStoreSecondaryActions(
                        isInstalled = state.isInstalled,
                        hasUpdate = state.hasUpdate,
                        busy = busy,
                        onOpen = { onOpen(app.packageName) },
                        onUninstall = { onUninstall(app.packageName) },
                    )
                    if (stage is InstallStage.Failed) {
                        TvButton(
                            text = stringResource(R.string.action_dismiss),
                            onClick = { onDismissFailure(app.packageName) },
                        )
                    }
                },
            )
            if (state.isInstalled && state.installedInfo?.versionName != null && !state.hasUpdate) {
                TvText(
                    text = stringResource(R.string.installed_2, state.installedInfo.versionName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            TvStoreVerificationLine(state = state, modifier = Modifier.padding(top = 8.dp))
        }
        TvStoreTrustPanel(app = app, modifier = Modifier.weight(0.85f))
    }
}

/** Open/Uninstall next to the primary action; the primary already covers Open when idle. */
@Composable
private fun TvStoreSecondaryActions(
    isInstalled: Boolean,
    hasUpdate: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onUninstall: () -> Unit,
) {
    if (busy || !isInstalled) return
    if (hasUpdate) {
        // Primary is Update; Open sits beside it like the phone's Update + Open pair.
        TvButton(
            text = stringResource(UiR.string.open),
            onClick = onOpen,
        )
    } else {
        TvButton(
            text = stringResource(R.string.uninstall),
            onClick = onUninstall,
            icon = { IconDelete() },
        )
    }
}
