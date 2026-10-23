package com.vayunmathur.uniflix.data

/** TMDB artwork via the self-hosted image proxy — no direct image.tmdb.org. Sizes: w185/w342 cards, w500 search, original hero. */
object TmdbImageUrl {
    private const val BASE = "https://api.maproj.org/api/tmdb/image/"

    fun poster(path: String?, size: String = "w342"): String? =
        path?.let { "$BASE$size$it" }

    fun backdrop(path: String?): String? =
        path?.let { "${BASE}original$it" }

    fun still(path: String?, size: String = "w185"): String? =
        path?.let { "$BASE$size$it" }
}
