package com.vayunmathur.globalesim

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.globalesim.platform.EsimViewModel
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.MaActivity

class MainActivity : MaActivity() {
    private val viewModel: EsimViewModel by viewModels()

    override fun onCreateBeforeContent() {
        // First-party backend only (api.maproj.org): Cloudflare Universal SSL
        // roots in FIRST_PARTY. The reseller vendor is never contacted here —
        // all vendor calls stay server-side behind /esim/v1/*.
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
    }

    @Composable
    override fun Content() {
        Navigation(viewModel)
    }
}
