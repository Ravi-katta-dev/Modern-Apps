package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.rememberIs24Hour
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.domain.sliceSignature
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.util.FlightQuery
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.loadPartialFares
import com.vayunmathur.travel.util.loadPartialReturn
import com.vayunmathur.travel.util.multiCitySelection
import com.vayunmathur.travel.util.searchMultiCityLeg
import com.vayunmathur.travel.util.selectMultiCityOffer
import com.vayunmathur.travel.util.selectOffer
import com.vayunmathur.travel.util.selectOffers
import com.vayunmathur.travel.util.startMultiCitySearch
import com.vayunmathur.travel.util.startPartialSearch
import androidx.compose.ui.res.stringResource

/**
 * Round-trip step 1: pick the outbound flight. One synchronous search, grouped
 * on-device by distinct outbound slice (cheapest price per row).
 */
@Composable
fun OutboundSelectPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.OutboundSelect,
) {
    LaunchedEffect(route) {
        viewModel.startPartialSearch(
            FlightQuery(
                slices = route.slices,
                adults = route.adults,
                children = route.children,
                infants = route.infants,
                cabin = route.cabin,
                maxConnections = route.maxConnections,
                isRoundTrip = true,
            )
        )
    }
    PartialLegScaffold(
        title = stringResource(R.string.choose_outbound),
        subtitle = stringResource(R.string.step_1_of_3),
        backStack = backStack,
        viewModel = viewModel,
    ) { offer ->
        val signature = offer.slices.firstOrNull()?.let { sliceSignature(it) }.orEmpty()
        backStack.add(Route.ReturnSelect(signature))
    }
}

/** Round-trip step 2: pick the return flight for the chosen outbound. */
@Composable
fun ReturnSelectPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.ReturnSelect,
) {
    LaunchedEffect(route.outboundSignature) { viewModel.loadPartialReturn(route.outboundSignature) }
    PartialLegScaffold(
        title = stringResource(R.string.choose_return),
        subtitle = stringResource(R.string.step_2_of_3),
        backStack = backStack,
        viewModel = viewModel,
    ) { offer ->
        val inbound = offer.slices.getOrNull(1)?.let { sliceSignature(it) }.orEmpty()
        backStack.add(Route.FareSelect(route.outboundSignature, inbound))
    }
}

/** Round-trip step 3: pick the final fare, then continue to review. */
@Composable
fun FareSelectPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.FareSelect,
) {
    LaunchedEffect(route.outboundSignature, route.inboundSignature) {
        viewModel.loadPartialFares(route.outboundSignature, route.inboundSignature)
    }
    PartialLegScaffold(
        title = stringResource(R.string.choose_fare),
        subtitle = stringResource(R.string.step_3_of_3),
        backStack = backStack,
        viewModel = viewModel,
    ) { offer ->
        viewModel.selectOffer(offer)
        backStack.add(Route.OfferReview(offer.offerId))
    }
}

/**
 * Multi-city: pick one offer for leg [Route.MultiCityLeg.legIndex] (each leg
 * searched as a one-way). When every leg has a pick, booking posts all ids in
 * one trip; otherwise advance to the next leg.
 */
