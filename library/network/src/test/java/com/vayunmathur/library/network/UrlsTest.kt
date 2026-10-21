package com.vayunmathur.library.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [Urls], the dependency-free query/host helpers. Pure `java.net` only,
 * so these run on the plain host JVM.
 */
class UrlsTest {

    @Test
    fun host_returns_the_bare_host() {
        assertEquals("example.com", Urls.host("https://example.com/a?b=c"))
        assertEquals("example.com", Urls.host("https://example.com:8443/a"))
    }

    @Test
    fun host_is_empty_when_there_is_nothing_to_parse() {
        assertEquals("", Urls.host("not a url at all"))
        assertEquals("", Urls.host("http://[::1"))
        assertEquals("", Urls.host(""))
    }

    @Test
    fun append_query_adds_a_question_mark_when_missing() {
        assertEquals("https://x.test/s?q=1", Urls.appendQuery("https://x.test/s", mapOf("q" to "1")))
    }

    @Test
    fun append_query_extends_an_existing_query() {
        assertEquals("https://x.test/s?a=1&b=2", Urls.appendQuery("https://x.test/s?a=1", mapOf("b" to "2")))
    }

    @Test
    fun append_query_tolerates_a_trailing_separator() {
        assertEquals("https://x.test/s?a=1", Urls.appendQuery("https://x.test/s?", mapOf("a" to "1")))
        assertEquals("https://x.test/s?a=1&b=2", Urls.appendQuery("https://x.test/s?a=1&", mapOf("b" to "2")))
    }

    @Test
    fun append_query_with_no_params_returns_the_url_untouched() {
        val url = "https://x.test/s?a=1"
        assertEquals(url, Urls.appendQuery(url, emptyMap()))
    }

    @Test
    fun append_query_form_encodes_keys_and_values() {
        assertEquals("https://x.test/s?q=a+b%26c", Urls.appendQuery("https://x.test/s", mapOf("q" to "a b&c")))
    }

    @Test
    fun append_query_joins_params_in_order_and_leaves_existing_encoding_alone() {
        val url = Urls.appendQuery("https://x.test/s?q=a%20b", mapOf("p" to "1", "r" to "2"))
        assertEquals("https://x.test/s?q=a%20b&p=1&r=2", url)
        assertTrue(url.contains("a%20b"), "existing encoding must survive byte-for-byte")
    }
}
