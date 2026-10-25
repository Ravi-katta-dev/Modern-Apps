package com.vayunmathur.appstore.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text as TvText
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.data.security.TrustProfile
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.tv.TvAspect
import com.vayunmathur.library.tv.tvContentStart
import com.vayunmathur.library.ui.IconTv
import com.vayunmathur.library.tv.TvBadge
import com.vayunmathur.library.tv.TvMediaCard
import com.vayunmathur.library.tv.TvRowCard

/**
 * The one app tile for every TV shelf: square icon art centred on a subtle surface,
 * name plus source underneath, an Installed/Update badge and a download progress strip.
 *
 * One tile everywhere so a shelf, the library grid and search all read the same from
 * the couch. The row-card sibling is [TvStoreAppRow].
 */
@Composable
fun TvStoreAppTile(
    app: UnifiedApp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 200.dp,
    badge: String? = null,
    progress: Float? = null,
    onFocus: (() -> Unit)? = null,
) {
    TvMediaCard(
        onClick = onClick,
        modifier = modifier,
        width = width,
        aspectRatio = TvAspect.Square,
        title = app.name,
        subtitle = app.tvSourceLabel(),
        badge = badge,
        progress = progress,
        onFocus = onFocus,
        image = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                TvStoreAppArt(app = app, size = width / 2)
            }
        },
    )
}

/**
 * One app as a horizontal row card: icon, name, source-plus-tier, summary. Fills whatever
 * width the caller gives it — a list column or one grid cell — because [TvRowCard] takes
 * a fixed width and a row that only fills 380dp of an 800dp cell reads as broken.
 */
@Composable
internal fun TvStoreAppRow(
    app: UnifiedApp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    badge: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
) {
    val subtitle = buildString {
        append(app.tvSourceLabel())
        append("  ·  ")
        append(stringResource(TrustProfile.of(app.source).title))
        if (badge != null) {
            append("  ·  ")
            append(badge)
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        TvRowCard(
            title = app.name,
            onClick = onClick,
            width = maxWidth,
            subtitle = subtitle,
            supporting = supporting?.takeIf { it.isNotBlank() },
            leading = { TvStoreAppArt(app = app, size = 56.dp) },
            leadingSize = 64.dp,
            trailing = trailing,
            onFocus = onFocus,
        )
    }
}

/** Icon art with the phone's fallback chain minus PackageManager: URL, else an initial. */
@Composable
internal fun BoxScope.TvStoreAppArt(app: UnifiedApp, size: Dp) {
    val shape = MaterialTheme.shapes.medium
    if (app.iconUrl != null) {
        AsyncImage(
            model = app.iconUrl,
            contentDescription = null,
            modifier = Modifier.size(size).clip(shape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            TvText(
                text = app.name.take(1).uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Hero/backdrop fallback when the listing publishes no image: the accent tinted down into
 * the background with the app's art large on top, so the hero is never a black hole.
 */
@Composable
internal fun BoxScope.TvStoreHeroFallback(app: UnifiedApp) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(
                0f to scheme.primaryContainer,
                0.6f to scheme.surface,
                1f to scheme.background,
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        TvStoreAppArt(app = app, size = 144.dp)
    }
}

/** Where the app came from. Purely factual — the sources are not ranked. */
@Composable
internal fun UnifiedApp.tvSourceLabel(): String = stringResource(
    when (source) {
        AppSource.MODERN_APPS -> R.string.source_chip_modern_apps
        AppSource.FDROID -> R.string.source_chip_fdroid
        AppSource.PLAYSTORE -> R.string.source_chip_play
        AppSource.ACCRESCENT -> R.string.source_chip_accrescent
        AppSource.PROPRIETARY -> R.string.source_chip_proprietary
    },
)

/** Short security-tier chip text: the source's trust profile title. */
@Composable
internal fun UnifiedApp.tvTierLabel(): String =
    stringResource(TrustProfile.of(source).title)

/** The install stage as a one-line label, mirroring the phone's stageLabel. */
@Composable
internal fun InstallStage.tvStageLabel(): String = when (this) {
    InstallStage.Preparing -> stringResource(R.string.stage_preparing)
    is InstallStage.Downloading -> stringResource(R.string.stage_downloading, (fraction * 100).toInt())
    InstallStage.Verifying -> stringResource(R.string.stage_verifying)
    InstallStage.Installing -> stringResource(R.string.stage_installing)
    is InstallStage.Failed -> stringResource(R.string.stage_failed, reason)
}

/** 0..1 download fraction for card progress strips, null for indeterminate stages. */
internal fun InstallStage.downloadFraction(): Float? =
    (this as? InstallStage.Downloading)?.fraction?.coerceIn(0f, 1f)

/** Shelf-tile badge: an available update outranks the installed mark. */
@Composable
internal fun tvTileBadge(isInstalled: Boolean, hasUpdate: Boolean): String? = when {
    hasUpdate -> stringResource(R.string.action_update)
    isInstalled -> stringResource(R.string.installed)
    else -> null
}

/** Small pill for the tier chip row on heroes and detail pages. */
@Composable
internal fun TvTierBadge(text: String, modifier: Modifier = Modifier) {
    TvBadge(text = text, modifier = modifier)
}

/** Single-line notice under a screen header: Play errors, rate limits. Red when an error. */
@Composable
internal fun TvStoreNoticeLine(text: String, isError: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    TvText(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) scheme.error else scheme.onSurfaceVariant,
        modifier = modifier.padding(start = tvContentStart(), end = 48.dp, bottom = 8.dp),
    )
}

/** TV-compat statement: the app passed the catalogue filter; the line says why. */
@Composable
internal fun TvCompatRow(app: UnifiedApp, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTv(tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(8.dp))
        TvText(
            text = stringResource(
                if (app.tvCompatible == true) R.string.tv_compat_declared
                else R.string.tv_compat_unverified,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
