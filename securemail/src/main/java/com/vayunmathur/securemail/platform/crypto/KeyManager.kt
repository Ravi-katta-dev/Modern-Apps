package com.vayunmathur.securemail.platform.crypto

import android.content.Context
import android.util.Base64
import com.vayunmathur.library.util.DatabaseHelper

/**
 * Custody for the SecureMail identity: the passphrase-wrapped PGP secret ring
 * plus the relay address token, both wrapped by an AndroidKeyStore key reusing
 * the repo's [DatabaseHelper] pattern (see backup's KeyManager).
 *
 * The PGP private key itself is S2K-wrapped by the passphrase before it ever
 * reaches this class, so two layers guard it: the S2K passphrase and the
 * Keystore-wrapped storage. Decrypt/sign operations additionally gate on the
 * biometric prompt (see MailViewModel.unlock) before [getSecretArmor] is read.
 */
class KeyManager(context: Context) : DatabaseHelper(context) {
    override val keyStoreAlias = "securemail_master_key"
    override val sharedPrefsName = "securemail_secure_prefs"
    override val passphraseKey = "encrypted_pgp_secret"
    override val ivKey = "pgp_secret_iv"

    private val tokenKey = "relay_address_token"
    private val addressKey = "own_address"

    fun hasIdentity(): Boolean = isKeyGenerated()

    /** Persists the passphrase-wrapped armored secret ring. */
    fun storeSecretArmor(armoredSecret: String) {
        storePassphrase(Base64.encodeToString(armoredSecret.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
    }

    /** Returns the armored secret ring. Call only after the biometric gate. */
    fun getSecretArmor(): String =
        String(Base64.decode(getPassphrase(), Base64.NO_WRAP), Charsets.UTF_8)

    fun storeRelayToken(token: String) {
        val prefs = context.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE)
        prefs.edit().putString(tokenKey, token).apply()
    }

    fun getRelayToken(): String? =
        context.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE)
            .getString(tokenKey, null)

    fun storeOwnAddress(address: String) {
        val prefs = context.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE)
        prefs.edit().putString(addressKey, address).apply()
    }

    fun getOwnAddress(): String? =
        context.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE)
            .getString(addressKey, null)

    fun clear() {
        deleteKey()
        val prefs = context.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE)
        prefs.edit().remove(tokenKey).remove(addressKey).apply()
    }
}
