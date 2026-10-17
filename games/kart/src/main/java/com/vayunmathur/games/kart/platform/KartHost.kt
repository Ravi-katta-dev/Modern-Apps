package com.vayunmathur.games.kart.platform

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import com.vayunmathur.library.log.Log
import com.vayunmathur.sdk.controller.ControllerAdvertiser
import com.vayunmathur.sdk.controller.ControllerHost
import com.vayunmathur.sdk.controller.ControllerInput
import com.vayunmathur.sdk.controller.ControllerSocket
import com.vayunmathur.sdk.controller.LobbyState
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "KartHost"
private const val PREFS = "kart_host"
private const val KEY_HOST_ID = "host_id"

/**
 * The TV-side lobby owner: advertises `_gamepad._tcp`, accepts phones, and exposes the
 * [ControllerHost] fan-in the race loop reads.
 *
 * Key material is injectable: [publicBundle] is the ML-KEM+ML-DSA bundle from `:library:e2ee-p2p`
 * (`PqcIdentity.publicBundle`), [unsealSecret] opens a sealed session secret with the KEM private
 * half. Device keys persist in private prefs keyed by phone id. The host id is a stable UUID minted
 * once, so re-pairing survives restarts.
 *
 * PQC stays behind the lambdas deliberately: this module never touches the native library
 * directly, and tests construct a `KartHost` with fake keys.
 */
class KartHost(
    private val context: Context,
    private val publicBundle: () -> ByteArray?,
    private val unsealSecret: (ByteArray) -> ByteArray?,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val hostId: String = prefs.getString(KEY_HOST_ID, null) ?: run {
        val fresh = java.util.UUID.randomUUID().toString()
        prefs.edit { putString(KEY_HOST_ID, fresh) }
        fresh
    }

    val host = ControllerHost(
        gameId = "kart",
        hostId = hostId,
        providePublicBundle = publicBundle,
        unsealSecret = unsealSecret,
        loadDeviceKey = ::loadDeviceKey,
        saveDeviceKey = ::saveDeviceKey,
    )

    val lobby: StateFlow<LobbyState> = host.lobby
    val inputs: StateFlow<Map<String, ControllerInput>> = host.inputs

    private val advertiser = ControllerAdvertiser(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null

    /** The pair code on screen, if a phone is mid-handshake. */
    val pairingCode: String? get() = host.pairingCode

    /**
     * Start advertising and accepting. Safe to call repeatedly; restarts the accept loop rather
     * than stacking a second one.
     */
    fun start() {
        scope.launch { acceptLoop() }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        advertiser.unadvertise()
        host.close()
        scope.cancel()
    }

    private fun acceptLoop() {
        val server = try {
            ServerSocket(0).also { this.server = it }
        } catch (e: Exception) {
            Log.status(TAG, "cannot bind a lobby port", e)
            return
        }
        advertiser.advertise(
            friendlyName = android.os.Build.MODEL ?: "TV",
            deviceId = hostId,
            port = server.localPort,
            gameId = "kart",
        )
        Log.status(TAG, "lobby open on port ${server.localPort}")
        while (true) {
            val accepted = try {
                server.accept()
            } catch (_: Exception) {
                return
            }
            val socket = ControllerSocket()
            socket.attach(accepted)
            host.accept(socket)
        }
    }

    private fun loadDeviceKey(senderId: String): ByteArray? =
        prefs.getString(deviceKeyOf(senderId), null)?.let {
            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
        }

    private fun saveDeviceKey(senderId: String, key: ByteArray) {
        prefs.edit { putString(deviceKeyOf(senderId), Base64.encodeToString(key, Base64.NO_WRAP)) }
    }

    private fun deviceKeyOf(senderId: String): String = "device_$senderId"
}
