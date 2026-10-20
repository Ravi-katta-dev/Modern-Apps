package com.vayunmathur.tvlauncher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.IconWifiFull
import com.vayunmathur.library.ui.IconWifiOff
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberClock
import com.vayunmathur.library.ui.rememberIs24Hour
import com.vayunmathur.library.util.rememberIsOnline
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Top-left date + giant time, top-right network tile (the tv-detox header).
 *
 * The clock getter is invoked only inside the [Text] scopes that display it, so ticks
 * recompose just those labels (the `rememberClock` contract).
 */
@Composable
fun TvClockHeader(
    onOpenNetworkSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column {
            val clock = rememberClock(1.seconds)
            val is24Hour = rememberIs24Hour()
            Text(
                text = dateLabel(clock()),
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xA8FFFFFF),
            )
            // Physical pixels, not dp: TV panels report densities that make fixed sp/dp
            // clock sizes balloon (176.sp filled the panel). The reference sets the time at
            // ~13% of screen height.
            val metrics = LocalContext.current.resources.displayMetrics
            val timePx = metrics.heightPixels * 0.13f
            val timeSize = with(LocalDensity.current) { timePx.toSp() }
            Text(
                text = timeLabel(clock(), is24Hour),
                fontSize = timeSize,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                lineHeight = timeSize * 1.05f,
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier.padding(top = 8.dp),
        ) {
            TvNetworkTile(onOpenNetworkSettings = onOpenNetworkSettings)
        }
    }
}

/** "Saturday, 17 October" shape: weekday + locale-ordered month/day. */
private fun dateLabel(nowMillis: Long): String {
    val date = Instant.fromEpochMilliseconds(nowMillis)
        .toLocalDateTime(TimeZone.currentSystemDefault()).date
    val weekday = DateString.dateWeekdayNoYear(date).substringBefore(',')
    val monthDay = DateString.dateMonthDay(date)
    return "$weekday, $monthDay"
}

/** Giant digits only, like the reference — a 12h AM/PM marker would double the width. */
private fun timeLabel(nowMillis: Long, is24Hour: Boolean): String {
    val time = Instant.fromEpochMilliseconds(nowMillis)
        .toLocalDateTime(TimeZone.currentSystemDefault()).time
    return DateString.timeNumeric(time, is24Hour)
}

/** Live network tile: Wi-Fi or offline, opening network settings on click. */
@Composable
private fun TvNetworkTile(
    onOpenNetworkSettings: () -> Unit,
) {
    val online by rememberIsOnline()
    Box(
        modifier = Modifier
            .size(56.dp)
            // No separate focusable(): clickable() provides focus handling — see TvAppGrid.
            .clickable(onClick = onOpenNetworkSettings),
        contentAlignment = Alignment.Center,
    ) {
        if (online) {
            IconWifiFull(
                modifier = Modifier.size(32.dp),
                tint = Color.White,
            )
        } else {
            IconWifiOff(
                modifier = Modifier.size(32.dp),
                tint = Color(0xA8FFFFFF),
            )
        }
    }
}
