package com.vayunmathur.uniflix.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private fun plugin(
    header: String = "// @uniflix-plugin id=test.plugin name=\"Test Plugin\" version=1.0.0",
    body: String = "function resolve(a){return '{}';}\nfunction servers(){return '[]';}",
    wasmBase64: String? = null,
): String = buildString {
    appendLine(header)
    if (wasmBase64 != null) {
        appendLine("// @uniflix-wasm-begin")
        appendLine("// $wasmBase64")
        appendLine("// @uniflix-wasm-end")
    }
    append(body)
}

/** Plugin file parsing: header identity, wasm section, size cap. */
class JsPluginManifestTest {

    @Test
    fun parses_valid_header() {
        val manifest = JsPluginManifest.parse(plugin())
        assertEquals("test.plugin", manifest.id)
        assertEquals("Test Plugin", manifest.name)
        assertEquals("1.0.0", manifest.version)
        assertNull(manifest.wasmBytes)
    }

    @Test
    fun parses_unquoted_header_fields() {
        val manifest = JsPluginManifest.parse(
            plugin(header = "// @uniflix-plugin id=nebula name=Nebula version=2.1"),
        )
        assertEquals("nebula", manifest.id)
        assertEquals("Nebula", manifest.name)
        assertEquals("2.1", manifest.version)
    }

    @Test
    fun rejects_missing_header() {
        assertFailsWith<ManifestError.Parse> {
            JsPluginManifest.parse("function resolve(a){return '{}';}")
        }
    }

    @Test
    fun rejects_missing_id() {
        assertFailsWith<ManifestError.Parse> {
            JsPluginManifest.parse(
                plugin(header = "// @uniflix-plugin name=Nope version=1.0"),
            )
        }
    }

    @Test
    fun rejects_bad_id_characters() {
        assertFailsWith<ManifestError.Parse> {
            JsPluginManifest.parse(
                plugin(header = "// @uniflix-plugin id=BAD_ID! name=Bad version=1.0"),
            )
        }
    }

    @Test
    fun rejects_oversize_file() {
        val big = "// @uniflix-plugin id=big name=Big version=1.0\n" + "x".repeat(JS_PLUGIN_MAX_CHARS)
        assertFailsWith<ManifestError.Parse> { JsPluginManifest.parse(big) }
    }

    @Test
    fun parses_wasm_section() {
        val wasm = byteArrayOf(0x00, 0x61, 0x73.toByte(), 0x6D)
        val base64 = java.util.Base64.getEncoder().encodeToString(wasm)
        val manifest = JsPluginManifest.parse(plugin(wasmBase64 = base64))
        assertNotNull(manifest.wasmBytes)
        assertEquals(wasm.toList(), manifest.wasmBytes!!.toList())
    }

    @Test
    fun rejects_unterminated_wasm_section() {
        val source = "// @uniflix-plugin id=w name=W version=1.0\n// @uniflix-wasm-begin\n// aGVsbG8=\nresolve();"
        assertFailsWith<ManifestError.Parse> { JsPluginManifest.parse(source) }
    }

    @Test
    fun rejects_invalid_wasm_base64() {
        val source = plugin(wasmBase64 = "!!!not-base64!!!")
        assertFailsWith<ManifestError.Parse> { JsPluginManifest.parse(source) }
    }
}
