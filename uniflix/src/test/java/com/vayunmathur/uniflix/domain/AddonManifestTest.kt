package com.vayunmathur.uniflix.domain

import com.vayunmathur.uniflix.data.AddonStreamProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/** Manifest parsing and validation: string/object resources, stream requirement, type overlap. */
class AddonManifestTest {

    @Test
    fun parses_string_resources() {
        val manifest = json.decodeFromString(
            AddonManifest.serializer(),
            """{"id":"test.addon","name":"Test","version":"1.0.0","resources":["stream"],"types":["movie","series"]}""",
        ).validate()
        assertEquals("test.addon", manifest.id)
        assertEquals(listOf(AddonResource(name = "stream")), manifest.resources)
    }

    @Test
    fun parses_object_resources() {
        val manifest = json.decodeFromString(
            AddonManifest.serializer(),
            """{"id":"test.addon","name":"Test","resources":[{"name":"stream","types":["movie"]}],"types":["movie"]}""",
        ).validate()
        assertEquals(listOf("movie"), manifest.resources.single().types)
    }

    @Test
    fun rejects_missing_stream_resource() {
        val manifest = json.decodeFromString(
            AddonManifest.serializer(),
            """{"id":"test.catalog","name":"Catalog","resources":["catalog"],"types":["movie"]}""",
        )
        assertFailsWith<ManifestError.Unsupported> { manifest.validate() }
    }

    @Test
    fun rejects_wrong_types() {
        val manifest = json.decodeFromString(
            AddonManifest.serializer(),
            """{"id":"test.meta","name":"Meta","resources":[{"name":"stream","types":["channel"]}],"types":["channel"]}""",
        )
        assertFailsWith<ManifestError.Unsupported> { manifest.validate() }
    }

    @Test
    fun accepts_untyped_stream_resource() {
        // Untyped resources serve everything; only an explicit non-movie/series list rejects.
        val manifest = json.decodeFromString(
            AddonManifest.serializer(),
            """{"id":"test.any","name":"Any","resources":["stream"]}""",
        ).validate()
        assertEquals("test.any", manifest.id)
    }
}

/** TMDB-to-addon stream id mapping for movies and episodes. */
class AddonIdMappingTest {

    @Test
    fun movie_id_form() {
        assertEquals("tmdb:550", addonStreamId(550, MediaType.MOVIE))
        assertEquals("movie", addonResourceType(MediaType.MOVIE))
    }

    @Test
    fun episode_id_form() {
        assertEquals("tmdb:1396:1:1", addonStreamId(1396, MediaType.TV, season = 1, episode = 1))
        assertEquals("series", addonResourceType(MediaType.TV))
    }

    @Test
    fun stream_mapping_drops_blank_urls() {
        val source = AddonStream(url = "  ").toVideoSource("Fallback")
        assertEquals(null, source)
    }

    @Test
    fun stream_mapping_prefers_addon_name() {
        val source = AddonStream(
            url = "https://cdn.example/stream.m3u8",
            name = "1080p",
            subtitles = listOf(AddonSubtitle(url = "https://cdn.example/en.vtt", lang = "en")),
        ).toVideoSource("Fallback")!!
        assertEquals("https://cdn.example/stream.m3u8", source.streamUrl)
        assertEquals("1080p", source.title)
        assertTrue(source.subtitles.single().url.endsWith("/en.vtt"))
    }
}

/** Base-URL normalization: bare hosts, manifest URLs, slashes, scheme policy. */
class AddonUrlNormalizationTest {

    @Test
    fun strips_manifest_suffix_and_trailing_slash() {
        assertEquals(
            "https://example.com",
            AddonStreamProvider.normalizeBaseUrl("https://example.com/manifest.json"),
        )
        assertEquals(
            "https://example.com",
            AddonStreamProvider.normalizeBaseUrl("https://example.com///"),
        )
    }

    @Test
    fun defaults_bare_host_to_https() {
        assertEquals(
            "https://example.com",
            AddonStreamProvider.normalizeBaseUrl("example.com"),
        )
    }

    @Test
    fun keeps_path_and_port() {
        assertEquals(
            "https://example.com:8080/addon",
            AddonStreamProvider.normalizeBaseUrl("https://example.com:8080/addon/manifest.json"),
        )
    }

    @Test
    fun rejects_remote_http() {
        assertFailsWith<ManifestError.InsecureUrl> {
            AddonStreamProvider.normalizeBaseUrl("http://example.com/manifest.json")
        }
    }

    @Test
    fun allows_loopback_and_private_http() {
        assertEquals(
            "http://localhost:8080",
            AddonStreamProvider.normalizeBaseUrl("http://localhost:8080/manifest.json"),
        )
        assertEquals(
            "http://192.168.1.10:7000",
            AddonStreamProvider.normalizeBaseUrl("http://192.168.1.10:7000/manifest.json"),
        )
    }

    @Test
    fun rejects_non_http_schemes() {
        assertFailsWith<ManifestError.Parse> {
            AddonStreamProvider.normalizeBaseUrl("ftp://example.com/manifest.json")
        }
    }
}
