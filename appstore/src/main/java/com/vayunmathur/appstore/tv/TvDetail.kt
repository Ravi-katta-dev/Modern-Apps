package com.vayunmathur.appstore.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.data.security.VerificationResult
import com.vayunmathur.library.ui.IconTv

/**
 * One app's TV page: what it is, whether it runs on TV, and the install button.
 *
 * The install and update buttons call the same [TvStoreViewModel.install] the browse
 * rows use — which runs the ordinary [InstallCoordinator] pipeline verbatim, including
 * the TV-compat gate. There is no TV-specific install path.
 *
 * TV compatibility is stated explicitly rather than implied by presence: an app that
 * reached this screen passed the catalogue filter, and the line says why (a leanback
 * entry the source published) or that the source said nothing either way.
 */
@Composable
fun TvAppDetail(
    viewModel: TvStoreViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.detail.collectAsState()
    TvDetailScreen(
        state = state,
        onInstall = { app -> viewModel.install(app) },
        onOpen = { pkg -> viewModel.openApp(pkg) },
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Stateless detail, so it can be rendered in a preview.
 *
 * // RAW SCAFFOLD EXCEPTION: androidx.tv has no AppScaffold equivalent; the detail is
 * a single scrollable column with a back button, not a scaffolded phone screen.
 */
@Composable
internal fun TvDetailScreen(
    state: TvDetailUiState,
    onInstall: (UnifiedApp) -> Unit = {},
    onOpen: (String) -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val app = state.app ?: return
    val stage = state.stage
    val busy = stage != null && stage !is InstallStage.Failed
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Button(onClick = onBack) {
            Text(stringResource(R.string.tv_detail_back))
        }
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
        TvCompatLine(app = app)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                busy -> Button(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) {
                    Text(stage.tvStageLabel())
                }
                state.hasUpdate -> {
                    Button(onClick = { onInstall(app) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.action_update))
                    }
                    Button(onClick = { onOpen(app.packageName) }) {
                        Text(stringResource(com.vayunmathur.library.ui.R.string.open))
                    }
                }
                state.isInstalled -> {
                    Button(
                        onClick = { onOpen(app.packageName) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(com.vayunmathur.library.ui.R.string.open))
                    }
                }
                else -> Button(onClick = { onInstall(app) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_install))
                }
            }
        }
        if (stage is InstallStage.Failed) {
            Text(
                text = stringResource(R.string.stage_failed, stage.reason),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.verification.toMessage()?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (app.description.isNotBlank()) {
            Text(
                text = stringResource(R.string.tv_detail_about),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = app.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TvDetailFacts(app = app)
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

@Composable
private fun TvDetailFacts(app: UnifiedApp) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        app.versionName?.let { TvFact(stringResource(R.string.fact_version), it) }
        app.author?.let { TvFact(stringResource(R.string.tv_detail_author), it) }
        app.license?.let { TvFact(stringResource(R.string.detail_license), it) }
        TvFact(stringResource(R.string.detail_package), app.packageName)
    }
}

@Composable
private fun TvFact(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
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
