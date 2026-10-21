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
 * [MAX_LIVE_SURFACES] cells show the placeholder. Heroes (ids 1–3)
 * always win eviction priority and are never counted here.
 */
object YuuSurfaceIds {
    /**
     * Max live 3D cells per panel grid (tune on-device; see below).
     *
     * Only the active panel composes at once (VariantGrid is non-lazy inside
     * a `when(category)`), and the largest single VariantGrid is 12 cells
     * (Hair/Eyes/Mouth). 12 therefore makes every single-grid panel fully
     * live; Extras sub-grids are at most 6 live cells each. Worst-case
     * concurrent live = hero preview (exempt id, never counted) + one
     * panel's grid (12, or 22 across the five Extras sub-grids) at ~4MB
     * GPU buffers each — accepted as a transient editor cost with the
     * placeholder/"…" backstop for anything over cap. Heroes (ids 1-3)
     * always win eviction priority and are never counted here.
     */
    const val MAX_LIVE_SURFACES = 12

    /** Base for Yuu-derived cell ids (heroes occupy 1–3). */
    private const val CELL_BASE = 1000

    /** Base for editor variant-cell ids (per-panel base + variant index). */
    private const val VARIANT_BASE = 100000

    /** Id stride per panel in [forVariant]: variant index lives in id % 100. */
    private const val VARIANT_STRIDE = 100

    /** Stable cell id for a Yuu row id. */
    fun forYuu(yuuId: Long): Int = CELL_BASE + (yuuId % 100000).toInt()

    /** Stable cell id for an editor variant (panel base + variant index). */
    fun forVariant(panelBase: Int, variant: Int): Int = VARIANT_BASE + panelBase * 100 + variant

    /**
     * Whether a cell id currently holds a live surface. Budget gate: the
     * lowest ids win so the first cells render 3D and the rest show the
     * placeholder. Called during composition — pure arithmetic, no allocation.
     *
     * Panel-aware: [forVariant] packs panelBase into the id
     * (VARIANT_BASE + panelBase * 100 + variant), so the gate compares the
     * within-panel variant index (id % 100), letting each panel's first
     * [MAX_LIVE_SURFACES] cells go live. (The old check compared the raw
     * panelBase-shifted offset, so only Face-panel base 0 ever passed.)
     */
    fun isLive(surfaceId: Int): Boolean = when {
        surfaceId >= VARIANT_BASE -> (surfaceId - VARIANT_BASE) % VARIANT_STRIDE < MAX_LIVE_SURFACES
        surfaceId >= CELL_BASE -> surfaceId - CELL_BASE < MAX_LIVE_SURFACES
        else -> true
    }
}
