package com.vayunmathur.everysync.core.passwords

import kotlinx.serialization.Serializable

/** Platform-free password record for the shared merge/match logic. */
@Serializable
data class CredentialDto(
    val name: String = "",
    val username: String = "",
    val email: String = "",
    val websites: List<String> = emptyList(),
    val syncId: String = "",
    val creationTime: Long = 0L,
)

/** Platform-free passkey record for the shared merge/match logic. */
@Serializable
data class PasskeyDto(
    val rpId: String = "",
    val rpName: String = "",
    val credentialId: String = "",
    val userId: String = "",
    val userName: String = "",
    val userDisplayName: String = "",
    val creationTime: Long = 0L,
    val syncId: String = "",
    val linkedPasswordSyncId: String? = null,
)

/**
 * A strict partition of the passkeys: each one is attached to exactly one password or is
 * standalone, never both and never neither. The list renders a row per password plus a row per
 * standalone passkey, so a passkey appearing twice would mean duplicate list keys.
 */
data class MergeResult(
    val passkeysByPasswordSyncId: Map<String, List<PasskeyDto>>,
    val standalone: List<PasskeyDto>,
)

/**
 * Pairs each passkey with the password it belongs to.
 *
 * An explicit link wins over the automatic rule in both directions. Automatic matching requires
 * both the domain and the username to agree, and only applies when exactly one password is a
 * candidate — anything ambiguous is left standalone for the user to link by hand.
 */
fun mergeCredentials(passwords: List<CredentialDto>, passkeys: List<PasskeyDto>): MergeResult {
    val bySyncId = passwords.associateBy { it.syncId }
    val attached = mutableMapOf<String, MutableList<PasskeyDto>>()
    val standalone = mutableListOf<PasskeyDto>()

    for (passkey in passkeys) {
        val target = when (val link = PasskeyLink.fromColumn(passkey.linkedPasswordSyncId)) {
            PasskeyLink.Detached -> null
            // A link can outlive its password, or arrive before it on a device that is still
            // syncing. Show it standalone but leave the link alone so it re-attaches later.
            is PasskeyLink.To -> bySyncId[link.passwordSyncId]
            PasskeyLink.Auto -> passwords.filter { matches(passkey, it) }.singleOrNull()
        }
        if (target == null) standalone.add(passkey)
        else attached.getOrPut(target.syncId) { mutableListOf() }.add(passkey)
    }

    return MergeResult(
        attached.mapValues { (_, list) -> list.sortedWith(compareBy({ it.creationTime }, { it.syncId })) },
        standalone,
    )
}

/** The passkeys shown on [password]'s detail page. Resolves identically to the list. */
fun passkeysFor(password: CredentialDto, passwords: List<CredentialDto>, passkeys: List<PasskeyDto>): List<PasskeyDto> =
    mergeCredentials(passwords, passkeys).passkeysByPasswordSyncId[password.syncId].orEmpty()

private fun matches(passkey: PasskeyDto, password: CredentialDto): Boolean =
    usernameMatches(passkey, password) && domainMatches(passkey, password)

private fun usernameMatches(passkey: PasskeyDto, password: CredentialDto): Boolean {
    val user = passkey.userName.trim()
    if (user.isEmpty()) return false
    return user.equals(password.username.trim(), ignoreCase = true) ||
        user.equals(password.email.trim(), ignoreCase = true)
}

private fun domainMatches(passkey: PasskeyDto, password: CredentialDto): Boolean {
    val rp = DomainMatch.normalizeSite(passkey.rpId)
    if (rp.isEmpty()) return false
    return password.websites.any { site ->
        !DomainMatch.isAndroidPackageSite(site) &&
            DomainMatch.isSameSiteOrSubdomain(DomainMatch.normalizeSite(site), rp)
    }
}
