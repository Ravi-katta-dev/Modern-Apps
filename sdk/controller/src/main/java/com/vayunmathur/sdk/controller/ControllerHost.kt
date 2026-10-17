package com.vayunmathur.sdk.controller

import com.vayunmathur.library.log.Log
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "ControllerHost"

/** JVM Base64, so the pairing path round-trips in a plain JVM test. */
private object HostBase64 {
    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun decode(text: String): ByteArray? = runCatching { Base64.getDecoder().decode(text) }.getOrNull()
}

/**
 * One connected phone, as the host tracks it.
 *
 * [lastSeq] is the high-water mark: anything at or below it is a replay and is ignored.
 * [inputTimestampsMs] is the rolling arrival record the rate limit is checked against.
 * [deviceKey] is the remembered secret from a code pairing, or what the phone just proved.
 */
private data class RemotePlayer(
    val slot: PlayerSlot,
    val socket: ControllerSocket,
    var keys: ControllerKeys? = null,
    val gate: ControllerInputGate = ControllerInputGate(),
    var deviceKey: ByteArray? = null,
)

/**
 * The TV side of the controller protocol: N player slots, join/leave, lobby snapshots, and the
 * 20-30 Hz input fan-in.
 *
 * Sessions are single-TV: one host per device, up to [ControllerContract.MAX_PLAYERS] phones plus
 * whatever gamepads the game merges in itself. A network drop frees the slot; a rejoin is a fresh
 * seat, never a resume, because input state ([lastSeq]) belongs to a connection, not a player.
 *
 * UDP later is a transport swap, not a redesign: [ControllerInput.seq], the replay window, and the
 * idempotent per-sample apply are already here, so only [ControllerSocket] changes underneath.
 *
 * Pairing reuses the `:cast:protocol` shape (sealed secret, transcript-bound HMAC proof, 6-digit
 * code with 3 attempts, remembered device keys) but with this SDK's own HKDF namespace, so a key
 * derived for casting can never be mistaken for one derived for controlling.
 */
