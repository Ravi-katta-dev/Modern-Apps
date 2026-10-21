package com.vayunmathur.youpipe.tv

import android.view.KeyEvent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.MaterialTheme
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.youpipe.DEFAULT_PAGE_KEY
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.util.YouPipeViewModel
import com.vayunmathur.youpipe.util.YouPipeViewModelFactory

/**
 * The TV entry: opened from TV launcher rows (LEANBACK_LAUNCHER). The phone MainActivity
 * stays exactly as it is — this activity hosts the D-pad browse + player UI.
 *
 * Landscape-locked and non-edge-to-edge per the manifest; the activity owns the [TvDpadInput]
 * key map so scrub/volume/BACK work consistently across browse and player.
 */
class TvMainActivity : MaActivity() {
    override val edgeToEdge: Boolean = false

    private val youPipeViewModel: YouPipeViewModel by viewModels {
        YouPipeViewModelFactory(
            application,
            SubscriptionRepository.get(application),
        )
    }

    private lateinit var initialBackStack: List<TvRoute>

    override fun onCreateBeforeContent() {
        // Same arming the phone does: touch the VM so its hourly subscription task schedules.
        youPipeViewModel
        // Honor the "Open to" default page (same DataStore key + mapping the phone uses),
        // read synchronously here because this runs in onCreate before setContent.
        initialBackStack = tvDefaultBackStack(DataStoreUtils.getInstance(this).getString(DEFAULT_PAGE_KEY))
        TvDpadInput.attach(this)
    }

    override fun onDestroy() {
        TvDpadInput.detach(this)
        super.onDestroy()
    }

    /**
     * The remote, in full — copied from `cast/tv`'s `MirrorActivity.dispatchKeyEvent`.
     *
     * Claims a key only while there is something to act on (player attached, overlay up);
     * everything else falls through to the focus system and nav, which is what the browse
     * screens need. BACK is the exception: it dismisses the overlay before it will pop nav.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (TvDpadInput.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    @Composable
    override fun AppTheme(content: @Composable () -> Unit) {
        // Always dark, not dynamic: a TV has no wallpaper to derive a scheme from.
        // Paints nothing itself; screens set their own background.
        MaterialTheme(colorScheme = darkColorScheme(), content = content)
    }

    @Composable
    override fun Content() {
        TvNavigation(youPipeViewModel, initialBackStack)
    }
}
