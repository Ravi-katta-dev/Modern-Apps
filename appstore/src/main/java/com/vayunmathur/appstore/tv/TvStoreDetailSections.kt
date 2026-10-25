package com.vayunmathur.appstore.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.security.TrustProfile
import com.vayunmathur.appstore.data.security.VerificationResult
import com.vayunmathur.appstore.ui.formatCount
import com.vayunmathur.appstore.ui.formatSize
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.compose.AsyncImageState
import com.vayunmathur.library.tv.TvAspect
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvMediaCard
import com.vayunmathur.library.tv.TvShelf
import com.vayunmathur.library.tv.tvContentStart

/**
 * Detail-page sections: the security panel, verification line, screenshots, description
 * and facts. Split from [TvStoreDetail] to keep both files under the 350-line limit.
 *
 * The security content comes straight from the phone's model — [TrustProfile] per-source
 * practices and checks plus the last [VerificationResult] — because signature
 * verification is the product and it stays prominent on TV.
 */
@Composable
internal fun TvStoreTrustPanel(app: UnifiedApp, modifier: Modifier = Modifier) {
    val profile = TrustProfile.of(app.source)
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(scheme.surface.copy(alpha = 0.85f))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.trust_page_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
        )
        Text(
            text = stringResource(profile.title),
            style = MaterialTheme.typography.titleSmall,
            color = scheme.primary,
        )
        Text(
            text = stringResource(profile.summary),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.trust_heading_we_check),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.primary,
        )
        profile.ourChecks.forEach { check ->
            Text(
                text = stringResource(R.string.trust_bullet, stringResource(check)),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface.copy(alpha = 0.9f),
            )
        }
        Text(
            text = stringResource(R.string.trust_heading_weaker),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.primary,
        )
        Text(
            text = stringResource(profile.limits),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}

/** What the last install proved, in the verifier's own words — or the not-yet-checked line. */
@Composable
internal fun TvStoreVerificationLine(state: TvDetailUiState, modifier: Modifier = Modifier) {
    val message = state.verification.toTvMessage()
    if (message == null) {
        // No install attempt yet: the neutral card line, mirroring the phone's trust card.
        Text(
            text = stringResource(R.string.detail_tap_for_trust),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = if (state.verification is VerificationResult.Rejected ||
            state.verification is VerificationResult.Unverified
        ) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier,
    )
}

@Composable
private fun VerificationResult?.toTvMessage(): String? = when (this) {
    is VerificationResult.Rejected ->
        stringResource(R.string.detail_install_blocked, reason)
    is VerificationResult.Verified ->
        stringResource(R.string.detail_checked_on_install, detail)
    is VerificationResult.Unverified ->
        stringResource(R.string.detail_installed_unverified, reason)
    null -> null
}

/** Failed install block with a retry that re-arms the install button. */
@Composable
internal fun TvStoreDetailFailure(reason: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(start = tvContentStart(), end = 48.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.stage_failed, reason),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        // Retry is dismiss + install: clearing the stage re-arms the install button,
        // which reports the outcome once via AppMessages.
        TvButton(text = stringResource(R.string.action_check_again), onClick = onRetry)
    }
}

/** TV screenshots where the source publishes a set; landscape cards in a shelf. */
@Composable
internal fun TvStoreScreenshotShelf(urls: List<String>, modifier: Modifier = Modifier) {
    TvShelf(title = stringResource(R.string.tv_detail_screenshots), modifier = modifier) {
        items(urls, key = { it }) { url ->
            // The width has to be stated: AsyncImage draws its bitmap with matchParentSize,
            // which contributes nothing to the measure pass, so an image given only a
            // height resolves to zero wide. Start at 16:9 and switch to the real ratio
            // once the bitmap has been decoded.
            var ratio by remember(url) { mutableFloatStateOf(TvAspect.Landscape) }
            TvMediaCard(
                onClick = {},
                width = 320.dp,
                aspectRatio = ratio,
                image = {
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
                        contentScale = ContentScale.Fit,
                        onState = { state ->
                            val size = (state as? AsyncImageState.Success)?.painter?.intrinsicSize
                            if (size != null && size.width > 0f && size.height > 0f) {
                                ratio = size.width / size.height
                            }
                        },
                    )
                },
            )
        }
    }
}

/** Description, what's new, compat line, facts, permissions. */
@Composable
internal fun TvStoreDetailBody(app: UnifiedApp, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = tvContentStart(), end = 48.dp, top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // TV-compat statement: the app passed the catalogue filter; the line says why.
        TvCompatRow(app = app)
        if (app.description.isNotBlank()) {
            TvStoreDetailBlock(title = stringResource(R.string.tv_detail_about)) {
                var expanded by remember(app.packageName) { mutableStateOf(false) }
                Text(
                    text = app.description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                    maxLines = if (expanded) Int.MAX_VALUE else 5,
                    overflow = TextOverflow.Ellipsis,
                )
                TvButton(
                    text = stringResource(
                        if (expanded) R.string.action_show_less else R.string.action_read_more,
                    ),
                    onClick = { expanded = !expanded },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        if (app.whatsNew?.isNotBlank() == true) {
            TvStoreDetailBlock(title = stringResource(R.string.detail_whats_new)) {
                app.updatedOn?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = app.whatsNew,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                )
            }
        }
        TvStoreDetailFacts(app = app)
        if (app.antiFeatures.isNotEmpty() || app.permissions.isNotEmpty()) {
            TvStoreDetailBlock(title = stringResource(R.string.detail_permissions)) {
                // Anti-features are the honest part of F-Droid listings; flagged, not hidden.
                val lines = app.antiFeatures +
                    app.permissions.map { it.substringAfterLast('.') }
                Text(
                    text = lines.joinToString("\n"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                )
            }
        }
        TvStoreVersionRow(app = app)
    }
}

@Composable
private fun TvStoreDetailBlock(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        content()
    }
}

/** Rating, installs, size and content rating, in whatever subset the source published. */
@Composable
private fun TvStoreDetailFacts(app: UnifiedApp) {
    val cells = buildList<Pair<String, String>> {
        app.rating?.let {
            add(stringResource(R.string.fact_rating) to String.format(java.util.Locale.US, "%.1f★", it))
        }
        if (app.installs > 0) add(stringResource(R.string.fact_installs) to formatCount(app.installs))
        if (app.sizeBytes > 0) add(stringResource(R.string.fact_size) to formatSize(app.sizeBytes))
        app.contentRating?.let { add(stringResource(R.string.fact_rated) to it) }
        app.versionName?.let { add(stringResource(R.string.fact_version) to it) }
    }
    if (cells.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(0.6f),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        cells.take(4).forEachIndexed { index, (label, value) ->
            if (index > 0) {
                Box(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .width(1.dp)
                        .height(32.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Package, developer, license, target SDK — deliberately last, like the phone. */
@Composable
private fun TvStoreVersionRow(app: UnifiedApp) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        app.author?.let { TvStoreFact(stringResource(R.string.tv_detail_author), it) }
        app.license?.let { TvStoreFact(stringResource(R.string.detail_license), it) }
        app.targetSdk?.let { TvStoreFact(stringResource(R.string.detail_target_sdk), it.toString()) }
        if (app.ratingCount > 0) {
            TvStoreFact(stringResource(R.string.detail_rating_count), formatCount(app.ratingCount))
        }
        TvStoreFact(stringResource(R.string.detail_package), app.packageName)
    }
}

@Composable
private fun TvStoreFact(label: String, value: String) {
    Column(Modifier.fillMaxWidth(0.6f)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
