package com.vayunmathur.desktop.passwords

import java.io.File
import kotlin.io.encoding.Base64
import kotlinx.coroutines.runBlocking

/**
 * Desktop Passwords JVM shell entry point (Phase 2a).
 *
 * Syncs the passwords channel (`passwords:<vaultId>`) once through the
 * shared [PasswordSync] driver, then prints counts. No Compose UI yet — the
 * UI lands after the core proves.
 *
 * Usage: `:desktop:passwords:run --args="<vaultId> [vaultKeyB64] [dataDir]"`
 *
 * The vault key resolves as: explicit arg → `vault-<vaultId>.key` in the data
 * dir → fresh random key (saved for next run). Roster/epoch resolution is
 * future work — rotated shared vaults need it before this shell can read them.
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("usage: desktop-passwords <vaultId> [vaultKeyB64] [dataDir]")
        return
    }
    val vaultId = args[0]
    val dataDir = File(args.getOrElse(2) { defaultDataDir() }).apply { mkdirs() }
    val vaultKey = resolveVaultKey(args.getOrNull(1), dataDir, vaultId)
    if (vaultKey == null) {
        println("error: vaultKeyB64 is not valid base64")
        return
    }
    val result = runBlocking {
        val transport = PasswordSync.defaultTransport(FileSecureStore(dataDir))
        if (!transport.init()) {
            println("error: relay unavailable (register failed)")
            return@runBlocking null
        }
        PasswordSync(transport, dataDir).syncOnce(vaultId, vaultKey)
    }
    if (result == null) return
    println("passwords: ok=${result.ok} passwords=${result.passwords} passkeys=${result.passkeys}")
}

private fun resolveVaultKey(arg: String?, dataDir: File, vaultId: String): ByteArray? {
    if (arg != null) {
        if (arg.isBlank()) return null
        return runCatching { Base64.decode(arg) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
    VaultKeyFile.load(dataDir, vaultId)?.let { return it }
    val fresh = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
    VaultKeyFile.save(dataDir, vaultId, fresh)
    println("note: generated fresh vault key at ${VaultKeyFile.fileFor(dataDir, vaultId)}")
    return fresh
}

private fun defaultDataDir(): String {
    val home = System.getProperty("user.home") ?: "."
    return File(File(home, ".everysync"), "passwords").absolutePath
}
