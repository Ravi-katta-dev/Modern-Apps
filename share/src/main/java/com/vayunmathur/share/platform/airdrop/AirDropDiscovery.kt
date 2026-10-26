package com.vayunmathur.share.platform.airdrop

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.ParcelUuid
import android.os.ext.SdkExtensions
import com.vayunmathur.library.log.Log
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.Executor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

private const val TAG = "AirDropDiscovery"

const val AIRDROP_SERVICE_TYPE = "_airdrop._tcp"

/**
 * The `flags` TXT value Mosey advertises: its Phenotype default 1161 (`dlh.g()`), rendered as
 * uppercase hex (`cen`).
 */
private const val AIRDROP_TXT_FLAGS = "489"

private const val NSD_NETWORK_EXTENSION = 3

/** A resolved `_airdrop._tcp` record, before `/Discover` has told us its name. */
data class AirDropPeer(
    val serviceName: String,
    val address: InetAddress,
    val port: Int,
)

/**
 * mDNS for AirDrop, scoped to whichever network the link runs on, plus the BLE advertisement
 * that wakes Apple receivers' AWDL so they answer it.
 */
class AirDropDiscovery(private val context: Context) {

    private val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)
    private var registration: NsdManager.RegistrationListener? = null
    private val resolveLock = Mutex()
    private val mainExecutor: Executor get() = context.mainExecutor

    /** Instance name we advertise under, so a browse can drop our own record. */
    @Volatile
    var ownServiceName: String? = null
        private set

    // Broad catch is deliberate: NSD throws undocumented RuntimeExceptions on some stacks.
    @Suppress("TooGenericExceptionCaught")
    fun advertise(serviceName: String, port: Int, network: Network?) {
        val mgr = nsd ?: return
        unadvertise()
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = AIRDROP_SERVICE_TYPE
            this.port = port
            setAttribute("flags", AIRDROP_TXT_FLAGS)
            if (network != null && canScopeToNetwork()) this.network = network
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                ownServiceName = info.serviceName
                Log.status(TAG, "advertising $AIRDROP_SERVICE_TYPE as ${info.serviceName} on $port (network=$network)")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.status(TAG, "AirDrop registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        ownServiceName = serviceName
        registration = listener
        try {
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.status(TAG, "registerService failed", e)
            registration = null
        }
    }

    fun unadvertise() {
        val listener = registration ?: return
        runCatching { nsd?.unregisterService(listener) }
        registration = null
        ownServiceName = null
    }

    /** `NsdServiceInfo.setNetwork` arrived in the T extension SDK 3. */
    private fun canScopeToNetwork(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= NSD_NETWORK_EXTENSION

    /** Browses `_airdrop._tcp` on [network] and emits each record once it resolves. */
    // Broad catch is deliberate: NSD throws undocumented RuntimeExceptions on some stacks.
    @Suppress("TooGenericExceptionCaught")
    fun browse(network: Network?): Flow<AirDropPeer> = callbackFlow {
        val mgr = nsd ?: run {
            close()
            return@callbackFlow
        }
        val producer = this
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.dev(TAG, "browsing $serviceType on $network")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName == ownServiceName) return
                producer.launch {
                    // The pre-U resolver handles one request at a time and fails the rest outright.
                    val resolved = resolveLock.withLock { resolve(mgr, info) } ?: return@launch
                    val host = resolved.host ?: return@launch
                    if (resolved.port > 0) trySend(AirDropPeer(resolved.serviceName, host, resolved.port))
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.status(TAG, "AirDrop browse failed: $errorCode")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        try {
            if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                mgr.discoverServices(AIRDROP_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, network, mainExecutor, listener)
            } else {
                mgr.discoverServices(AIRDROP_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        } catch (e: Exception) {
            Log.status(TAG, "discoverServices failed", e)
            close(e)
            return@callbackFlow
        }
        awaitClose { runCatching { mgr.stopServiceDiscovery(listener) } }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(mgr: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.dev(TAG, "resolve ${info.serviceName} failed: $errorCode")
                    if (cont.isActive) cont.resume(null)
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(info)
                }
            }
            runCatching { mgr.resolveService(info, listener) }.onFailure { if (cont.isActive) cont.resume(null) }
        }
}

/**
 * The BLE advertisement a Mosey sender runs while it looks for receivers (`cce.H`): Apple
 * manufacturer data of type `0x05` (AirDrop), version 1, eight random bytes where Apple puts
 * truncated contact hashes, plus Google's `0xFCF1` service UUID so other Mosey receivers can
 * tell it is not an Apple sender. Apple devices wake their AirDrop listener on seeing it.
 */
class AirDropBleAdvertiser(private val context: Context) {

    private var callback: AdvertiseCallback? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (callback != null) return
        val advertiser = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser ?: run {
            Log.status(TAG, "no BLE advertiser; Apple receivers may not wake up")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .also(::setNonResolvableAddress)
            .build()
        val data = AdvertiseData.Builder()
            .addManufacturerData(APPLE_COMPANY_ID, payload())
            .addServiceUuid(MOSEY_SERVICE_UUID)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                Log.status(TAG, "AirDrop BLE advertise failed: $errorCode")
            }
        }
        callback = cb
        runCatching { advertiser.startAdvertising(settings, data, cb) }
            .onFailure { Log.status(TAG, "startAdvertising threw", it); callback = null }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val cb = callback ?: return
        callback = null
        runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb)
        }
    }

    /** `setOwnAddressType(ADDRESS_TYPE_RANDOM_NON_RESOLVABLE)` is @SystemApi; best effort. */
    private fun setNonResolvableAddress(builder: AdvertiseSettings.Builder) {
        runCatching {
            AdvertiseSettings.Builder::class.java
                .getMethod("setOwnAddressType", Int::class.javaPrimitiveType)
                .invoke(builder, ADDRESS_TYPE_RANDOM_NON_RESOLVABLE)
        }
    }

    private fun payload(): ByteArray = ByteArray(PAYLOAD_SIZE).also {
        it[0] = AIRDROP_TYPE
        it[1] = AIRDROP_LENGTH
        it[VERSION_INDEX] = 1
        val random = ByteArray(RANDOM_LENGTH).also(SecureRandom()::nextBytes)
        random.copyInto(it, RANDOM_OFFSET)
    }

    private companion object {
        const val APPLE_COMPANY_ID = 0x004C
        val MOSEY_SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("0000fcf1-0000-1000-8000-00805f9b34fb")
        const val ADDRESS_TYPE_RANDOM_NON_RESOLVABLE = 2
        const val PAYLOAD_SIZE = 20
        const val AIRDROP_TYPE: Byte = 0x05
        const val AIRDROP_LENGTH: Byte = 0x12
        const val VERSION_INDEX = 10
        const val RANDOM_OFFSET = 11
        const val RANDOM_LENGTH = 8
    }
}
