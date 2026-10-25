package com.vayunmathur.appstore.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.tv.TvShelf
import com.vayunmathur.library.tv.tvContentStart
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.R as UiR

/**
 * The store front on TV: a featured hero keyed by the focused app, then one shelf per
 * source/section. Rows come from the TV-filtered catalogue the ViewModel already builds;
 * the only TV derivation here is which app the hero shows.
 *
 * The hero shows the first app of the first non-empty shelf and follows focus: moving
 * across any tile swaps the backdrop, name and actions to the focused app. The hero's
 * primary button keeps initial focus so CENTER installs straight away.
 */
@Composable
fun TvStoreHome(
    viewModel: TvStoreViewModel,
    onAppClick: (UnifiedApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.home.collectAsState()
    TvStoreHomeScreen(
        state = state,
        onInstall = viewModel::install,
        onOpen = viewModel::openApp,
        onAppClick = onAppClick,
        modifier = modifier,
    )
}

@Composable
internal fun TvStoreHomeScreen(
    state: TvHomeUiState,
    modifier: Modifier = Modifier,
    onInstall: (UnifiedApp) -> Unit = {},
    onOpen: (String) -> Unit = {},
    onAppClick: (UnifiedApp) -> Unit = {},
) {
    val primaryFocus = remember { FocusRequester() }
    var heroApp by remember { mutableStateOf<UnifiedApp?>(null) }
    val sections = state.sections
    val shownHero = heroApp?.takeIf { hero -> sections.any { s -> s.apps.any { it.packageName == hero.packageName } } }
        ?: sections.firstOrNull()?.apps?.firstOrNull()
    // Claim the hero as soon as sections arrive, so the first frame has a backdrop.
    LaunchedEffect(shownHero?.packageName) {
        if (heroApp == null && shownHero != null) heroApp = shownHero
    }

    when {
        state.isLoading && sections.isEmpty() -> TvLoading(label = stringResource(R.string.sync_in_progress))
        sections.isEmpty() -> TvEmptyState(
            title = stringResource(R.string.home_empty_title),
            body = stringResource(R.string.home_empty_message),
            icon = { IconPackage() },
        )
        else -> Box(modifier.fillMaxSize()) {
            TvBackdrop(key = shownHero?.packageName) { _ ->
                val art = shownHero?.tvScreenshots?.firstOrNull()
                    ?: shownHero?.screenshots?.firstOrNull()
                    ?: shownHero?.featureGraphic
                if (art != null) {
                    AsyncImage(
                        model = art,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else if (shownHero != null) {
                    TvStoreHeroFallback(app = shownHero)
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (shownHero != null) {
                    item("hero") {
                        TvStoreHero(
                            app = shownHero,
                            state = state,
                            focusRequester = primaryFocus,
                            onInstall = onInstall,
                            onOpen = onOpen,
                            onDetails = { onAppClick(shownHero) },
                            modifier = Modifier.padding(top = 56.dp, bottom = 32.dp),
                        )
                    }
                }
                if (state.statusMessage.isNotBlank()) {
                    item("status") {
                        TvStoreStatusLine(
                            message = state.statusMessage,
                            isLoading = state.isLoading,
                        )
                    }
                }
                items(sections, key = { it.id }) { section ->
                    TvStoreSectionShelf(
                        section = section,
                        state = state,
                        onAppClick = onAppClick,
                        onHeroFocus = { heroApp = it },
                    )
                }
                item("bottom") { Spacer(Modifier.height(48.dp)) }
            }
            // Unkeyed: runs once. Keying on the hero would yank focus back to the hero
            // button every time focus moves across tiles and the hero follows.
            TvInitialFocus(primaryFocus)
        }
    }
}

/** Featured hero: backdrop art, name, source eyebrow, tier chip, primary action + Details. */
@Composable
private fun TvStoreHero(
    app: UnifiedApp,
    state: TvHomeUiState,
    focusRequester: FocusRequester,
    onInstall: (UnifiedApp) -> Unit,
    onOpen: (String) -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stage = state.stages[app.packageName]
    val busy = stage != null && stage !is InstallStage.Failed
    val installedVersion = state.installedVersions[app.packageName]
    val isInstalled = installedVersion != null
    val hasUpdate = isInstalled && app.versionCode > (installedVersion ?: 0L)
    val meta = buildList {
        app.versionName?.let { add(it) }
        if (app.sizeBytes > 0) add(formatSize(app.sizeBytes))
        app.categories.firstOrNull()?.let { add(it) }
    }
    Column(modifier.padding(start = tvContentStart())) {
        TvHeroText(
            title = app.name,
            eyebrow = app.tvSourceLabel(),
            meta = meta,
            description = app.summary.ifBlank { app.description }.ifBlank { null },
            chips = { TvTierBadge(text = app.tvTierLabel()) },
            actions = {
                TvStorePrimaryAction(
                    isInstalled = isInstalled,
                    hasUpdate = hasUpdate,
                    stage = stage,
                    busy = busy,
                    focusRequester = focusRequester,
                    onInstall = { onInstall(app) },
                    onOpen = { onOpen(app.packageName) },
                )
                TvButton(
                    text = stringResource(R.string.tv_detail_open),
                    onClick = onDetails,
                )
            },
        )
    }
}

/** One primary (accent) action per screen: Install / Update / Open / the live stage. */
@Composable
internal fun TvStorePrimaryAction(
    isInstalled: Boolean,
    hasUpdate: Boolean,
    stage: InstallStage?,
    busy: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onInstall: () -> Unit = {},
    onOpen: () -> Unit = {},
) {
    val withFocus = if (focusRequester != null) {
        Modifier.focusRequester(focusRequester)
    } else {
        Modifier
    }
    when {
        busy -> TvButton(
            text = stage!!.tvStageLabel(),
            onClick = {},
            enabled = false,
            modifier = modifier.then(withFocus),
        )
        hasUpdate || !isInstalled -> TvButton(
            text = stringResource(
                if (hasUpdate || isInstalled) R.string.action_update else R.string.action_install,
            ),
            onClick = onInstall,
            primary = true,
            icon = { IconDownload() },
            modifier = modifier.then(withFocus),
        )
        else -> TvButton(
            text = stringResource(UiR.string.open),
            onClick = onOpen,
            primary = true,
            modifier = modifier.then(withFocus),
        )
    }
}

/** One titled shelf of app tiles; focusing a tile moves the hero to it. */
@Composable
private fun TvStoreSectionShelf(
    section: TvSection,
    state: TvHomeUiState,
    onAppClick: (UnifiedApp) -> Unit,
    onHeroFocus: (UnifiedApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    TvShelf(title = section.title, modifier = modifier) {
        items(section.apps, key = { it.packageName }) { app ->
            val installed = app.packageName in state.installedPackages
            val installedVersion = state.installedVersions[app.packageName]
            val hasUpdate = installedVersion != null && app.versionCode > installedVersion
            TvStoreAppTile(
                app = app,
                onClick = { onAppClick(app) },
                badge = tvTileBadge(installed, hasUpdate),
                progress = state.stages[app.packageName]?.downloadFraction(),
                onFocus = { onHeroFocus(app) },
            )
        }
    }
}

/** Transient status line under the hero, e.g. what the sync is currently fetching. */
@Composable
private fun TvStoreStatusLine(message: String, isLoading: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier
            .padding(
                start = tvContentStart(),
                end = 48.dp,
                top = 4.dp,
                bottom = 12.dp,
            ),
        horizontalAlignment = Alignment.Start,
    ) {
        TvText(
            text = if (message.isBlank() && isLoading) {
                stringResource(R.string.sync_in_progress)
            } else {
                message
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
