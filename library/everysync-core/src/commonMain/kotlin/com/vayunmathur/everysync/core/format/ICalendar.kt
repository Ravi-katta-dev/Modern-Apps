package com.vayunmathur.everysync.core.format

import com.vayunmathur.everysync.core.data.RemoteEvent
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.toInstant

/**
 * Minimal iCalendar (RFC 5545) VEVENT reader/writer covering the fields
 * EverySync maps into platform calendar stores. Self-contained; RRULE lines
 * are carried through verbatim.
 *
 * KMP port of the Android `ICalendar`: `SimpleDateFormat`/`java.util.TimeZone`
 * replaced with `kotlinx-datetime`. Behavior is identical (UTC date-only,
 * `Z`-suffixed UTC, TZID local).
 */
object ICalendar {

    /** Parse a VCALENDAR body into its VEVENTs. [calendarId] tags each result. */
    fun parse(text: String, calendarId: String, fallbackUid: String? = null): List<RemoteEvent> {
        val lines = unfold(text)
        val events = mutableListOf<RemoteEvent>()
        var cur: MutableMap<String, Pair<String, String>>? = null // key -> (params, value)
        for (line in lines) {
            val trimmed = line.trimEnd()
            when {
                trimmed.equals("BEGIN:VEVENT", true) -> cur = mutableMapOf()
                trimmed.equals("END:VEVENT", true) -> {
                    cur?.let { events += toEvent(it, calendarId, fallbackUid) }
                    cur = null
                }
                cur != null -> {
                    val prop = splitProperty(trimmed)
                    if (prop != null) {
                        val (name, params, value) = prop
                        cur[name.uppercase()] = params to value
                    }
                }
            }
        }
        return events
    }

    fun serialize(e: RemoteEvent): String = buildString {
        append("BEGIN:VCALENDAR\r\n")
        append("VERSION:2.0\r\n")
        append("PRODID:-//vayunmathur//EverySync//EN\r\n")
        append("BEGIN:VEVENT\r\n")
        append("UID:${e.uid}\r\n")
        e.recurrenceId?.takeIf { it.isNotBlank() }?.let { append("RECURRENCE-ID:${it}\r\n") }
        if (e.allDay) {
            append("DTSTART;VALUE=DATE:${dateOnly(e.startMillis)}\r\n")
            append("DTEND;VALUE=DATE:${dateOnly(e.endMillis)}\r\n")
        } else {
            append("DTSTART:${utc(e.startMillis)}\r\n")
            append("DTEND:${utc(e.endMillis)}\r\n")
        }
        if (e.summary.isNotBlank()) append("SUMMARY:${esc(e.summary)}\r\n")
        if (e.description.isNotBlank()) append("DESCRIPTION:${esc(e.description)}\r\n")
        if (e.location.isNotBlank()) append("LOCATION:${esc(e.location)}\r\n")
        e.rrule?.takeIf { it.isNotBlank() }?.let { append("RRULE:${it.removePrefix("RRULE:")}\r\n") }
        append("END:VEVENT\r\n")
        append("END:VCALENDAR\r\n")
    }

    private fun toEvent(
        props: Map<String, Pair<String, String>>,
        calendarId: String,
        fallbackUid: String?,
    ): RemoteEvent {
        val uid = props["UID"]?.second?.ifBlank { null } ?: fallbackUid ?: props.hashCode().toString()
        val (startMillis, startAllDay, tz) = parseTime(props["DTSTART"])
        val (endMillisRaw, _, _) = parseTime(props["DTEND"])
        val start = startMillis ?: 0L
        var end = endMillisRaw ?: (start + if (startAllDay) DAY_MS else HOUR_MS)
        if (startAllDay && end == start) end = start + DAY_MS
        return RemoteEvent(
            uid = uid,
            calendarId = calendarId,
            summary = unescape(props["SUMMARY"]?.second ?: ""),
            description = unescape(props["DESCRIPTION"]?.second ?: ""),
            location = unescape(props["LOCATION"]?.second ?: ""),
            startMillis = start,
            endMillis = end,
            allDay = startAllDay,
            timezone = tz ?: "UTC",
            rrule = props["RRULE"]?.second?.ifBlank { null },
            recurrenceId = props["RECURRENCE-ID"]?.second?.ifBlank { null },
        )
    }

