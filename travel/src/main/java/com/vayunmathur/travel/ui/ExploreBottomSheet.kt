package com.vayunmathur.travel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberModalBottomSheetState
import com.vayunmathur.travel.R
import com.vayunmathur.travel.domain.exploreLiveSlices
import com.vayunmathur.travel.network.ExploreDestinationDto
import com.vayunmathur.travel.util.FlightQuery
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.searchFlights

/**
 * Bottom sheet for an Explore map pin: destination header (photo, city,
 * cached "from" fare with a disclaimer) then LIVE flights to that place.
 * The cached explore fare is never booked; tapping a live offer reports it
 * via [onOffer] (the caller runs `selectOffer` + navigates to review).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreBottomSheet(
    destination: ExploreDestinationDto,
    request: ExploreRequest,
    viewModel: TravelViewModel,
    onDismiss: () -> Unit,
    onOffer: (com.vayunmathur.travel.network.OfferDto) -> Unit,
) {
    val flights by viewModel.flights.collectAsStateWithLifecycle()

    LaunchedEffect(destination) {
        val destCode = destination.destinationCode.ifBlank { destination.iataCode }
        val depart = destination.departDate.ifBlank { request.depart }
        val slices = exploreLiveSlices(
            origin = request.origin,
            destinationCode = destCode,
            departDate = depart,
            returnDate = destination.returnDate,
            isRoundTrip = request.tripType == "roundtrip",
        )
        viewModel.searchFlights(
            FlightQuery(
                slices = slices,
                adults = request.adults,
                children = request.children,
                infants = request.infants,
                cabin = request.cabin,
                maxConnections = request.maxConnections,
                isRoundTrip = request.tripType == "roundtrip",
            )
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (destination.imageUrl.isNotBlank()) {
                            AsyncImage(
                                model = destination.imageUrl,
                                contentDescription = destination.cityName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(64.dp),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(destination.cityName, style = MaterialTheme.typography.titleLarge)
                            Text(
                                "${stringResource(R.string.from)} ${formatMoney(destination.lowestAmount, destination.lowestCurrency)}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.explore_fare_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(stringResource(R.string.explore_live_fares), style = MaterialTheme.typography.titleSmall)
                }
            }
            items(flights.visibleOffers) { offer ->
                OfferCard(offer) { onOffer(offer) }
            }
            if (flights.loading || flights.error != null || (flights.hasSearched && flights.visibleOffers.isEmpty())) {
                item {
                    StatusBox(
                        loading = flights.loading,
                        error = flights.error,
                        isEmpty = flights.hasSearched && flights.visibleOffers.isEmpty(),
                    )
                }
            }
        }
    }
}
