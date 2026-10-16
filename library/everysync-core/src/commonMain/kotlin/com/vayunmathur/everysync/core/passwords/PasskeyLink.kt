package com.vayunmathur.everysync.core.passwords

/** Detached-link marker, shared with the Android Room column encoding. */
const val PASSKEY_LINK_DETACHED = "-"

/**
 * Which password a passkey belongs to, decoded from the linked-password sync id.
 *
 * The three states are encoded in one nullable column rather than a syncId plus a `detached`
 * flag: the vault syncs as a flat field map with last-writer-wins, and two columns can be merged
 * into a contradictory pair, while three mutually exclusive states in one column cannot disagree.
 */
sealed interface PasskeyLink {
    /** No decision recorded; the matching rule in [mergeCredentials] applies. */
    data object Auto : PasskeyLink

    /** The user disconnected it. Never auto-match, however good the match would be. */
    data object Detached : PasskeyLink

    /** Pinned to the password with this syncId, regardless of domain or username. */
    data class To(val passwordSyncId: String) : PasskeyLink

    companion object {
        fun fromColumn(raw: String?): PasskeyLink = when {
            raw.isNullOrBlank() -> Auto
            raw == PASSKEY_LINK_DETACHED -> Detached
            else -> To(raw)
        }
    }
}

fun PasskeyLink.toColumn(): String? = when (this) {
    PasskeyLink.Auto -> null
    PasskeyLink.Detached -> PASSKEY_LINK_DETACHED
    is PasskeyLink.To -> passwordSyncId
}