    /** Returns (epochMillis?, isAllDay, tzid?). */
    private fun parseTime(prop: Pair<String, String>?): Triple<Long?, Boolean, String?> {
        if (prop == null) return Triple(null, false, null)
        val (params, value) = prop
        val up = params.uppercase()
        val allDay = up.contains("VALUE=DATE") ||
            (value.length == DATE_ONLY_LEN && value.all { it.isDigit() })
        return try {
            when {
                allDay -> {
                    val date = LocalDate.parse(dashDate(value))
                    Triple(
                        date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
                        true,
                        "UTC",
                    )
                }
                value.endsWith("Z") -> {
                    val dt = LocalDateTime.parse(dashDateTime(value.dropLast(1)))
                    Triple(dt.toInstant(TimeZone.UTC).toEpochMilliseconds(), false, "UTC")
                }
                else -> {
                    val tzid = extractTzid(params)
                    val zone = runCatching { TimeZone.of(tzid ?: "UTC") }.getOrDefault(TimeZone.UTC)
                    val dt = LocalDateTime.parse(dashDateTime(value))
                    Triple(dt.toInstant(zone).toEpochMilliseconds(), false, tzid ?: "UTC")
                }
            }
        } catch (_: Exception) {
            Triple(null, allDay, null)
        }
    }

    /** `yyyyMMdd` -> `yyyy-MM-dd` (for `LocalDate.parse`). */
    private fun dashDate(v: String): String =
        if (v.length == DATE_ONLY_LEN && v.all { it.isDigit() }) {
            "${v.take(4)}-${v.substring(4, 6)}-${v.substring(6, 8)}"
        } else {
            v
        }

    /** `yyyyMMdd'T'HHmmss` -> `yyyy-MM-dd'T'HH:mm:ss` (for `LocalDateTime.parse`). */
    private fun dashDateTime(v: String): String {
        val t = v.indexOf('T')
        if (t < 0) return v
        val d = v.take(t)
        val time = v.substring(t + 1)
        val dashed = if (d.length == DATE_ONLY_LEN && d.all { it.isDigit() }) dashDate(d) else d
        val clock = if (time.length >= 6 && time.take(6).all { it.isDigit() }) {
            "${time.take(2)}:${time.substring(2, 4)}:${time.substring(4, 6)}${time.drop(6)}"
        } else {
            time
        }
        return "${dashed}T$clock"
    }

    private fun extractTzid(params: String): String? =
        params.split(";").map { it.split("=", limit = 2) }
            .firstOrNull { it.size == 2 && it[0].uppercase() == "TZID" }?.get(1)

    private fun pad2(n: Int): String = n.toString().padStart(2, '0')
    private fun pad4(n: Int): String = n.toString().padStart(4, '0')

    private fun utc(millis: Long): String {
        val dt = kotlinx.time.Instant.fromEpochMilliseconds(millis)
            .toLocalDateTime(TimeZone.UTC)
        return "${pad4(dt.year)}${pad2(dt.month.number)}${pad2(dt.day)}T" +
            "${pad2(dt.hour)}${pad2(dt.minute)}${pad2(dt.second)}Z"
    }

    private fun dateOnly(millis: Long): String {
        val d = kotlinx.time.Instant.fromEpochMilliseconds(millis)
            .toLocalDateTime(TimeZone.UTC).date
        return "${pad4(d.year)}${pad2(d.month.number)}${pad2(d.day)}"
    }

    private fun unfold(text: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in text.replace("\r\n", "\n").split("\n")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) {
                out[out.lastIndex] = out.last() + raw.trimStart()
            } else {
                out.add(raw)
            }
        }
        return out
    }

    private fun splitProperty(line: String): Triple<String, String, String>? {
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        val left = line.take(colon)
        val value = line.substring(colon + 1)
        val semi = left.indexOf(';')
        return if (semi > 0) Triple(left.take(semi), left.substring(semi + 1), value)
        else Triple(left, "", value)
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    private fun unescape(s: String): String =
        s.replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    private const val HOUR_MS = 60L * 60L * 1000L
    private const val DAY_MS = 24L * HOUR_MS
    private const val DATE_ONLY_LEN = 8
}
