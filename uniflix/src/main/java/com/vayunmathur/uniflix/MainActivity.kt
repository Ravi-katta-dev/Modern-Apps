package com.vayunmathur.uniflix

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.uniflix.platform.SettingsViewModel

class MainActivity : MaActivity() {
    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun onCreateBeforeContent() {
        // Public catalog + artwork hosts only. The player itself streams
        // user-supplied URLs; trust stays at STANDARD, never SYSTEM.
        NetworkClient.init(this, TrustBundle.STANDARD)
    }

    @Composable
    override fun Content() {
        Navigation(settingsViewModel)
    }
}