@Composable
fun MultiCityLegPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.MultiCityLeg,
) {
    val query = remember(route) {
        FlightQuery(
            slices = route.slices,
            adults = route.adults,
            children = route.children,
            infants = route.infants,
            cabin = route.cabin,
            maxConnections = route.maxConnections,
        )
    }
    val legs = query.legs
    LaunchedEffect(route) {
        if (route.legIndex == 0) viewModel.startMultiCitySearch(query)
        else viewModel.searchMultiCityLeg(query, route.legIndex)
    }
    val state by viewModel.multiCity.collectAsStateWithLifecycle()
    val offers = state.legOffers[route.legIndex].orEmpty()
    val pickedId = state.selectedOfferIds[route.legIndex]
    val picked = offers.find { it.offerId == pickedId }
    val legLabel = legs.getOrNull(route.legIndex).orEmpty()
    val allPicked = (0 until legs.size).all { state.selectedOfferIds[it] != null }

    MultiCityLegScaffold(
        title = stringResource(R.string.choose_flight_leg, route.legIndex + 1, legs.size),
        subtitle = legLabel.replace(':', ' ').trim(),
        backStack = backStack,
        loading = state.loading,
        error = state.error,
        offers = offers,
        pickedId = pickedId,
        allPicked = allPicked,
        onPick = { viewModel.selectMultiCityOffer(route.legIndex, it) },
        onNextLeg = {
            backStack.add(route.copy(legIndex = route.legIndex + 1))
        },
        onFinish = {
            val selection = viewModel.multiCitySelection(legs.size).filterNotNull()
            if (selection.size == legs.size) {
                viewModel.selectOffers(selection)
                backStack.add(Route.OfferReview(selection.first().offerId))
            }
        },
        pickedLabel = picked?.let { formatMoney(it.totalAmount, it.currency) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PartialLegScaffold(
    title: String,
    subtitle: String,
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    onSelect: (OfferDto) -> Unit,
) {
    val state by viewModel.partialFlow.collectAsStateWithLifecycle()
    LazyListScaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconNavigation(backStack) },
            )
        },
        scrollBehavior = appBarScrollBehavior(),
    ) {
        items(state.offers) { offer -> PartialOfferCard(offer) { onSelect(offer) } }
        if (state.loading || state.error != null || state.offers.isEmpty()) {
            item {
                StatusBox(
                    loading = state.loading,
                    error = state.error,
                    isEmpty = !state.loading && state.error == null && state.offers.isEmpty(),
                    emptyMessage = stringResource(R.string.no_flights_for_leg),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MultiCityLegScaffold(
    title: String,
    subtitle: String,
    backStack: NavBackStack<Route>,
    loading: Boolean,
    error: String?,
    offers: List<OfferDto>,
    pickedId: String?,
    allPicked: Boolean,
    onPick: (OfferDto) -> Unit,
    onNextLeg: () -> Unit,
    onFinish: () -> Unit,
    pickedLabel: String?,
) {
    LazyListScaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        if (subtitle.isNotBlank()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = { IconNavigation(backStack) },
            )
        },
        scrollBehavior = appBarScrollBehavior(),
    ) {
        items(offers) { offer ->
            PartialOfferCard(offer, selected = offer.offerId == pickedId) { onPick(offer) }
        }
        if (loading || error != null || offers.isEmpty()) {
            item {
                StatusBox(
                    loading = loading,
                    error = error,
                    isEmpty = !loading && error == null && offers.isEmpty(),
                    emptyMessage = stringResource(R.string.no_flights_for_leg),
                )
            }
        }
        if (pickedId != null) {
            item {
                Button(
                    onClick = { if (allPicked) onFinish() else onNextLeg() },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text(
                        if (allPicked) stringResource(R.string.review_picks, pickedLabel.orEmpty())
                        else stringResource(R.string.continue_action)
                    )
                }
            }
        }
    }
}

@Composable
private fun PartialOfferCard(offer: OfferDto, selected: Boolean = false, onClick: () -> Unit) {
    val is24 = rememberIs24Hour()
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onClick() },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AirlineLogo(offer.ownerLogoUrl, offer.ownerIata)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(offer.owner.ifBlank { stringResource(R.string.flight) }, style = MaterialTheme.typography.titleMedium)
                    if (offer.fareBrand.isNotBlank()) {
                        AssistChip(onClick = {}, label = { Text(offer.fareBrand) })
                    }
                    if (selected) {
                        AssistChip(onClick = {}, label = { Text(stringResource(R.string.picked)) })
                    }
                }
                offer.slices.forEach { slice ->
                    val times = "${formatTime(slice.departureAt, is24)} – ${formatTime(slice.arrivalAt, is24)}"
                    Text(
                        "${slice.origin} → ${slice.destination}  ·  $times",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        listOf(stopsLabel(slice.stops), formatDuration(slice.durationMinutes))
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                formatMoney(offer.totalAmount, offer.currency),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
