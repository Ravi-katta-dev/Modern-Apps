package com.vayunmathur.appstore.data

/**
 * Rules that apply to every listing, whichever source produced it.
 *
 * This used to be an interface that each source implemented — `fetchAll`, `search`,
 * `isPresent`, `getDetails` — with the ViewModel picking between three implementations at
 * every call site. All reads now go through [CatalogRepository] (offline) or
 * [com.vayunmathur.appstore.data.play.PlayRepository] (live), so the sources are left with
 * one job each: fetch a catalogue and write it to the cache table. What survived is the
 * part that was genuinely shared.
 */
object AppProvider {

    /**
     * The oldest Android an app may be built for and still be listed.
     *
     * Enforced twice: here, against the target SDK the source *claims*, and again at
     * install time against the manifest actually downloaded (see
     * [com.vayunmathur.appstore.data.security.InstallVerifier]), because a source may
     * claim nothing at all.
     */
    const val MIN_TARGET_SDK = 35

    fun filterTargetSdk(apps: List<UnifiedApp>): List<UnifiedApp> =
        apps.filter { app -> app.targetSdk == null || app.targetSdk >= MIN_TARGET_SDK }

    /**
     * The `<uses-feature>` name F-Droid publishes for a leanback entry, and the value
     * Android's PackageManager reports for it on a TV.
     */
    const val FEATURE_LEANBACK = "android.software.leanback"

    /**
     * Features that positively mark an APK as needing phone hardware a TV has not got.
     * Matched as substrings against each declared feature name, lowercased: F-Droid
     * publishes names like `android.hardware.telephony`, and Play's proto (where it
     * exposes one) uses the same vocabulary.
     */
    private val PHONE_ONLY_FEATURES = listOf(
        "android.hardware.telephony",
        "android.hardware.camera.flash",
        "android.hardware.sensor.barometer",
    )

    /**
     * Keep only the listings usable on this form factor.
     *
     * A filter, never a trust decision: it hides listings the catalogue already marks
     * as incompatible, and the install gate re-checks the actual APK regardless (see
     * [com.vayunmathur.appstore.data.security.InstallVerifier.checkLeanback]).
     *
     * On a phone ([isTv] false) everything passes untouched. On a TV, a listing with
     * an explicit negative verdict ([UnifiedApp.tvCompatible] false) is hidden, as is
     * one whose declared features name phone-only hardware. Unknown (null) stays
     * visible: most sources publish no TV signal at all, and hiding every app for
     * lack of metadata would empty the store.
     */
    fun filterTvCompatible(isTv: Boolean, apps: List<UnifiedApp>): List<UnifiedApp> {
        if (!isTv) return apps
        return apps.filter { app ->
            if (app.tvCompatible == false) return@filter false
            !requiresPhoneHardware(app.requiredFeatures)
        }
    }

    /** True when any declared feature names hardware a TV does not have. */
    fun requiresPhoneHardware(requiredFeatures: List<String>): Boolean =
        requiredFeatures.any { feature ->
            val name = feature.lowercase()
            PHONE_ONLY_FEATURES.any { phoneOnly -> phoneOnly in name }
        }
}
