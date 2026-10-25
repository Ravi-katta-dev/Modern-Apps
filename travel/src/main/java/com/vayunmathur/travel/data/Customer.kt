package com.vayunmathur.travel.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * A saved booking profile, local-only: [id] is a random UUID generated on
 * device. Used to prefill contact email/phone/name on the passengers form; no
 * server use.
 */
@Entity
data class Customer(
    @PrimaryKey val id: String,
    val email: String,
    val givenName: String,
    val familyName: String,
    val phoneNumber: String = "",
) {
    val displayName: String
        get() = listOf(givenName, familyName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { email }
}
