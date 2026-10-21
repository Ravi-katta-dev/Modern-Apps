package com.vayunmathur.appstore.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.library.image.compose.AsyncImage

/**
 * The TV browse: one row per source, TV-compatible listings only.
 *
 * The rows come from the same repositories the phone UI reads, which already filter
 * to TV-compatible apps — nothing here filters again. Each tile is a tv-material
 * [Card] so the D-pad focus ring is handled by the design system, not by hand.
 */
@Composable
fun TvStoreHome(
    viewModel: TvStoreViewModel,
    onAppClick: (UnifiedApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.home.collectAsState()
    TvBrowseScreen(state = state, onAppClick = onAppClick, modifier = modifier)
}

/**
 * Stateless browse grid, so it can be rendered in a preview.
 *
 * // RAW SCAFFOLD EXCEPTION: androidx.tv has no AppScaffold equivalent, and the TV
 * ten-foot rule (see cast/tv ReceiverContent) is that a title bar above a browse
 * grid is furniture — the rows are the screen.
 */
@Composable
internal fun TvBrowseScreen(
    state: TvHomeUiState,
    onAppClick: (UnifiedApp) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            text = stringResource(R.string.tv_browse_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (state.isLoading && state.sections.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.sync_in_progress),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        if (state.sections.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.home_empty_message),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(state.sections, key = { it.id }) { section ->
                TvSectionRow(
                    section = section,
                    installedPackages = state.installedPackages,
                    stages = state.stages,
                    onAppClick = onAppClick,
                )
            }
        }
    }
}

@Composable
private fun TvSectionRow(
    section: TvSection,
    installedPackages: Set<String>,
    stages: Map<String, InstallStage>,
    onAppClick: (UnifiedApp) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            items(section.apps, key = { it.packageName }) { app ->
                TvAppTile(
                    app = app,
                    isInstalled = app.packageName in installedPackages,
                    stage = stages[app.packageName],
                    onClick = { onAppClick(app) },
                )
            }
        }
    }
}

@Composable
private fun TvAppTile(
    app: UnifiedApp,
    isInstalled: Boolean,
    stage: InstallStage?,
    onClick: () -> Unit,
) {
    // Ten-foot density: 200–250dp cards with a larger icon and a titleMedium/bodyMedium
    // floor, so tiles read from the couch instead of phone-scale.
    Card(onClick = onClick, modifier = Modifier.width(220.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TvAppIcon(app = app)
            Text(
                text = app.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(48.dp),
            )
            val status = when {
                stage != null -> stage.tvLabel()
                isInstalled -> stringResource(R.string.installed)
                else -> null
            }
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (stage is InstallStage.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TvAppIcon(app: UnifiedApp) {
    val box = Modifier
        .size(96.dp)
        .clip(RoundedCornerShape(20.dp))
    if (app.iconUrl != null) {
        AsyncImage(
            model = app.iconUrl,
            contentDescription = null,
            modifier = box,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            box.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = app.name.take(1).uppercase(),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InstallStage.tvLabel(): String = when (this) {
    InstallStage.Preparing -> stringResource(R.string.stage_preparing)
    is InstallStage.Downloading -> stringResource(R.string.stage_downloading, (fraction * 100).toInt())
    InstallStage.Verifying -> stringResource(R.string.stage_verifying)
    InstallStage.Installing -> stringResource(R.string.stage_installing)
    is InstallStage.Failed -> stringResource(R.string.stage_failed, reason)
}
