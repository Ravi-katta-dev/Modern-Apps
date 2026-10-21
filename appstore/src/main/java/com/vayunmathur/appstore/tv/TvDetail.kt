package com.vayunmathur.appstore.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.data.security.VerificationResult
import com.vayunmathur.appstore.ui.formatCount
import com.vayunmathur.appstore.ui.formatSize
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.compose.AsyncImageState
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconTv

/**
 * One app's TV page: what it is, what it looks like, and the install button.
 *
 * The install and update buttons call the same [TvStoreViewModel.install] the browse
 * rows use — which runs the ordinary [InstallCoordinator] pipeline verbatim, including
 * the TV-compat gate. There is no TV-specific install path.
 *
 * TV compatibility is stated explicitly rather than implied by presence: an app that
 * reached this screen passed the catalogue filter, and the line says why (a leanback
 * entry the source published) or that the source said nothing either way.
 *
 * Order mirrors the phone's detail page — screenshots, the install button, the
 * description, then the facts, then provenance — at ten-foot density: a
 * focus-scrolling [LazyColumn], 56–64dp buttons, 12–16dp fact gaps, and `bodyLarge`
 * body text. Share/browser/Play links and `market://` deep links are phone-only and
 * stay off this screen.
 */
@Composable
fun TvAppDetail(
    viewModel: TvStoreViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val state by viewModel.detail.collectAsState()
    TvDetailScreen(
        state = state,
        onInstall = { app -> viewModel.install(app) },
        onOpen = { pkg -> viewModel.openApp(pkg) },
        onUninstall = { pkg -> viewModel.uninstallApp(pkg) },
        onDismissFailure = { pkg -> viewModel.dismissInstallFailure(pkg) },
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Stateless detail, so it can be rendered in a preview.
 *
 * // RAW SCAFFOLD EXCEPTION: androidx.tv has no AppScaffold equivalent; the detail is
 * a single focus-scrolling column with a back button, not a scaffolded phone screen.
 */
@Composable
internal fun TvDetailScreen(
    state: TvDetailUiState,
    modifier: Modifier = Modifier,
    onInstall: (UnifiedApp) -> Unit = {},
    onOpen: (String) -> Unit = {},
    onUninstall: (String) -> Unit = {},
    onDismissFailure: (String) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val app = state.app ?: return
    val stage = state.stage
    val busy = stage != null && stage !is InstallStage.Failed
    // @Composable string resolutions hoisted: the LazyColumn item lambdas below are not
    // @Composable scopes for non-local calls like toMessage() — only their trailing
    // content lambdas are. Everything resolved here, plain values used below.
    val verificationMessage = state.verification.toMessage()
    val reproducibleLabel = if (app.reproducible) stringResource(R.string.chip_reproducible) else null
    val adsLabel = if (app.containsAds) stringResource(R.string.chip_contains_ads) else null
    val flags = buildList {
        reproducibleLabel?.let { add(it) }
        adsLabel?.let { add(it) }
        addAll(app.antiFeatures)
    }
    val shots = app.tvScreenshots.ifEmpty { app.screenshots }
    // A LazyColumn, not a scrollable Column: D-pad focus moves item to item, and the
    // list scrolls the focused block into view. A Column would scroll the pixels while
    // focus stays wherever it was.
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item("back") {
            Button(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.tv_detail_back), style = MaterialTheme.typography.bodyLarge)
            }
        }
        item("title") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (app.summary.isNotBlank()) {
                    Text(
                        text = app.summary,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.isLoadingDetails) {
                    Text(
                        text = stringResource(R.string.sync_in_progress),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item("compat") { TvCompatLine(app = app) }
        item("actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    busy -> Button(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp),
                    ) {
                        Text(stage.tvStageLabel(), style = MaterialTheme.typography.bodyLarge)
                    }
                    state.hasUpdate -> {
                        Button(
                            onClick = { onInstall(app) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 56.dp),
                        ) {
                            Text(
                                stringResource(R.string.action_update),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        Button(
                            onClick = { onOpen(app.packageName) },
                            modifier = Modifier.heightIn(min = 56.dp),
                        ) {
                            Text(
                                stringResource(com.vayunmathur.library.ui.R.string.open),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    state.isInstalled -> {
                        Button(
                            onClick = { onOpen(app.packageName) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 56.dp),
                        ) {
                            Text(
                                stringResource(com.vayunmathur.library.ui.R.string.open),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        Button(
                            onClick = { onUninstall(app.packageName) },
                            modifier = Modifier.heightIn(min = 56.dp),
                        ) {
                            // IconXxx from library/ui — never Icon()/painterResource() in app modules.
                            IconDelete()
                        }
                    }
                    else -> Button(
                        onClick = { onInstall(app) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp),
                    ) {
                        Text(
                            stringResource(R.string.action_install),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
        if (stage is InstallStage.Failed) {
            item("failure") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.stage_failed, stage.reason),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    // Retry is dismiss + install: clearing the stage re-arms the install
                    // button, which reports the outcome once via AppMessages.
                    Button(
                        onClick = {
                            onDismissFailure(app.packageName)
                            onInstall(app)
                        },
                        modifier = Modifier.heightIn(min = 56.dp),
                    ) {
                        Text(
                            stringResource(R.string.action_check_again),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
        if (verificationMessage != null) {
            item("verification") {
                Text(
                    text = verificationMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // TV screenshots where the source publishes a separate set — phone-aspect shots
        // jump between ratios when mixed. Falls back to the phone set, mirroring the
        // phone's strip order (screenshots, not hero-first: the hero is phone-framed).
        // (Resolved at the top of this screen — see shots.)
        if (shots.isNotEmpty()) {
            item("screenshots") {
                TvScreenshotStrip(urls = shots)
            }
        }
        item("facts") { TvDetailFactRow(app = app) }
        if (app.description.isNotBlank()) {
            item("about") {
                TvDetailAbout(app = app)
            }
        }
        if (app.whatsNew?.isNotBlank() == true) {
            item("whatsnew") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.detail_whats_new),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    app.updatedOn?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = app.whatsNew,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Anti-features and reproducibility, simplified: the phone's chip row verbatim
        // would be a second focus axis; a plain list at bodyLarge reads at ten feet.
        // (Labels resolved at the top of this screen — see flags.)
        if (flags.isNotEmpty()) {
            item("flags") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    flags.forEach { flag ->
                        Text(
                            text = flag,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (app.permissions.isNotEmpty()) {
            item("permissions") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.detail_permissions),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = app.permissions.joinToString("\n") { it.substringAfterLast('.') },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item("details") { TvDetailFacts(app = app) }
    }
}

/**
 * The TV-compat statement: why this app is offered on TV.
 *
 * Three cases, all factual: the source published a leanback signal; the source
 * published phone-only hardware but the APK still installed (cannot happen through
 * the filter — this line then explains the discrepancy); or the source said nothing
 * and the install gate verified the bytes instead.
 */
@Composable
private fun TvCompatLine(app: UnifiedApp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconTv(tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(
                if (app.tvCompatible == true) R.string.tv_compat_declared
                else R.string.tv_compat_unverified
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** TV screenshots at a fixed height so mixed aspect ratios still scroll as one strip. */
@Composable
private fun TvScreenshotStrip(urls: List<String>) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(urls, key = { it }) { url ->
            // The width has to be stated: AsyncImage draws its bitmap with matchParentSize,
            // which contributes nothing to the measure pass, so an image given only a
            // height resolves to zero wide. Start at 16:9 and switch to the real ratio
            // once the bitmap has been decoded.
            var ratio by remember(url) { mutableFloatStateOf(LANDSCAPE_SCREENSHOT_RATIO) }
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier
                    .height(220.dp)
                    .aspectRatio(ratio)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Fit,
                onState = { state ->
                    val size = (state as? AsyncImageState.Success)?.painter?.intrinsicSize
                    if (size != null && size.width > 0f && size.height > 0f) {
                        ratio = size.width / size.height
                    }
                },
            )
        }
    }
}

/** 16:9, the shape a TV screenshot is until its own is known. */
private const val LANDSCAPE_SCREENSHOT_RATIO = 16f / 9f

/** Rating, installs, size and age rating, in whatever subset the source published. */
@Composable
private fun TvDetailFactRow(app: UnifiedApp) {
    val cells = buildList<Pair<String, String>> {
        app.rating?.let {
            add(
                stringResource(R.string.fact_rating) to
                    String.format(java.util.Locale.US, "%.1f★", it)
            )
        }
        if (app.installs > 0) add(stringResource(R.string.fact_installs) to formatCount(app.installs))
        if (app.sizeBytes > 0) add(stringResource(R.string.fact_size) to formatSize(app.sizeBytes))
        app.contentRating?.let { add(stringResource(R.string.fact_rated) to it) }
        app.versionName?.let { add(stringResource(R.string.fact_version) to it) }
    }
    if (cells.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        cells.take(4).forEachIndexed { index, (label, value) ->
            if (index > 0) {
                Spacer(
                    Modifier
                        .width(1.dp)
                        .height(32.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)),
                )
            }
            TvStatCell(label = label, value = value, modifier = Modifier.weight(1f))
        }
    }
}

/** A labelled value in the facts row, at a ten-foot floor. */
@Composable
private fun TvStatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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

@Composable
private fun TvDetailAbout(app: UnifiedApp) {
    var expanded by remember(app.packageName) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.tv_detail_about),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = app.description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 5,
            overflow = TextOverflow.Ellipsis,
        )
        Surface(onClick = { expanded = !expanded }) {
            Text(
                text = stringResource(
                    if (expanded) R.string.action_show_less else R.string.action_read_more
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun TvDetailFacts(app: UnifiedApp) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        app.versionName?.let { TvFact(stringResource(R.string.fact_version), it) }
        app.author?.let { TvFact(stringResource(R.string.tv_detail_author), it) }
        app.license?.let { TvFact(stringResource(R.string.detail_license), it) }
        app.targetSdk?.let { TvFact(stringResource(R.string.detail_target_sdk), it.toString()) }
        if (app.ratingCount > 0) {
            TvFact(stringResource(R.string.detail_rating_count), formatCount(app.ratingCount))
        }
        TvFact(stringResource(R.string.detail_package), app.packageName)
    }
}

@Composable
private fun TvFact(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
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

@Composable
private fun VerificationResult?.toMessage(): String? = when (this) {
    is VerificationResult.Rejected ->
        stringResource(R.string.detail_install_blocked, reason)
    is VerificationResult.Verified ->
        stringResource(R.string.detail_checked_on_install, detail)
    is VerificationResult.Unverified ->
        stringResource(R.string.detail_installed_unverified, reason)
    null -> null
}

@Composable
private fun InstallStage.tvStageLabel(): String = when (this) {
    InstallStage.Preparing -> stringResource(R.string.stage_preparing)
    is InstallStage.Downloading -> stringResource(R.string.stage_downloading, (fraction * 100).toInt())
    InstallStage.Verifying -> stringResource(R.string.stage_verifying)
    InstallStage.Installing -> stringResource(R.string.stage_installing)
    is InstallStage.Failed -> stringResource(R.string.stage_failed, reason)
}
