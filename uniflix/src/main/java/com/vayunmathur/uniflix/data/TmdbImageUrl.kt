package com.vayunmathur.uniflix.data

/** Direct TMDB image CDN URLs — no proxy, no key. Sizes: w185/w342 cards, w500 search, original hero. */
object TmdbImageUrl {
    private const val BASE = "https://image.tmdb.org/t/p/"

    fun poster(path: String?, size: String = "w342"): String? =
        path?.let { "$BASE$size$it" }

    fun backdrop(path: String?): String? =
        path?.let { "${BASE}original$it" }

    fun still(path: String?, size: String = "w185"): String? =
        path?.let { "$BASE$size$it" }
}
