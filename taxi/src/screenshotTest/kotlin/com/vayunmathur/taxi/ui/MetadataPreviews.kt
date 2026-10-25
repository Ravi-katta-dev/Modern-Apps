package com.vayunmathur.taxi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.map.CameraPosition
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconMap
import com.vayunmathur.library.ui.IconNavigationArrow
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.taxi.Route
import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.QuoteResult
import com.vayunmathur.taxi.data.RideQuote

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Desktop-wide (1280dp >= 840dp), so isExpandedWidth() side-by-side branches render. */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=240"

/**
 * Store listing images for `:taxi`, rendered from Compose previews instead of from
 * an instrumented test on a device.
 *
 * `./gradlew :taxi:metadata` renders these and copies the PNGs into
 * per-bucket `metadata_data/photos/taxi/<bucket>/` dirs (`phone/` for the
 * handset preview, `large-tablet/` for the expanded-layout preview — Play has no
 * desktop bucket and large-tablet is the closest Triple-T type), where the release
 * build picks them up.
 *
 * Ride tab only: the CurrentRide and Accounts tabs have no slices, so they render
 * as empty boxes here (never visible — the selected tab is always Ride).
 *
 * Two things to keep in mind when editing:
 *
 *  - Order matters, and it comes from the function names. The generated PNG filenames
 *    embed the function name, so `Preview1Ride`/`Preview2RideExpanded` sort into listing
 *    order no matter how the plugin formats the rest of the filename. Renumber the
 *    functions if you reorder the listing. Order is per bucket.
 *  - Each preview needs a `group` (the form-factor bucket: `phone`, `tablet`,
 *    `large-tablet`, `tv`, `wear`) as well as @PreviewTest.
 *  - Everything must be a literal. These render with no ViewModel, no database and no
 *    device, so the state below is the whole input — which is also what makes the output
 *    reproducible from a clean checkout.
 *  - Each preview needs @PreviewTest as well as @Preview. @Preview alone renders in
 *    Studio but is not collected as a screenshot test, and the build fails with the
 *    unhelpful "did not discover any tests".
 *  - The previews must be members of a class, not top-level functions. Android Studio
 *    renders top-level previews happily, but the screenshot engine discovers previews as
 *    JUnit tests and needs a real class to attach them to — top-level functions land in a
 *    synthetic `…Kt` facade and are silently skipped, which surfaces as "did not discover
 *    any tests".
 *  - The phone preview renders through [ChromePreview]: the bottom bar lives in the
 *    app's `Navigation`, above the pages, so a bare `RideNarrowContent` shot would miss
 *    the chrome users actually see. The expanded preview renders [RideWideContent]
 *    bare: production `Navigation` has no rail branch (it always uses `MainNavigation`
 *    + `BottomNavBar`), so there is no expanded chrome to reproduce.
 *  - The map renders without tiles here (Layoutlib has no native map renderer), so the
 *    `VectorMap` background, pins and results show against a plain backdrop. Acceptable
 *    per convention.
 *
 * Rendering goes through the app's real [DynamicTheme] with `darkTheme = true`, matching
 * the `cmd uimode night yes` the old on-device generator used. Note that Material You
 * sources its palette from the device wallpaper, which does not exist here, so these
 * render with the fallback scheme rather than a user's actual accent colour.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-ride", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Ride() {
        ChromePreview(Route.Ride)
    }

    @PreviewTest
    @Preview(name = "2-ride-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview2RideExpanded() {
        DynamicTheme(darkTheme = true) {
            RideWideContent(
                data = ridePreviewData(),
                events = ridePreviewEvents(),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Literals backing the Ride tab: a Ferry Building -> SFO trip with both fares compared. */
