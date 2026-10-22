package com.vayunmathur.library.util

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService

/**
 * Version + eligibility guards for Android Live Update (promoted ongoing)
 * notifications (API 36 `ProgressStyle` + status chip).
 *
 * Version trap this isolates: [android.app.Notification.ProgressStyle],
 * `setShortCriticalText` and `canPostPromotedNotifications` are API 36.0, but
 * `setRequestPromotedOngoing` only exists on API 36.1 (QPR1). Calling it on
 * base Android 16 (36.0) throws `NoSuchMethodError`, so the style gate
 * ([isStyleSupported]) and the promotion gate ([isPromotedOngoingSupported])
 * must stay separate — a single `SDK_INT >= 36` check is a crash on 36.0.
 *
 * All three Live Update call sites (maps / fooddelivery / updater) go through
 * `NotificationCompat` and only touch the 36.x APIs inside these gates, with
 * the legacy `setProgress()` bar as the `< 36` fallback.
 */
object LiveUpdateSupport {
    /** Android 16 Live Updates arrived in API 36. */
    const val LIVE_UPDATE_SDK = 36

    /** True on API 36+: `ProgressStyle` + `setShortCriticalText` are safe (via compat). */
    @ChecksSdkIntAtLeast(api = LIVE_UPDATE_SDK)
    fun isStyleSupported(): Boolean = Build.VERSION.SDK_INT >= LIVE_UPDATE_SDK

    /**
     * True only where the platform `setRequestPromotedOngoing` method exists:
     * API 36.1+. Checked via the BAKLAVA extension version so 36.0 devices get
     * the Live Update style without promotion instead of a crash.
     */
    @ChecksSdkIntAtLeast(api = LIVE_UPDATE_SDK)
    fun isPromotedOngoingSupported(): Boolean {
        if (Build.VERSION.SDK_INT < LIVE_UPDATE_SDK) return false
        return SdkExtensions.getExtensionVersion(Build.VERSION_CODES.BAKLAVA) >= 1
    }

    /**
     * Whether the system will actually promote an ongoing notification right
     * now: a 36.1+ platform, the app declared `POST_PROMOTED_NOTIFICATIONS`,
     * and the user has not turned promoted notifications off.
     */
    fun canPostPromoted(context: Context): Boolean {
        if (!isPromotedOngoingSupported()) return false
        val manager = context.getSystemService<NotificationManager>() ?: return false
        return canPostPromotedOn36(manager)
    }

    @RequiresApi(LIVE_UPDATE_SDK)
    private fun canPostPromotedOn36(manager: NotificationManager): Boolean =
        manager.canPostPromotedNotifications()
}
