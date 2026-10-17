package com.vayunmathur.sdk.controller

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update

private const val TAG = "ControllerDiscovery"

/** Friendly name, e.g. "Living Room TV". */
private const val TXT_FRIENDLY_NAME = "fn"

/** The protocol version the host speaks. */
private const val TXT_PROTOCOL_VERSION = "pv"

/** The host's own stable id. Stable across renames, unlike the instance name. */
private const val TXT_ID = "id"

/** The game this lobby is for, e.g. "kart" - informational, so a phone can label what it found. */
private const val TXT_GAME_ID = "gameId"

/** One TV hosting a controller lobby, as resolved from mDNS. */
data class ControllerHostInfo(
    val id: String,
    val instanceName: String,
    val friendlyName: String,
    val host: String,
    val port: Int,
    val protocolVersion: Int,
    val gameId: String,
)

/**
 * mDNS discovery of controller hosts on the LAN.
 *
 * Browse-only - nothing is advertised, because a phone is not itself a host. The shape is
 * `:cast`'s `CastDiscoveryManager` retargeted at [ControllerContract.GAMEPAD_SERVICE_TYPE]:
 * the `callbackFlow` + `DiscoveryListener` pairing, `_hosts.update {}` because resolve callbacks
 * land on several threads, and [localNetworkBlocked] - on Android 16 a missing
 * `ACCESS_LOCAL_NETWORK` grant makes `discoverServices` throw `SecurityException`, and that is
 * otherwise indistinguishable from an empty network.
 */
class ControllerDiscovery(context: Context) {

    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _hosts = MutableStateFlow<List<ControllerHostInfo>>(emptyList())
    val hosts: StateFlow<List<ControllerHostInfo>> = _hosts.asStateFlow()

    /** True when the platform refused the browse, not when the network is simply empty. */
    private val _localNetworkBlocked = MutableStateFlow(false)
    val localNetworkBlocked: StateFlow<Boolean> = _localNetworkBlocked.asStateFlow()

    /**
     * Browse [ControllerContract.GAMEPAD_SERVICE_TYPE] and emit each resolved host.
     *
     * Cancelling the collection stops the browse. Hosts also accumulate in [hosts], which is what
     * the list observes, so a caller that only wants the state can collect and drop.
     */
    fun discover(): Flow<ControllerHostInfo> = callbackFlow {
        val manager = nsdManager ?: run {
            Log.status(TAG, "NsdManager unavailable - cannot browse for hosts")
            close()
            return@callbackFlow
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                _localNetworkBlocked.value = false
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                try {
                    manager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.status(TAG, "resolve failed for ${info.serviceName}: $errorCode")
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = toHostInfo(info) ?: return
                            trySend(host)
                            // update, not value =: resolve callbacks arrive on several threads
                            // and a read-modify-write would drop hosts found at once.
                            _hosts.update { merge(it, host) }
                        }
                    })
                } catch (e: SecurityException) {
                    Log.status(TAG, "resolveService threw for ${service.serviceName}", e)
                } catch (e: IllegalArgumentException) {
                    Log.status(TAG, "resolveService threw for ${service.serviceName}", e)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                // Only the instance name is available here, so match on it rather than on the
                // UUID; the two are one-to-one for the lifetime of a registration.
                val name = service.serviceName ?: return
                _hosts.update { hosts -> hosts.filterNot { it.instanceName == name } }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.status(TAG, "startDiscovery failed: $errorCode")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.status(TAG, "stopDiscovery failed: $errorCode")
            }
        }
        discoveryListener = listener
        try {
            manager.discoverServices(
                ControllerContract.GAMEPAD_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener,
            )
        } catch (e: SecurityException) {
            // Android 16+ Local Network Protections. Nothing is wrong with the request; the
            // OS blocks mDNS outright until ACCESS_LOCAL_NETWORK is granted.
            Log.error(TAG, "mDNS browse blocked - ACCESS_LOCAL_NETWORK not granted", e)
            _localNetworkBlocked.value = true
            discoveryListener = null
            close()
            return@callbackFlow
        } catch (e: IllegalArgumentException) {
            Log.status(TAG, "discoverServices threw", e)
            discoveryListener = null
            close(e)
            return@callbackFlow
        }
        awaitClose {
            try {
                discoveryListener?.let { manager.stopServiceDiscovery(it) }
            } catch (_: IllegalArgumentException) {
            } catch (_: SecurityException) {
            }
            discoveryListener = null
        }
    }

    fun clear() {
        _hosts.value = emptyList()
    }

    private fun textAttribute(info: NsdServiceInfo, key: String): String? =
        runCatching { info.attributes[key]?.toString(Charsets.UTF_8) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /**
     * Turn a resolved record into a [ControllerHostInfo], or null when it is not usable.
     *
     * A host and a port are both mandatory - the TV binds an ephemeral control port, so there is no
     * sensible default to fall back to. The name has one, because a TV with an odd TXT record is
     * still joinable.
     */
    private fun toHostInfo(info: NsdServiceInfo): ControllerHostInfo? {
        val host = info.host?.hostAddress ?: return null
        val instance = info.serviceName ?: return null
        val port = info.port.takeIf { it > 0 } ?: return null
        return ControllerHostInfo(
            id = textAttribute(info, TXT_ID) ?: instance,
            instanceName = instance,
            friendlyName = textAttribute(info, TXT_FRIENDLY_NAME) ?: instance,
            host = host,
            port = port,
            protocolVersion = textAttribute(info, TXT_PROTOCOL_VERSION)?.toIntOrNull() ?: 0,
            gameId = textAttribute(info, TXT_GAME_ID).orEmpty(),
        )
    }

    /**
     * Replace by [ControllerHostInfo.id] rather than append.
     *
     * A host re-announces itself whenever its record changes, and every announcement resolves, so
     * appending would show the same TV three times.
     */
    private fun merge(current: List<ControllerHostInfo>, host: ControllerHostInfo): List<ControllerHostInfo> {
        val index = current.indexOfFirst { it.id == host.id }
        if (index < 0) return current + host
        return current.toMutableList().apply { this[index] = host }
    }
}
