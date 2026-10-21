package com.vayunmathur.library.downloadservice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the host-safe core of [ModelDownloadWorker]: [ModelDownloadWorker.uniqueName]
 * stability plus the [DownloadSpec] value semantics it keys on.
 *
 * `uniqueName` only touches String hashing, so it runs on the plain host JVM even though
 * its enclosing worker is Android-only. `readSpecs`, `checksumOk` and the streaming loop
 * need a Context or are private, so they stay on-device; visibility is never widened for tests.
 */
class UniqueNameTest {

    private fun spec(
        fileName: String,
        url: String = "https://data.maproj.org/models/$fileName",
        sha256: String? = null,
    ) = DownloadSpec(fileName = fileName, description = fileName, url = url, sha256 = sha256)

    @Test
    fun same_specs_give_the_same_name() {
        val specs = listOf(spec("a.bin"), spec("b.bin"))
        assertEquals(ModelDownloadWorker.uniqueName(specs), ModelDownloadWorker.uniqueName(specs))
    }

    @Test
    fun name_is_a_prefixed_hex_token() {
        val name = ModelDownloadWorker.uniqueName(listOf(spec("a.bin")))
        assertTrue(Regex("model_download_[0-9a-f]+").matches(name), "unexpected name: $name")
    }

    @Test
    fun empty_specs_still_give_a_stable_name() {
        assertEquals("model_download_0", ModelDownloadWorker.uniqueName(emptyList()))
    }

    @Test
    fun different_file_names_give_different_names() {
        assertNotEquals(
            ModelDownloadWorker.uniqueName(listOf(spec("a.bin"))),
            ModelDownloadWorker.uniqueName(listOf(spec("b.bin"))),
        )
    }

    @Test
    fun spec_order_is_part_of_the_name() {
        val forward = listOf(spec("a.bin"), spec("b.bin"))
        val backward = listOf(spec("b.bin"), spec("a.bin"))
        assertNotEquals(ModelDownloadWorker.uniqueName(forward), ModelDownloadWorker.uniqueName(backward))
    }

    @Test
    fun url_and_sha_differences_keep_the_name_since_it_keys_on_file_names() {
        val base = ModelDownloadWorker.uniqueName(listOf(spec("a.bin")))
        assertEquals(base, ModelDownloadWorker.uniqueName(listOf(spec("a.bin", url = "https://other.test/a.bin"))))
        assertEquals(base, ModelDownloadWorker.uniqueName(listOf(spec("a.bin", sha256 = "abc123"))))
    }

    @Test
    fun spec_sha_defaults_to_null_and_compares_by_value() {
        assertNull(spec("a.bin").sha256)
        assertEquals(spec("a.bin"), spec("a.bin"))
        assertFalse(spec("a.bin") == spec("a.bin", sha256 = "abc123"))
        assertEquals("abc123", spec("a.bin").copy(sha256 = "abc123").sha256)
    }
}
