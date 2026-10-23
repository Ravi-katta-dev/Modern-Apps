package com.vayunmathur.uniflix

import androidx.compose.runtime.Composable
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.MaActivity

class MainActivity : MaActivity() {
    override fun onCreateBeforeContent() {
        // Catalog + artwork go through the self-hosted proxy; player streams
        // user-supplied addon URLs, so trust stays at STANDARD, never SYSTEM.
        NetworkClient.init(this, TrustBundle.STANDARD)
    }

    @Composable
    override fun Content() {
        Navigation()
    }
}
