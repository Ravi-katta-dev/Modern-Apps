package com.vayunmathur.travel.intents

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import com.vayunmathur.library.util.AppMessages

/**
 * Open a hosted-checkout URL in a Custom Tab (in-app secure payment page).
 * Falls back to a plain view intent when Custom Tabs is unavailable, and
 * reports (rather than throwing) when nothing handles the URL.
 */
fun openCheckout(context: Context, url: String) {
    if (url.isBlank()) {
        AppMessages.show("Payment link is missing. Please try again.")
        return
    }
    runCatching {
        val params = CustomTabColorSchemeParams.Builder().build()
        val intent = CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(params)
            .setShowTitle(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .build()
        intent.launchUrl(context, Uri.parse(url))
    }.onFailure {
        runCatching {
            val view = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                Uri.parse(url)
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(view)
        }.onFailure {
            AppMessages.show("No app can open the payment page.")
        }
    }
}
