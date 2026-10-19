package com.vayunmathur.games.hub.ui.yuu

/**
 * Stable surface ids for the multi-renderer avatar engine. Kotlin owns
 * identity: each hero surface gets a fixed id so the engine can route
 * config/pose/lifecycle per surface. Grid cells (Phase E) derive ids from
 * their Yuu/variant keys instead.
 */
const val EDITOR_SURFACE_ID = 1
const val DASHBOARD_SURFACE_ID = 2
const val PROFILE_SURFACE_ID = 3

/**
 * Live-surface budget for the 3D grid expansion. Each live surface holds a
 * swapchain + render thread + up to ~4MB of GPU buffers; beyond
 * [MAX_LIVE_SURFACES] cells fall back to 2D thumbnails. Heroes (ids 1–3)
 * always win eviction priority and are never counted here.
 */
object YuuSurfaceIds {
    /** Max concurrent live 3D grid cells (tune on-device; start conservative). */
    const val MAX_LIVE_SURFACES = 8

    /** Base for Yuu-derived cell ids (heroes occupy 1–3). */
    private const val CELL_BASE = 1000

    /** Base for editor variant-cell ids (per-panel base + variant index). */
    private const val VARIANT_BASE = 100000

    /** Stable cell id for a Yuu row id. */
    fun forYuu(yuuId: Long): Int = CELL_BASE + (yuuId % 100000).toInt()

    /** Stable cell id for an editor variant (panel base + variant index). */
    fun forVariant(panelBase: Int, variant: Int): Int = VARIANT_BASE + panelBase * 100 + variant

    /**
     * Whether a cell id currently holds a live surface. Budget gate: the
     * lowest ids win so the first cells render 3D and the rest stay 2D.
     * Called during composition — pure arithmetic, no allocation.
     */
    fun isLive(surfaceId: Int): Boolean = when {
        surfaceId >= VARIANT_BASE -> surfaceId - VARIANT_BASE < MAX_LIVE_SURFACES
        surfaceId >= CELL_BASE -> surfaceId - CELL_BASE < MAX_LIVE_SURFACES
        else -> true
    }
}
