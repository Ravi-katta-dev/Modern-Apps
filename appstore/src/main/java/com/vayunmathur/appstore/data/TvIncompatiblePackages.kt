package com.vayunmathur.appstore.data

import android.content.Context
import androidx.core.content.edit

/**
 * Packages the OS refused as incompatible with this device, so the store stops
 * offering them updates.
 *
 * The shape mirrors [RestrictedPackages] deliberately: the OS's
 * `STATUS_FAILURE_INCOMPATIBLE` is a final answer for this device, not a failure the
 * user can act on — and without learning it, the 12-hour update check would re-offer
 * the same update forever, each time with a terminal failure message. One failed
 * install teaches the store; the next check skips the package.
 *
 * Unlike [RestrictedPackages.KNOWN], there is no pre-seeded set: incompatibility is
 * only ever learned the hard way, per device. Entries are device-specific facts, so
 * they are never synced or shared — a package incompatible with this TV may install
 * fine on the user's phone.
 *
 * Two guards keep the learning from going stale (both missing in the first version,
 * which suppressed forever):
 * - TV-only: recording is gated on [isTvLearned]. A phone `FAILURE_INCOMPATIBLE`
 *   (e.g. an ABI mismatch) is not a TV verdict and must not suppress updates.
 * - Expiry + clear-on-update: entries are stored as `package:timestamp` and expire
 *   after 7 days, so a newly shipped TV build is re-offered; a successful install
 *   clears the package immediately via [clearOnUpdate].
 * - Legacy bare-package entries (pre-timestamp) are treated as expired and dropped
 *   on read, retiring the permanent suppressions the old format created.
 */
object TvIncompatiblePackages {

    private const val PREFS = "appstore-tv-incompatible-packages"
    private const val KEY_LEARNED = "incompatible_packages"

    /** 7 days in milliseconds: learned incompatibility expires after this. */
    private const val EXPIRY_MS = 7L * 24 * 60 * 60 * 1000

    /** Packages to leave out of update checks on this device. */
    fun forDevice(context: Context): Set<String> = unexpiredPackages(context)

    /**
     * Remember [packageName] when the OS refused it as incompatible.
     *
     * Records only when [isTv] is true: only a TV learns TV-incompatibility. A
     * phone ABI-mismatch refusal must not suppress future updates. Pass the flag
     * from the receiver, which knows the device.
     *
     * The status code is the signal — unlike the restriction case there is no message
     * text worth matching, since every incompatibility refusal carries the same finality.
     */
    fun recordIfIncompatible(context: Context, packageName: String?, isTv: Boolean) {
        if (!isTv) return
        if (packageName.isNullOrEmpty()) return
        val now = System.currentTimeMillis()
        prefs(context).edit {
            putStringSet(KEY_LEARNED, pruneExpired(learned(context), now) + "$packageName:$now")
        }
    }

    /** Forget [packageName] after it installs successfully — the lesson is stale. */
    fun clearOnUpdate(context: Context, packageName: String?) {
        if (packageName.isNullOrEmpty()) return
        prefs(context).edit {
            putStringSet(
                KEY_LEARNED,
                learned(context).filterNot { it == packageName || it.startsWith("$packageName:") }
                    .toSet(),
            )
        }
    }

    private fun unexpiredPackages(context: Context): Set<String> {
        val now = System.currentTimeMillis()
        val entries = learned(context)
        val live = pruneExpired(entries, now)
        if (live.size != entries.size) {
            prefs(context).edit { putStringSet(KEY_LEARNED, live) }
        }
        return live.mapNotNull { it.substringBefore(":").takeIf { pkg -> pkg.isNotEmpty() } }.toSet()
    }

    private fun pruneExpired(entries: Set<String>, now: Long): Set<String> =
        entries.filter { entry ->
            val timestamp = entry.substringAfterLast(":", "").toLongOrNull()
                // Legacy bare-package entries (no timestamp) are treated as expired.
                ?: return@filter false
            now - timestamp < EXPIRY_MS
        }.toSet()

    private fun learned(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_LEARNED, emptySet()).orEmpty()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
