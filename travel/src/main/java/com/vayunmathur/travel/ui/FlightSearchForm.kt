package com.vayunmathur.travel.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconSwapLanguages
import com.vayunmathur.library.ui.Text
import com.vayunmathur.travel.util.FlightQuery
import com.vayunmathur.travel.util.TravelViewModel
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.vayunmathur.travel.R
import kotlinx.datetime.LocalDate

/** The booking-provider cabin classes, with a display label. */
enum class Cabin(val code: String, @StringRes val label: Int) {
    ECONOMY("economy", R.string.economy),
    PREMIUM("premium_economy", R.string.premium),
    BUSINESS("business", R.string.business),
    FIRST("first", R.string.first),
}

/** The two supported trip shapes (multi-city was removed). */
enum class TripType(@StringRes val label: Int) {
    ONE_WAY(R.string.one_way),
    ROUND_TRIP(R.string.round_trip),
}

/** Max-stops options for the dropdown, mapped to the API's maxStops values. */
enum class MaxStops(val apiValue: Int, @StringRes val label: Int) {
    NONSTOP(0, R.string.stops_nonstop),
    ONE(1, R.string.stops_1),
    TWO_PLUS(2, R.string.stops_2_plus),
}

/**
 * Flight search form (Google-Flights style): a dropdown row (trip type,
 * passengers, cabin, max stops) above side-by-side From/To fields with a swap
 * button, then dates. Calls [onSearch] with a normalized [FlightQuery] when
 * the user taps "Search flights", or [onExplore] with the explore params when
 * the To field is in Explore mode.
 *
 * Date rules: no date before today; return not before depart.
 */
@Composable
fun FlightSearchForm(
    viewModel: TravelViewModel,
    modifier: Modifier = Modifier,
    onSearch: (FlightQuery) -> Unit,
    onExplore: (ExploreRequest) -> Unit = {},
) {
    var tripType by remember { mutableStateOf(TripType.ROUND_TRIP) }
    var origin by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    var depart by remember { mutableStateOf("") }
    var returnDate by remember { mutableStateOf("") }
    var mix by remember { mutableStateOf(PassengerMix()) }
    var cabin by remember { mutableStateOf(Cabin.ECONOMY) }
    var maxStops by remember { mutableStateOf(MaxStops.TWO_PLUS) }
    var exploreMode by remember { mutableStateOf(false) }
    // Bumped on swap so the autocomplete fields re-init from the swapped codes
    // (they own their display text internally).
    var swapNonce by remember { mutableIntStateOf(0) }

    val today = remember { todayUtc() }
    val departDate = remember(depart) { runCatching { LocalDate.parse(depart) }.getOrNull() }
    val returnLocalDate = remember(returnDate) { runCatching { LocalDate.parse(returnDate) }.getOrNull() }

    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchDropdown(
                display = stringResource(tripType.label),
                options = TripType.entries.map { it.name to stringResource(it.label) },
                selectedValue = tripType.name,
                onSelect = { name -> tripType = TripType.valueOf(name) },
            )
            PassengerField(mix = mix, onMix = { mix = it })
            SearchDropdown(
                display = stringResource(cabin.label),
                options = Cabin.entries.map { it.name to stringResource(it.label) },
                selectedValue = cabin.name,
                onSelect = { name -> cabin = Cabin.valueOf(name) },
            )
            SearchDropdown(
                display = stringResource(maxStops.label),
                options = MaxStops.entries.map { it.name to stringResource(it.label) },
                selectedValue = maxStops.name,
                onSelect = { name -> maxStops = MaxStops.valueOf(name) },
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.runtime.key(swapNonce) {
                PlaceAutocompleteField(
                    "From",
                    viewModel,
                    onCodeChange = { origin = it },
                    modifier = Modifier.weight(1f),
                    initialText = origin,
                )
            }
            IconButton(onClick = {
                val o = origin
                origin = destination
                destination = o
                exploreMode = false
                swapNonce++
            }) { IconSwapLanguages() }
            androidx.compose.runtime.key(swapNonce) {
                PlaceAutocompleteField(
                    "To",
                    viewModel,
                    onCodeChange = {
                        destination = it
                        // Typing a code exits Explore mode.
                        if (it.isNotBlank()) exploreMode = false
                    },
                    modifier = Modifier.weight(1f),
                    initialText = destination,
                    showExploreRow = true,
                    onExploreSelected = {
                        destination = ""
                        exploreMode = true
                    },
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DateField(
                stringResource(R.string.depart),
                depart,
                onDate = { depart = it },
                modifier = Modifier.weight(1f),
                minDate = today,
            )
            if (tripType == TripType.ROUND_TRIP) {
                DateField(
                    stringResource(R.string.return_label),
                    returnDate,
                    onDate = { returnDate = it },
                    modifier = Modifier.weight(1f),
                    minDate = departDate ?: today,
                )
            }
        }

        val departValid = departDate != null && departDate >= today
        val returnValid = tripType == TripType.ONE_WAY ||
            (returnLocalDate != null && departDate != null && returnLocalDate >= departDate)
        val valid = origin.isNotBlank() && (destination.isNotBlank() || exploreMode) && departValid && returnValid

        Button(
            onClick = {
                // Infants in seats fly as children (age 1) so they hold a seat;
                // infants on lap use the infant bucket.
                val childAges = mix.childAges + List(mix.infantsInSeat) { 1 }
                if (exploreMode) {
                    onExplore(
                        ExploreRequest(
                            origin = origin,
                            tripType = if (tripType == TripType.ROUND_TRIP) "roundtrip" else "oneway",
                            depart = depart,
                            returnDate = returnDate.takeIf { tripType == TripType.ROUND_TRIP },
                            cabin = cabin.code,
                            adults = mix.adults,
                            children = childAges.joinToString(","),
                            infants = mix.infantsOnLap,
                            maxConnections = maxStops.apiValue,
                        )
                    )
                    return@Button
                }
                val slices = when (tripType) {
                    TripType.ROUND_TRIP -> "$origin:$destination:$depart,$destination:$origin:$returnDate"
                    TripType.ONE_WAY -> "$origin:$destination:$depart"
                }
                onSearch(
                    FlightQuery(
                        slices = slices,
                        adults = mix.adults,
                        children = childAges.joinToString(","),
                        infants = mix.infantsOnLap,
                        cabin = cabin.code,
                        maxConnections = maxStops.apiValue,
                        isRoundTrip = tripType == TripType.ROUND_TRIP,
                    )
                )
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(if (exploreMode) R.string.explore_search else R.string.search_flights)) }
    }
}