private fun ridePreviewData(): RideContentData {
    val pickup = Place(
        name = "Ferry Building",
        address = "1 Ferry Bldg, SF",
        location = LatLng(latitude = 37.7955, longitude = -122.3937),
    )
    val destination = Place(
        name = "SFO",
        address = "SFO, SF",
        location = LatLng(latitude = 37.6213, longitude = -122.3790),
    )
    val lyftQuote = RideQuote(
        provider = Provider.LYFT,
        productId = "lyft_std",
        displayName = "Lyft Standard",
        fareLowMinor = 1299,
        fareHighMinor = 1599,
        currency = "USD",
        pickupEtaMinutes = 4,
        tripDurationMinutes = 18,
        surgeMultiplier = null,
        capacity = 4,
    )
    val uberQuote = RideQuote(
        provider = Provider.UBER,
        productId = "uber_x",
        displayName = "UberX",
        fareLowMinor = 1499,
        fareHighMinor = 1499,
        currency = "USD",
        pickupEtaMinutes = 3,
        tripDurationMinutes = 18,
        surgeMultiplier = null,
        capacity = 4,
    )
    return RideContentData(
        camera = CameraState(CameraPosition(target = GeoPoint(-122.4194, 37.7749), zoom = 12.0)),
        routeOverlay = null,
        pickup = pickup,
        destination = destination,
        pickupQuery = pickup.name,
        destinationQuery = destination.name,
        suggestions = emptyList(),
        searching = false,
        active = null,
        results = mapOf(
            Provider.LYFT to QuoteResult.Success(quotes = listOf(lyftQuote)),
            Provider.UBER to QuoteResult.Success(quotes = listOf(uberQuote)),
        ),
        comparing = false,
    )
}

/** No-op callbacks: previews never drive state. */
private fun ridePreviewEvents() = RideContentEvents(
    onPickupChange = {},
    onPickupFocus = {},
    onResetPickup = {},
    onDestinationChange = {},
    onDestinationFocus = {},
    onClearDestination = {},
    onSearch = {},
    onSelect = {},
    onBook = { _, _ -> },
)

/**
 * The app's real navigation chrome around preview screen content: the same tab
 * list and the same [MainNavigation] host as `Navigation` — with a literal back
 * stack standing in for the selected tab, since previews have no ViewModel to
 * drive one.
 */
@Composable
private fun ChromePreview(route: Route) {
    DynamicTheme(darkTheme = true) {
        // NavDisplay requires a NavigationEventDispatcherOwner, which an Activity
        // normally provides — Layoutlib has no Activity, so previews supply a
        // root dispatcher explicitly (null parent).
        CompositionLocalProvider(
            LocalNavigationEventDispatcherOwner provides rememberNavigationEventDispatcherOwner(
                parent = null,
            ),
        ) {
            ChromePreviewContent(route)
        }
    }
}

@Composable
private fun ChromePreviewContent(route: Route) {
    val backStack = rememberNavBackStack(route)
    val current = backStack.last()
    val tabs = listOf(
        BottomBarItem("Ride", Route.Ride) { IconMap() },
        BottomBarItem("Current ride", Route.CurrentRide) { IconNavigationArrow() },
        BottomBarItem("Settings", Route.Accounts) { IconSettings() },
    )
    // Only the Ride tab has a slice. The other entries are empty boxes that are
    // never visible (the selected tab always matches the shown screen) — they
    // exist only so the entry set matches production's tab list.
    val entries: @Composable () -> Unit = {
        when (current) {
            Route.Ride -> RideNarrowContent(
                data = ridePreviewData(),
                events = ridePreviewEvents(),
                modifier = Modifier.fillMaxSize(),
            )
            else -> Box(Modifier.fillMaxSize()) { }
        }
    }
    MainNavigation(
        backStack,
        bottomBar = { BottomNavBar(backStack, tabs, current) },
    ) {
        entry<Route.Ride>(metadata = SiblingPage()) { entries() }
        entry<Route.CurrentRide>(metadata = SiblingPage()) { entries() }
        entry<Route.Accounts>(metadata = SiblingPage()) { entries() }
    }
}
