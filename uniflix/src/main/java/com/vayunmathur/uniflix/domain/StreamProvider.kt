package com.vayunmathur.uniflix.domain

/**
 * Resolves a catalog id to a playable source: HLS URL, title, and subtitles.
 *
 * Deliberately abstract: catalog browsing (TMDB) is implemented in this module,
 * but stream resolution is not. Subclass this with your own source later — the
 * player consumes only [VideoSource], never any provider-specific grant shape.
 */
abstract class StreamProvider {
    /** Human label shown in the server picker (e.g. "Nebula"). */
    abstract val name: String

    /** Whether this provider advertises 4K renditions. */
    abstract val supports4k: Boolean

    /**
     * Resolve [tmdbId] (+ season/episode for series) to a playable [VideoSource].
     * Throw on failure; the Watch screen surfaces the message.
     *
     * [server] pins resolution to one server (from [servers]); null lets the
     * provider pick its default.
     */
    abstract suspend fun resolve(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
        server: String? = null,
    ): VideoSource

    /** Extra providers offered as fallback in the picker, if any. */
    open suspend fun alternates(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int = 0,
        episode: Int = 0,
    ): List<VideoSource> = emptyList()

    /** Healthy server names, for the in-player server picker. Empty when unsupported. */
    open suspend fun servers(): List<String> = emptyList()
}
