package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.staySuggestions
import androidx.compose.ui.res.stringResource
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * Hotel search form (used from Home when the "Stays" product is selected):
 * location autocomplete, check-in/out dates, rooms and guests dropdowns.
 *
 * Date rules: check-in not before today; check-out after check-in.
 */
@Composable
fun StaySearchForm(
    viewModel: TravelViewModel,
    modifier: Modifier = Modifier,
    onSearch: (place: String, checkIn: String, checkOut: String, rooms: Int, adults: Int, latitude: Double?, longitude: Double?) -> Unit,
) {
    var place by remember { mutableStateOf("") }
    var latitude by remember { mutableStateOf<Double?>(null) }
    var longitude by remember { mutableStateOf<Double?>(null) }
    var checkIn by remember { mutableStateOf("") }
    var checkOut by remember { mutableStateOf("") }
    var rooms by remember { mutableIntStateOf(1) }
    var guests by remember { mutableIntStateOf(2) }

    val today = remember { todayUtc() }
    val checkInDate = remember(checkIn) { runCatching { LocalDate.parse(checkIn) }.getOrNull() }
    val checkOutDate = remember(checkOut) { runCatching { LocalDate.parse(checkOut) }.getOrNull() }

    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchDropdown(
                display = "$rooms ${stringResource(R.string.rooms)}",
                options = (1..5).map { it.toString() to "$it" },
                selectedValue = rooms.toString(),
                onSelect = { rooms = it.toInt() },
            )
            SearchDropdown(
                display = "$guests ${stringResource(R.string.guests)}",
                options = (1..9).map { it.toString() to "$it" },
                selectedValue = guests.toString(),
                onSelect = { guests = it.toInt() },
            )
        }
        StaySuggestField(
            label = stringResource(com.vayunmathur.travel.R.string.destination_or_hotel),
            viewModel = viewModel,
            onSelect = { name, lat, lng ->
                place = name
                latitude = lat
                longitude = lng
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateField(
                stringResource(R.string.check_in),
                checkIn,
                onDate = { checkIn = it },
                modifier = Modifier.weight(1f),
                minDate = today,
            )
            DateField(
                stringResource(R.string.check_out),
                checkOut,
                onDate = { checkOut = it },
                modifier = Modifier.weight(1f),
                minDate = checkInDate?.plus(DatePeriod(days = 1)) ?: today,
            )
        }
        val checkInValid = checkInDate != null && checkInDate >= today
        val checkOutValid = checkOutDate != null && checkInDate != null && checkOutDate > checkInDate
        Button(
            onClick = { onSearch(place, checkIn, checkOut, rooms, guests, latitude, longitude) },
            enabled = place.isNotBlank() && checkInValid && checkOutValid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.search_hotels)) }
    }
}

/**
 * Stays location autocomplete: type a city or hotel name and pick a suggestion,
 * which carries coordinates so the search can run without an airport code.
 */
@Composable
private fun StaySuggestField(
    label: String,
    viewModel: TravelViewModel,
    onSelect: (name: String, latitude: Double?, longitude: Double?) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<com.vayunmathur.travel.network.StaySuggestionDto>>(emptyList()) }
    var justSelected by remember { mutableStateOf(false) }

    LaunchedEffect(text) {
        if (justSelected) {
            justSelected = false
            return@LaunchedEffect
        }
        if (text.length < 2) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(100)
        suggestions = viewModel.staySuggestions(text)
    }

    Column {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                // Free-text fallback: pass the typed text with no coordinates so
                // the server resolves it as an IATA code.
                onSelect(it.trim(), null, null)
            },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (suggestions.isNotEmpty()) {
            ElevatedCard(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Column {
                    suggestions.take(6).forEach { s ->
                        com.vayunmathur.library.ui.ListItem(
                            modifier = Modifier.clickable {
                                onSelect(s.name, s.latitude, s.longitude)
                                text = s.name
                                justSelected = true
                                suggestions = emptyList()
                            },
                        ) { Text(s.name) }
                    }
                }
            }
        }
    }
}
