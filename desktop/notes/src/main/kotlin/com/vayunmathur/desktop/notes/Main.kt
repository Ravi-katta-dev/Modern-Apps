package com.vayunmathur.desktop.notes

import com.vayunmathur.everysync.core.network.JvmRelayTransport
import com.vayunmathur.everysync.core.platform.MemorySecureStore
import java.io.File
import kotlin.io.encoding.Base64
import kotlinx.coroutines.runBlocking

/**
 * Desktop Notes JVM shell entry point (Phase 2b).
 *
 * Syncs the notes channel (`notes:<vaultId>`) and the todos channel
 * (`todos:<vaultId>`) once through the shared [NoteSync] driver, then prints
 * counts. The Windows todos-view ([TodoView.todosAsNotes]) renders inside the
 * Notes UI; writes flow back through the same todos channel.
 *
 * Usage: `:desktop:notes:run --args="<vaultId> <vaultKeyB64> [dataDir]"`
 *
 * Vault-key note: takes the epoch-0 vault key as base64 (same bytes the
 * Android `TokenStore` holds pre-wrap). Roster/epoch resolution is future
 * work — rotated shared vaults need it before this shell can read them.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        println("usage: desktop-notes <vaultId> <vaultKeyB64> [dataDir]")
        return
    }
    val vaultId = args[0]
    val vaultKey = runCatching { Base64.decode(args[1]) }.getOrNull()
    if (vaultKey == null || vaultKey.isEmpty()) {
        println("error: vaultKeyB64 is not valid base64")
        return
    }
    val dataDir = File(args.getOrElse(2) { defaultDataDir() }).apply { mkdirs() }
    val result = runBlocking {
        val transport = JvmRelayTransport(MemorySecureStore())
        if (!transport.init()) {
            println("error: relay unavailable (register failed)")
            return@runBlocking null
        }
        val sync = NoteSync(transport, dataDir, FileBlobStore(dataDir, vaultId))
        sync.syncOnce(vaultId, vaultKey)
    }
    if (result == null) return
    println("notes: ok=${result.notes.ok} local=${result.notes.localCount}")
    println("todos: ok=${result.todos.ok} local=${result.todos.localCount} view=${result.todosViewCount}")
    val blobs = result.blobs
    if (blobs == null) {
        println("blobs: no store wired (TODO)")
    } else {
        println("blobs: ok=${blobs.ok} pulled=${blobs.pulled} pushed=${blobs.pushed} deleted=${blobs.deleted}")
    }
}

private fun defaultDataDir(): String {
    val home = System.getProperty("user.home") ?: "."
    return File(File(home, ".everysync"), "notes").absolutePath
}
