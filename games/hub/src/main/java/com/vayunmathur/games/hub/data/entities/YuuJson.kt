package com.vayunmathur.games.hub.data.entities

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * JSON encode/decode for [YuuFaceConfig] at the store boundary (notes-app `NoteBlock`
 * precedent). `ignoreUnknownKeys` lets older configs parse after part additions, and
 * any unparseable blob falls back to [YuuFaceConfig.DEFAULT] so a stale row can never
 * break the plaza grid.
 */
private val yuuJson = Json { ignoreUnknownKeys = true }

fun YuuFaceConfig.encode(): String = yuuJson.encodeToString(this)

fun String.decodeYuuConfig(): YuuFaceConfig =
    if (isBlank()) YuuFaceConfig.DEFAULT
    else try {
        yuuJson.decodeFromString<YuuFaceConfig>(this)
    } catch (_: SerializationException) {
        YuuFaceConfig.DEFAULT
    } catch (_: IllegalArgumentException) {
        YuuFaceConfig.DEFAULT
    }
