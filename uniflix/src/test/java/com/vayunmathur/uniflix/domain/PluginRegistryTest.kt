package com.vayunmathur.uniflix.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private fun provider(
    name: String,
    source: VideoSource? = null,
    alternates: List<VideoSource> = emptyList(),
    servers: List<String> = emptyList(),
): StreamProvider = object : StreamProvider() {
    override val name: String = name
    override val supports4k: Boolean = false

    override suspend fun resolve(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int,
        episode: Int,
        server: String?,
    ): VideoSource = source ?: error("$name has nothing")

    override suspend fun alternates(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int,
        episode: Int,
    ): List<VideoSource> = alternates

    override suspend fun servers(): List<String> = servers
}

private fun installed(dbId: Long, priority: Int, addonId: String = "addon-$dbId") =
    InstalledAddon(dbId = dbId, addonId = addonId, baseUrl = "https://a$dbId.example", name = "A$dbId", priority = priority)

/** Resolution order, fallback, disabled-skip, and error aggregation. */
class PluginRegistryTest {

    @Test
    fun empty_registry_reports_no_plugins() { runBlocking {
        val result = PluginRegistry(emptyList()).resolve(550, MediaType.MOVIE)
        assertIs<ResolutionResult.NoPluginsEnabled>(result)
    }
    }

    @Test
    fun default_resolves_first() { runBlocking {
        val first = provider("First", VideoSource("https://a.example/1.m3u8", "First"))
        val second = provider("Second", VideoSource("https://b.example/2.m3u8", "Second"))
        val registry = PluginRegistry.ordered(
            entities = listOf(installed(1, priority = 1), installed(2, priority = 0)),
            defaultDbId = 1L,
            build = { entity -> if (entity.dbId == 1L) first else second },
        )
        val result = registry.resolve(550, MediaType.MOVIE)
        assertIs<ResolutionResult.Success>(result, "default must win over priority")
        assertEquals("https://a.example/1.m3u8", result.source.streamUrl)
    }
    }

    @Test
    fun falls_back_on_failure_and_records_errors() { runBlocking {
        val failing = provider("Failing")
        val good = provider("Good", VideoSource("https://good.example/g.m3u8", "Good"))
        val registry = PluginRegistry.ordered(
            entities = listOf(installed(1, priority = 0), installed(2, priority = 1)),
            defaultDbId = null,
            build = { entity -> if (entity.dbId == 1L) failing else good },
        )
        val result = registry.resolve(550, MediaType.MOVIE)
        assertIs<ResolutionResult.Success>(result)
        assertEquals("https://good.example/g.m3u8", result.source.streamUrl)
    }
    }

    @Test
    fun all_failed_aggregates_per_addon_errors() { runBlocking {
        val registry = PluginRegistry(
            listOf("a" to provider("A"), "b" to provider("B")),
        )
        val result = registry.resolve(550, MediaType.MOVIE)
        assertIs<ResolutionResult.AllFailed>(result)
        assertEquals(setOf("a", "b"), result.errorsByAddon.keys)
        assertTrue(result.errorsByAddon.values.all { it.isNotBlank() })
    }
    }

    @Test
    fun alternates_concatenate_in_order_and_cap() { runBlocking {
        val registry = PluginRegistry(
            listOf(
                "a" to provider(
                    "A",
                    VideoSource("https://a.example/0.m3u8", "A"),
                    alternates = listOf(VideoSource("https://a.example/1.m3u8", "A1")),
                ),
                "b" to provider(
                    "B",
                    VideoSource("https://b.example/0.m3u8", "B"),
                    alternates = listOf(VideoSource("https://b.example/1.m3u8", "B1")),
                ),
            ),
        )
        val rest = registry.alternates(550, MediaType.MOVIE, maxTotal = 1)
        assertEquals(listOf("https://a.example/1.m3u8"), rest.map { it.streamUrl })
    }
    }

    @Test
    fun servers_union_preserves_order() { runBlocking {
        val registry = PluginRegistry(
            listOf(
                "a" to provider("A", servers = listOf("Nebula", "Lisbon")),
                "b" to provider("B", servers = listOf("Lisbon", "Oslo")),
            ),
        )
        assertEquals(listOf("Nebula", "Lisbon", "Oslo"), registry.servers())
    }
    }

    @Test
    fun priority_orders_non_default_addons() { runBlocking {
        val seen = mutableListOf<String>()
        fun tracking(name: String) = provider(name)
        val registry = PluginRegistry.ordered(
            entities = listOf(installed(1, priority = 5), installed(2, priority = 1)),
            defaultDbId = null,
            build = { entity -> tracking(if (entity.dbId == 1L) "slow" else "fast").also { seen += entity.addonId } },
        )
        registry.resolve(550, MediaType.MOVIE)
        assertEquals(listOf("addon-2", "addon-1"), seen, "lower priority resolves first")
    }
}