class ControllerHost(
    private val gameId: String,
    private val hostId: String,
    private val hostName: String = android.os.Build.MODEL ?: "TV",
    private val providePublicBundle: () -> ByteArray?,
    private val unsealSecret: (sealed: ByteArray) -> ByteArray?,
    private val loadDeviceKey: (senderId: String) -> ByteArray?,
    private val saveDeviceKey: (senderId: String, key: ByteArray) -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _lobby = MutableStateFlow(LobbyState(gameId = gameId))
    /** The current lobby, broadcast to every phone whenever it changes. Replaces wholesale. */
    val lobby: StateFlow<LobbyState> = _lobby.asStateFlow()

    private val _inputs = MutableStateFlow<Map<String, ControllerInput>>(emptyMap())
    /**
     * The latest accepted sample per player.
     *
     * The game reads this on its own tick; samples are idempotent snapshots, so a missed frame
     * costs one tick of staleness and repairs itself with no sequence numbers to resynchronise.
     */
    val inputs: StateFlow<Map<String, ControllerInput>> = _inputs.asStateFlow()

    private val players = mutableMapOf<String, RemotePlayer>()
    private val pairingGates = mutableMapOf<ControllerSocket, ControllerPairingGate>()
    private val lock = Any()
    private var heartbeatStarted = false

    /** The code currently on screen for the phone asking, or null when none is pairing. */
    var pairingCode: String? = null
        private set

    /**
     * Accept a freshly-connected [socket]. Runs the handshake (HELLO → HOST_HELLO →
     * SEALED_SECRET → PAIR_REQUIRED → PAIR_PROOF → PAIR_OK) then the JOIN/INPUT loop, all on the
     * host's IO scope. Returns when the session ends; the seat is freed on the way out.
     */
    fun accept(socket: ControllerSocket) {
        scope.launch { runSession(socket) }
    }

    /**
     * A gamepad plugged into the TV takes a seat without a handshake.
     *
     * Indistinguishable downstream: the game reads [inputs] and [lobby] and never learns which
     * seats are network and which are local.
     */
    fun addLocalPlayer(playerId: String, displayName: String) {
        synchronized(lock) {
            if (players.size >= ControllerContract.MAX_PLAYERS) {
                Log.status(TAG, "lobby full - local player $displayName refused")
                return
            }
            if (players.containsKey(playerId)) return
            val slot = PlayerSlot(playerId, displayName, remote = false)
            players[playerId] = RemotePlayer(slot, ControllerSocket().also { it.close() })
            publishLobbyLocked()
        }
    }

    /** Feed one gamepad sample into the fan-in. No seq window: local input is trusted. */
    fun onLocalInput(input: ControllerInput) {
        synchronized(lock) {
            if (!players.containsKey(input.playerId)) return
        }
        _inputs.value = _inputs.value + (input.playerId to input)
    }

    fun removeLocalPlayer(playerId: String) {
        synchronized(lock) {
            players.remove(playerId) ?: return
            _inputs.value = _inputs.value - playerId
            publishLobbyLocked()
        }
    }

    /** Ask one phone to buzz. Fire and forget - a missed buzz is not worth a round trip. */
    fun rumble(playerId: String, rumble: RumbleFeedback) {
        val player = synchronized(lock) { players[playerId] } ?: return
        if (!player.slot.remote) return
        player.socket.send(ControllerRumble(rumble))
    }

    /** Lock joins and mark the lobby racing. New JOINs are refused until [endRace]. */
    fun startRace() {
        synchronized(lock) {
            _lobby.value = _lobby.value.copy(racing = true)
        }
        broadcastLobby()
    }

    fun endRace() {
        synchronized(lock) {
            _lobby.value = _lobby.value.copy(racing = false)
        }
        broadcastLobby()
    }

    fun close() {
        scope.cancel()
        synchronized(lock) {
            players.values.forEach { runCatching { it.socket.close() } }
            players.clear()
            _inputs.value = emptyMap()
            // A restarted host must heartbeat again: the loop died with the cancelled scope.
            heartbeatStarted = false
            publishLobbyLocked()
        }
    }

    private fun runSession(socket: ControllerSocket) {
        val transcript = ControllerTranscript()
        // HELLO
        val hello = socket.receive() ?: run { socket.close(); return }
        transcript.add(hello.body)
        val helloMsg = hello.message as? ControllerHello ?: run { socket.close(); return }
        if (helloMsg.version != ControllerContract.CONTROLLER_PROTOCOL_VERSION) {
            Log.status(TAG, "refusing version ${helloMsg.version} from ${helloMsg.senderName}")
            socket.close()
            return
        }
        // HOST_HELLO
        val bundle = providePublicBundle() ?: run { socket.close(); return }
        val hostHello = ControllerHostHello(
            hostName = hostName,
            hostId = hostId,
            publicBundle = HostBase64.encode(bundle),
            gameId = gameId,
        )
        val hostHelloBody = socket.send(hostHello) ?: run { socket.close(); return }
        transcript.add(hostHelloBody)
        // SEALED_SECRET
        val sealed = socket.receive() ?: run { socket.close(); return }
        transcript.add(sealed.body)
        val sealedMsg = sealed.message as? ControllerSealedSecret ?: run { socket.close(); return }
        val secretBytes = HostBase64.decode(sealedMsg.sealed)?.let { unsealSecret(it) }
            ?: run { socket.close(); return }
        val keys = ControllerKeys.of(secretBytes)
        socket.codec.useSessionKey(keys.control)
        // PAIR_REQUIRED
        val remembered = loadDeviceKey(helloMsg.senderId)
        val wantsCode = remembered == null || !helloMsg.paired
        val gate = ControllerPairingGate()
        synchronized(lock) { pairingGates[socket] = gate }
        if (wantsCode) pairingCode = gate.code
        socket.send(ControllerPairRequired(code = wantsCode)) ?: run {
            finishPairing(socket); socket.close(); return
        }
        // PAIR_PROOF
        val proof = socket.receive() ?: run { finishPairing(socket); socket.close(); return }
        val proofMsg = proof.message as? ControllerPairProof ?: run {
            finishPairing(socket); socket.close(); return
        }
        val proofBytes = HostBase64.decode(proofMsg.proof) ?: run {
            finishPairing(socket); socket.close(); return
        }
        val transcriptValue = transcript.value()
        val result = if (wantsCode) {
            gate.verifyCode(keys, transcriptValue, proofBytes)
        } else {
            // The no-code path is reached only when the TV holds a key for this phone
            // (`wantsCode` is `remembered == null || !paired`), so `remembered` is present.
            // Checked rather than asserted: a null here is a confused host, and failing closed
            // beats crashing the session thread.
            val rememberedKey = remembered
                ?: return run {
                    socket.send(ControllerPairFailed(ControllerPairCode.MAX_ATTEMPTS))
                    finishPairing(socket)
                    socket.close()
                }
            gate.verifyDevice(keys, transcriptValue, rememberedKey, proofBytes)
        }
        when (result) {
            is ControllerPairResult.Ok -> {
                result.deviceKey?.let { saveDeviceKey(helloMsg.senderId, it) }
                socket.send(ControllerPairOk(result.deviceKey?.let { HostBase64.encode(it) }))
                    ?: run { finishPairing(socket); socket.close(); return }
                finishPairing(socket)
                runJoined(socket, helloMsg, keys, result.deviceKey ?: remembered)
            }
            is ControllerPairResult.Wrong -> {
                socket.send(ControllerPairFailed(result.attemptsLeft))
                finishPairing(socket)
                socket.close()
            }
        }
    }

    private fun finishPairing(socket: ControllerSocket) {
        synchronized(lock) { pairingGates.remove(socket) }
        pairingCode = null
    }

    private fun runJoined(
        socket: ControllerSocket,
        hello: ControllerHello,
        keys: ControllerKeys,
        deviceKey: ByteArray?,
    ) {
        // JOIN
        val join = socket.receive() ?: run { socket.close(); return }
        val joinMsg = join.message as? ControllerJoin ?: run { socket.close(); return }
        val playerId = hello.senderId
        val admitted = synchronized(lock) {
            if (_lobby.value.racing || players.size >= ControllerContract.MAX_PLAYERS) false
            else {
                val slot = PlayerSlot(playerId, joinMsg.displayName, remote = true)
                players[playerId] = RemotePlayer(slot, socket, keys, deviceKey = deviceKey)
                publishLobbyLocked()
                true
            }
        }
        if (!admitted) {
            socket.send(ControllerBye("lobby full or racing"))
            socket.close()
            return
        }
        broadcastLobby()
        startHeartbeat()
        try {
            while (true) {
                val received = socket.receive() ?: break
                when (val msg = received.message) {
                    is ControllerInputFrame -> onRemoteInput(playerId, msg.input)
                    is ControllerLeave -> break
                    is ControllerPing -> socket.send(ControllerPing)
                    is ControllerBye -> break
                    else -> Log.status(TAG, "unexpected ${msg::class.simpleName} from $playerId")
                }
            }
        } finally {
            synchronized(lock) {
                players.remove(playerId)
                _inputs.value = _inputs.value - playerId
                publishLobbyLocked()
            }
            broadcastLobby()
            socket.close()
        }
    }

    /**
     * Apply one network sample: replay-window, then rate-limit, then publish.
     *
     * Both checks live in the player's [ControllerInputGate] and are per-player, so one faulty or
     * hostile client cannot crowd out the rest of the grid. Drops rather than applies over the
     * limit.
     */
    private fun onRemoteInput(playerId: String, input: ControllerInput) {
        val nowMs = android.os.SystemClock.uptimeMillis()
        val accepted = synchronized(lock) {
            val player = players[playerId] ?: return
            player.gate.offer(input.seq, nowMs)
        }
        if (accepted) _inputs.value = _inputs.value + (playerId to input)
    }

    private fun publishLobbyLocked() {
        _lobby.value = _lobby.value.copy(slots = players.values.map { it.slot })
    }

    /**
     * The session heartbeat both ends depend on.
     *
     * A read timeout is the only liveness check either end has, and it cannot tell a quiet lobby
     * from a dead one. Once the first player joins, every PING interval the host says PING to every
     * phone and the phones echo it straight back - so a session with no input and no lobby change
     * still produces traffic well inside the 60 s read bound, even while a human reads six digits.
     * Started once; a second join does not stack a second loop.
     */
    private fun startHeartbeat() {
        synchronized(lock) {
            if (heartbeatStarted) return
            heartbeatStarted = true
        }
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(ControllerContract.PING_INTERVAL_MS)
                synchronized(lock) {
                    players.values.forEach { it.socket.send(ControllerPing) }
                }
            }
        }
    }

    private fun broadcastLobby() {
        val snapshot = _lobby.value
        synchronized(lock) {
            players.values.forEach { it.socket.send(ControllerLobby(snapshot)) }
        }
    }
}
