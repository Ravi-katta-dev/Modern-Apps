package com.vayunmathur.maps.ui

/**
 * The safety overlay's `.mamaps` contract: the `safety` source layer and the kinds it carries.
 * Drawn by the renderer (`safety-points` style layer) when `LayerOptions.safety` is on.
 */
object SafetyLayersSource {
    const val SOURCE_LAYER: String = "safety"
    const val LAYER_ID: String = "safety-points"

    const val KIND_SPEED_CAMERA = "speed_camera"
    const val KIND_ALPR = "alpr"
    const val KIND_SURVEILLANCE = "surveillance"
    const val KIND_STOP_SIGN = "stop_sign"
    const val KIND_TRAFFIC_SIGNALS = "traffic_signals"

    val available: Boolean get() = true
}
