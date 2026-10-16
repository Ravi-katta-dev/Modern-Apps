package com.vayunmathur.everysync.core.sync

import kotlin.uuid.Uuid

/**
 * Last agreed state of one relay record, written after a successful merge.
 *
 * Mirrors the passwords baseline: without it "present locally, absent remotely"
 * cannot be told apart from "created locally, not pushed yet", so deletions
 * could never propagate. [deleted] marks a retained tombstone alongside the
 * snapshot rows so a delete survives until both sides have acknowledged it.
 */
data class RelaySnapshot(
    val syncId: String,
    val kind: String,
    val contentHash: String,
    val localUpdatedAt: Long,
    val remoteModified: Long,
    val lastSyncedAt: Long,
    val deleted: Boolean = false,
)

/** Opaque 32-char identity shared between a local row and its relay record. */
fun newRelaySyncId(): String = Uuid.random().toString().replace("-", "")

fun relayContentHash(canonical: String): String =
    SyncDigest.sha256Hex(canonical.encodeToByteArray())
