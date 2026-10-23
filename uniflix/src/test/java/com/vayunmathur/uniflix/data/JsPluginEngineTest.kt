package com.vayunmathur.uniflix.data

import com.vayunmathur.uniflix.domain.JsPluginManifest
import com.vayunmathur.uniflix.domain.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private fun manifest(source: String) = JsPluginManifest.parse(
    "// @uniflix-plugin id=test.plugin name=\"Test\" version=1.0.0\n$source",
)

private val stubBridge = object : NetworkBridge {
    override suspend fun fetch(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): FetchResult = FetchResult(status = 200, bytes = "{}".toByteArray())
}

/**
 * Engine: sync plugin fns, JSON contract, error wrapping, timeouts.
 *
 * The contract is deliberately sync-only (async plugin fns are rejected):
 * quickjs-kt does not reliably unwrap promise results on the JNI path, so
 * promises are kept out of the plugin surface entirely.
 */
class JsPluginEngineTest {

    @Test
    fun sync_resolve_returns_json() { runBlocking {
        val engine = JsPluginEngine(
            manifest("function resolve(a){ return JSON.stringify({ok: a.tmdb}); }"),
            stubBridge,
        )
        assertEquals("""{"ok":550}""", engine.resolve("""{"tmdb":550}"""))
    }
    }

    @Test
    fun async_resolve_is_rejected_with_contract_error() { runBlocking {
        val engine = JsPluginEngine(
            manifest("async function resolve(a){ return '\"async-ok\"'; }"),
            stubBridge,
        )
        val failure = assertFailsWith<IllegalStateException> { engine.resolve("{}") }
        assertTrue(failure.message!!.contains("must be sync"), failure.message)
    }
    }

    @Test
    fun servers_parses_string_array() { runBlocking {
        val engine = JsPluginEngine(
            manifest("function servers(){ return '[\"Nebula\",\"Lisbon\"]'; }"),
            stubBridge,
        )
        assertEquals("""["Nebula","Lisbon"]""", engine.servers())
    }
    }

    @Test
    fun alternates_defaults_to_empty_when_undefined() { runBlocking {
        val engine = JsPluginEngine(manifest("function resolve(a){return '{}';}"), stubBridge)
        assertEquals("[]", engine.alternates("{}"))
    }
    }

    @Test
    fun sync_ufetch_bridge_round_trips() { runBlocking {
        val seen = mutableListOf<String>()
        val bridge = object : NetworkBridge {
            override suspend fun fetch(
                url: String,
                method: String,
                headers: Map<String, String>,
                body: ByteArray?,
            ): FetchResult {
                seen += "$method $url"
                return FetchResult(status = 200, bytes = """{"hello":"world"}""".toByteArray())
            }
        }
        val engine = JsPluginEngine(
            manifest(
                "function resolve(a){ " +
                    "var r = JSON.parse(__ufetch('https://x.example/g', 'POST', '{}', null)); " +
                    "return JSON.stringify({status: r.status, ok: r.ok, body: r.text}); }",
            ),
            bridge,
        )
        val out = engine.resolve("{}")
        assertTrue(out.contains("\"status\":200"), out)
        assertTrue(out.contains("\"ok\":true"), out)
        assertTrue(out.contains("world"), out)
        assertEquals(listOf("POST https://x.example/g"), seen)
    }
    }

    @Test
    fun ufetch_bytes_arrive_as_base64() { runBlocking {
        val bridge = object : NetworkBridge {
            override suspend fun fetch(
                url: String,
                method: String,
                headers: Map<String, String>,
                body: ByteArray?,
            ): FetchResult = FetchResult(status = 200, bytes = byteArrayOf(1, 2, 3))
        }
        val engine = JsPluginEngine(
            manifest("function resolve(a){ var r = JSON.parse(__ufetch('https://x.example/', 'GET', '{}', null)); return r.bytes; }"),
            bridge,
        )
        assertEquals("AQID", engine.resolve("{}"))
    }
    }

    @Test
    fun plugin_throw_surfaces_plugin_name() { runBlocking {
        val engine = JsPluginEngine(
            manifest("function resolve(a){ throw new Error('boom'); }"),
            stubBridge,
        )
        val failure = assertFailsWith<IllegalStateException> { engine.resolve("{}") }
        assertTrue(failure.message!!.contains("'Test'"), failure.message)
        assertTrue(failure.message!!.contains("boom"), failure.message)
    }
    }

    @Test
    fun missing_resolve_function_errors() { runBlocking {
        val engine = JsPluginEngine(manifest("function servers(){return '[]';}"), stubBridge)
        assertFailsWith<IllegalStateException> { engine.resolve("{}") }
    }
    }

    @Test
    fun non_string_return_rejected_with_contract_error() { runBlocking {
        val engine = JsPluginEngine(
            manifest("function resolve(a){ return {not: 'a string'}; }"),
            stubBridge,
        )
        val failure = assertFailsWith<IllegalStateException> { engine.resolve("{}") }
        assertTrue(failure.message!!.contains("must return a JSON string"), failure.message)
    }
    }

