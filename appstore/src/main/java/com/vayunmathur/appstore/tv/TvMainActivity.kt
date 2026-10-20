package com.vayunmathur.appstore.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.appstore.data.AppStoreDatabaseRepository
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.library.ui.MaActivity

/**
 * The TV entry: opened from TV launcher rows (LEANBACK_LAUNCHER). The phone MainActivity
 * stays exactly as it is — this activity hosts the D-pad store UI.
 *
 * Browse and detail are two states, not a nav graph: one selected app or none. The
 * ViewModel is the phone repositories reused verbatim, so installs go through the
 * ordinary pipeline including the TV-compat gate.
 */
class TvMainActivity : MaActivity() {
    override val edgeToEdge: Boolean = false

    @Composable
    override fun AppTheme(content: @Composable () -> Unit) {
        // Always dark, not dynamic: a TV has no wallpaper to derive a scheme from.
        // Paints nothing itself; screens set their own background.
        MaterialTheme(colorScheme = darkColorScheme(), content = content)
    }

    @Composable
    override fun Content() {
        val repository = remember { AppStoreDatabaseRepository.get(this) }
        val factory = remember { TvStoreViewModelFactory(this, repository.database) }
        val viewModel: TvStoreViewModel = viewModel(factory = factory)
        TvRoot(viewModel = viewModel)
    }
}

@Composable
private fun TvRoot(viewModel: TvStoreViewModel) {
    var selected by remember { mutableStateOf<UnifiedApp?>(null) }
    if (selected == null) {
        TvStoreHome(
            viewModel = viewModel,
            onAppClick = { app ->
                viewModel.selectApp(app)
                selected = app
            },
        )
    } else {
        TvAppDetail(
            viewModel = viewModel,
            onBack = {
                viewModel.clearSelection()
                selected = null
            },
        )
    }
}
