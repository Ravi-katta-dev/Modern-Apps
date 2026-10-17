// PACKAGE STRUCTURE EXCEPTION (JNI): FQN frozen for native RegisterNatives/symbol mangling
// NOTE: file lives under platform/ but package is ...kart.util to match the native symbol
@file:Suppress("InvalidPackageDeclaration")
package com.vayunmathur.games.kart.util

import com.vayunmathur.library.log.Log

/**
 * The engine bridge, shaped like `VoxelsNative.onMoveInput`.
 *
 * Today there is no native library: every call is a no-op behind [isAvailable] = false, and the
 * game runs on [com.vayunmathur.games.kart.domain.PlaceholderRace] instead. The signatures below
 * are the contract the Rust engine will implement in Phase 4b, so the Kotlin side never changes
 * shape when native lands - only this object's body does.
 */
@Suppress("TooManyFunctions")
object KartNative {
    /** False until `libkart_engine` ships. The game checks this once per surface, like voxels. */
    val isAvailable: Boolean = try {
        System.loadLibrary("kart_engine")
        Log.status("KartNative", "libkart_engine loaded")
        true
    } catch (t: Throwable) {
        Log.status("KartNative", "libkart_engine absent - running the placeholder race")
        false
    }

    /** One player's control for this tick: throttle/steering/brake normalized, drift/item edges. */
    fun onDriveInput(player: Int, throttle: Float, steering: Float, brake: Float) {
        if (!isAvailable) return
        nativeOnDriveInput(player, throttle, steering, brake)
    }

    fun setDrift(player: Int, held: Boolean) {
        if (!isAvailable) return
        nativeSetDrift(player, held)
    }

    fun useItem(player: Int) {
        if (!isAvailable) return
        nativeUseItem(player)
    }

    /** Snapshot for the HUD/standings: JSON `[{player,lap,progress,place}]`. */
    fun getStandingsJson(): String = if (isAvailable) nativeGetStandingsJson() else "[]"

    private external fun nativeOnDriveInput(player: Int, throttle: Float, steering: Float, brake: Float)
    private external fun nativeSetDrift(player: Int, held: Boolean)
    private external fun nativeUseItem(player: Int)
    private external fun nativeGetStandingsJson(): String
}
