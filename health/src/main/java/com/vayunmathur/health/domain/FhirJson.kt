package com.vayunmathur.health.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val fhirJson = Json { ignoreUnknownKeys = true }

internal fun parseObject(data: String): JsonObject? = try {
    fhirJson.parseToJsonElement(data) as? JsonObject
} catch (_: Exception) {
    null
}

internal fun JsonObject?.string(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

internal fun JsonObject?.codingWithSystem(system: String): JsonObject? =
    (this?.get("coding") as? JsonArray)
        ?.firstOrNull { (it as? JsonObject).string("system") == system } as? JsonObject

internal fun JsonObject?.anyCoding(): JsonObject? =
    (this?.get("coding") as? JsonArray)?.firstOrNull() as? JsonObject

internal fun JsonObject?.conceptText(): String? =
    this.string("text") ?: this.anyCoding()?.string("display")

internal fun JsonObject.firstNoteText(): String? =
    (this["note"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.string("text") }

/** The code carried in a `clinicalStatus`-shaped CodeableConcept. */
internal fun JsonObject?.clinicalStatusCode(): String? =
    this.anyCoding()?.string("code") ?: this.string("text")

private const val OBSERVATION_CATEGORY_SYSTEM =
    "http://terminology.hl7.org/CodeSystem/observation-category"

internal fun JsonObjectBuilder.putObservationCategory(code: String) {
    putJsonArray("category") {
        add(
            buildJsonObject {
                putJsonArray("coding") {
                    add(
                        buildJsonObject {
                            put("system", OBSERVATION_CATEGORY_SYSTEM)
                            put("code", code)
                        }
                    )
                }
            }
        )
    }
}

/** A CodeableConcept with exactly one coding, which is all any of these need. */
internal fun JsonObjectBuilder.putCoded(
    key: String,
    system: String,
    code: String,
    display: String,
) {
    putJsonObject(key) {
        putJsonArray("coding") {
            add(
                buildJsonObject {
                    put("system", system)
                    put("code", code)
                    put("display", display)
                }
            )
        }
        put("text", display)
    }
}

internal fun JsonObjectBuilder.putQuantity(value: Double, unit: String?) {
    put("value", value)
    unit?.takeIf { it.isNotBlank() }?.let {
        put("unit", it)
        put("system", FhirRecords.UCUM_SYSTEM)
        put("code", it)
    }
}

internal fun formatDateTime(instant: Instant): String =
    DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))

/**
 * A date picked from a calendar as a FHIR `date`, anything else as a full `dateTime`.
 *
 * Date-precision fields are stored as local midnight. Writing that as a UTC instant puts it on the
 * previous day for any reader east of UTC, so a value sitting exactly on local midnight is written
 * as the bare date it stands for.
 */
internal fun formatClinicalDate(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
    val local = instant.atZone(zone)
    return if (local.toLocalTime() == LocalTime.MIDNIGHT) {
        local.toLocalDate().toString()
    } else {
        formatDateTime(instant)
    }
}

private const val ENTERED_IN_ERROR = "entered-in-error"

/**
 * Whether the author has retracted this resource.
 *
 * Most types say so in `status`; AllergyIntolerance and Condition say it in `verificationStatus`.
 */
internal fun isEnteredInError(data: String): Boolean {
    val root = parseObject(data) ?: return false
    return root.string("status") == ENTERED_IN_ERROR ||
        (root["verificationStatus"] as? JsonObject).clinicalStatusCode() == ENTERED_IN_ERROR
}

internal fun formatQuantity(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