    @Test
    fun network_hang_hits_wall_clock_timeout() { runBlocking {
        val hanging = object : NetworkBridge {
            override suspend fun fetch(
                url: String,
                method: String,
                headers: Map<String, String>,
                body: ByteArray?,
            ): FetchResult {
                kotlinx.coroutines.delay(30_000)
                return FetchResult(200, ByteArray(0))
            }
        }
        val engine = JsPluginEngine(
            manifest(
                "function resolve(a){ __ufetch('https://x.example/', 'GET', '{}', null); return '{}'; }",
            ),
            hanging,
            timeoutMs = 500,
        )
        val failure = assertFailsWith<IllegalStateException> { engine.resolve("{}") }
        assertTrue(failure.message!!.contains("imed out"), failure.message)
    }
    }

    @Test
    fun urand_returns_requested_bytes() { runBlocking {
        val engine = JsPluginEngine(
            manifest("function resolve(a){ return JSON.stringify({n: __urand(44).length}); }"),
            stubBridge,
        )
        assertEquals("""{"n":44}""", engine.resolve("{}"))
    }
    }

    @Test
    fun wasm_new_without_bytes_errors() { runBlocking {
        val engine = JsPluginEngine(
            manifest("function resolve(a){ __uwasm_new(new Uint8Array(0)); return '{}'; }"),
            stubBridge,
        )
        assertFailsWith<IllegalStateException> { engine.resolve("{}") }
    }
    }

    @Test
    fun resolve_receives_episode_args() { runBlocking {
        val engine = JsPluginEngine(
            manifest("function resolve(a){ return JSON.stringify(a); }"),
            stubBridge,
        )
        val out = engine.resolve("""{"tmdb":1396,"type":"series","season":1,"episode":2}""")
        assertTrue(out.contains("\"season\":1"), out)
        assertTrue(out.contains("\"episode\":2"), out)
        assertTrue(out.contains("\"type\":\"series\""), out)
    }
    }
}

/** Provider: JSON mapping onto VideoSource, error naming, server filtering. */
class JsStreamProviderTest {

    private fun provider(
        source: String,
        bridge: NetworkBridge = stubBridge,
        name: String = "Test",
    ) = JsStreamProvider(manifest = manifest(source), displayName = name, network = bridge)

    @Test
    fun resolve_maps_playlist_and_subtitles() { runBlocking {
        val plugin = provider(
            "function resolve(a){ return JSON.stringify({" +
                "streamUrl:'https://cdn.example/s.m3u8', title:'1080p'," +
                "subtitles:[{language:'en', displayName:'English', url:'https://cdn.example/en.vtt'}]}); }",
        )
        val result = plugin.resolve(550, MediaType.MOVIE)
        assertEquals("https://cdn.example/s.m3u8", result.streamUrl)
        assertEquals("1080p", result.title)
        assertEquals("https://cdn.example/en.vtt", result.subtitles.single().url)
        assertEquals("en", result.subtitles.single().language)
    }
    }

    @Test
    fun resolve_falls_back_to_plugin_name_title() { runBlocking {
        val plugin = provider(
            "function resolve(a){ return JSON.stringify({streamUrl:'https://cdn.example/s.m3u8'}); }",
            name = "Nebula",
        )
        assertEquals("Nebula", plugin.resolve(550, MediaType.MOVIE).title)
    }
    }

    @Test
    fun resolve_blank_url_errors_with_plugin_name() { runBlocking {
        val plugin = provider("function resolve(a){ return JSON.stringify({streamUrl:''}); }")
        val failure = assertFailsWith<IllegalStateException> {
            plugin.resolve(550, MediaType.MOVIE)
        }
        assertTrue(failure.message!!.startsWith("'Test'"), failure.message)
    }
    }

    @Test
    fun resolve_malformed_json_errors() { runBlocking {
        val plugin = provider("function resolve(a){ return 'not json'; }")
        assertFailsWith<IllegalStateException> { plugin.resolve(550, MediaType.MOVIE) }
    }
    }

    @Test
    fun servers_lists_names() { runBlocking {
        val plugin = provider("function servers(){ return '[\"Nebula\", \"Lisbon\", \"\"]'; }")
        assertEquals(listOf("Nebula", "Lisbon"), plugin.servers())
    }
    }

    @Test
    fun alternates_failure_returns_empty() { runBlocking {
        val plugin = provider(
            "function resolve(a){ return JSON.stringify({streamUrl:'https://cdn.example/s.m3u8'}); }",
        )
        assertEquals(emptyList(), plugin.alternates(550, MediaType.MOVIE))
    }
    }

    @Test
    fun resolve_selects_url_from_episode_args() { runBlocking {
        val plugin = provider(
            "function resolve(a){ " +
                "return JSON.stringify({streamUrl:'https://cdn.example/' + a.season + 'x' + a.episode + '.m3u8'}); }",
        )
        val out = plugin.resolve(1396, MediaType.TV, season = 1, episode = 2)
        assertEquals("https://cdn.example/1x2.m3u8", out.streamUrl)
    }
    }

    @Test
    fun registry_serves_js_then_falls_back() { runBlocking {
        val failing = provider("function resolve(a){ throw new Error('nope'); }", name = "Js")
        val good = provider(
            "function resolve(a){ return JSON.stringify({streamUrl:'https://ok.example/s.m3u8'}); }",
            name = "JsGood",
        )
        val registry = com.vayunmathur.uniflix.domain.PluginRegistry(
            listOf("js-1" to failing, "js-2" to good),
        )
        val result = registry.resolve(550, MediaType.MOVIE)
        assertIs<com.vayunmathur.uniflix.domain.ResolutionResult.Success>(result)
        assertEquals("https://ok.example/s.m3u8", result.source.streamUrl)
        assertEquals("js-2", result.addonId)
    }
    }
}
