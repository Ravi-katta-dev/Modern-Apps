package com.vayunmathur.uniflix.data

import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.uniflix.domain.Episode
import com.vayunmathur.uniflix.domain.MediaDetail
import com.vayunmathur.uniflix.domain.MediaItem
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.domain.SeasonSummary
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Public TMDB v3 catalog client. Key is injected per call, never stored here. */
class TmdbApi {
    private val json = Json { ignoreUnknownKeys = true }

    private fun url(path: String, apiKey: String, extra: String = ""): String {
        val params = "language=en-US&include_adult=false&api_key=$apiKey"
        val suffix = if (extra.isEmpty()) "" else "&$extra"
        return "$BASE$path?$params$suffix"
    }

    private suspend fun getItems(requestUrl: String): List<MediaItem> {
        val resp = NetworkClient.execute(requestUrl)
        if (!resp.isSuccess) error("TMDB request failed: ${resp.status}")
        return json.decodeFromString(TmdbList.serializer(), resp.text).results.map { it.toMediaItem() }
    }

    suspend fun trendingMovies(apiKey: String): List<MediaItem> =
        getItems(url("/trending/movie/day", apiKey))

    suspend fun trendingTv(apiKey: String): List<MediaItem> =
        getItems(url("/trending/tv/day", apiKey))

    suspend fun topRatedMovies(apiKey: String): List<MediaItem> =
        getItems(url("/movie/top_rated", apiKey, "page=1"))

    suspend fun topRatedTv(apiKey: String): List<MediaItem> =
        getItems(url("/tv/top_rated", apiKey, "page=1"))

    suspend fun discoverMovies(apiKey: String): List<MediaItem> =
        getItems(url("/discover/movie", apiKey, "page=1"))

    suspend fun discoverTv(apiKey: String): List<MediaItem> =
        getItems(url("/discover/tv", apiKey, "page=1"))

    suspend fun search(apiKey: String, query: String): List<MediaItem> {
        val encoded = java.net.URLEncoder.encode(query, Charsets.UTF_8.name())
        return getItems(url("/search/multi", apiKey, "query=$encoded&page=1"))
    }

    suspend fun movieDetail(apiKey: String, tmdbId: Int): MediaDetail {
        val requestUrl = url(
            "/movie/$tmdbId",
            apiKey,
            "append_to_response=release_dates,images,credits,videos,external_ids",
        )
        val resp = NetworkClient.execute(requestUrl)
        if (!resp.isSuccess) error("TMDB request failed: ${resp.status}")
        val dto = json.decodeFromString(TmdbMovieDetail.serializer(), resp.text)
        val recs = runCatching { movieRecommendations(apiKey, tmdbId) }.getOrDefault(emptyList())
        return MediaDetail(
            tmdbId = dto.id,
            mediaType = MediaType.MOVIE,
            title = dto.title,
            year = dto.releaseDate?.take(4)?.toIntOrNull(),
            overview = dto.overview,
            posterPath = dto.posterPath,
            backdropPath = dto.backdropPath,
            voteAverage = dto.voteAverage,
            runtimeMinutes = dto.runtime,
            recommendations = recs,
        )
    }

    suspend fun tvDetail(apiKey: String, tmdbId: Int): MediaDetail {
        val requestUrl = url(
            "/tv/$tmdbId",
            apiKey,
            "append_to_response=content_ratings,images,credits,seasons,external_ids",
        )
        val resp = NetworkClient.execute(requestUrl)
        if (!resp.isSuccess) error("TMDB request failed: ${resp.status}")
        val dto = json.decodeFromString(TmdbTvDetail.serializer(), resp.text)
        return MediaDetail(
            tmdbId = dto.id,
            mediaType = MediaType.TV,
            title = dto.name,
            year = dto.firstAirDate?.take(4)?.toIntOrNull(),
            overview = dto.overview,
            posterPath = dto.posterPath,
            backdropPath = dto.backdropPath,
            voteAverage = dto.voteAverage,
            seasons = dto.seasons.map {
                SeasonSummary(it.seasonNumber, it.name, it.episodeCount, it.posterPath)
            },
        )
    }

    suspend fun seasonEpisodes(apiKey: String, tmdbId: Int, season: Int): List<Episode> {
        val resp = NetworkClient.execute(url("/tv/$tmdbId/season/$season", apiKey))
        if (!resp.isSuccess) error("TMDB request failed: ${resp.status}")
        return json.decodeFromString(TmdbSeason.serializer(), resp.text).episodes.map {
            Episode(season, it.episodeNumber, it.name, it.overview, it.stillPath)
        }
    }

    private suspend fun movieRecommendations(apiKey: String, tmdbId: Int): List<MediaItem> =
        getItems(url("/movie/$tmdbId/recommendations", apiKey, "page=1"))

    companion object {
        private const val BASE = "https://api.themoviedb.org/3"
    }
}

@Serializable
private data class TmdbList(val results: List<TmdbItem> = emptyList())

@Serializable
private data class TmdbItem(
    val id: Int,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
) {
    fun toMediaItem(): MediaItem {
        val type = if (mediaType == "tv") MediaType.TV else MediaType.MOVIE
        val date = releaseDate ?: firstAirDate
        return MediaItem(
            tmdbId = id,
            mediaType = type,
            title = title ?: name ?: "",
            year = date?.take(4)?.toIntOrNull(),
            overview = overview,
            posterPath = posterPath,
            backdropPath = backdropPath,
            voteAverage = voteAverage,
        )
    }
}

@Serializable
private data class TmdbMovieDetail(
    val id: Int,
    val title: String,
    @SerialName("release_date") val releaseDate: String? = null,
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val runtime: Int? = null,
)

@Serializable
private data class TmdbTvDetail(
    val id: Int,
    val name: String,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val seasons: List<TmdbSeasonSummary> = emptyList(),
)

@Serializable
private data class TmdbSeasonSummary(
    @SerialName("season_number") val seasonNumber: Int,
    val name: String,
    @SerialName("episode_count") val episodeCount: Int = 0,
    @SerialName("poster_path") val posterPath: String? = null,
)

@Serializable
private data class TmdbSeason(val episodes: List<TmdbEpisode> = emptyList())

@Serializable
private data class TmdbEpisode(
    @SerialName("episode_number") val episodeNumber: Int,
    val name: String,
    val overview: String = "",
    @SerialName("still_path") val stillPath: String? = null,
)
