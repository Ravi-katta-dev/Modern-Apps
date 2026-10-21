package com.vayunmathur.library.network

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Extends the pooled-engine coverage with the paths [PooledHttpEngineTest] leaves out:
 * error-status mapping, redirect suppression, header forwarding (single + iterable),
 * POST body round-trip, and connect-failure surfacing. Same JDK in-process server setup.
 */
class PooledHttpEngineRoutingTest {

    private lateinit var server: HttpServer
    private var baseUrl = ""

    @BeforeTest
    fun setUp() {
        PooledHttpEngine.USE_POOLED_ENGINE = true
        PooledHttpEngine.resetForTests()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        baseUrl = "http://127.0.0.1:${server.address.port}"
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        PooledHttpEngine.USE_POOLED_ENGINE = true
        PooledHttpEngine.resetForTests()
    }

    @Test
    fun error_status_maps_body_and_reports_failure() = runBlocking {
        server.createContext("/gone") { exchange ->
            val body = "nope".toByteArray()
            exchange.sendResponseHeaders(404, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val response = NetworkClient.execute(url = "$baseUrl/gone")
        assertEquals(404, response.status)
        assertEquals("nope", response.text)
        assertFalse(response.isSuccess, "a 404 must not read as success")
        assertTrue(PooledHttpEngine.lastUsedPooled, "execute routes through the pool")
    }

    @Test
    fun redirects_are_not_followed_when_suppressed() = runBlocking {
        server.createContext("/hop") { exchange ->
            exchange.responseHeaders.add("Location", "$baseUrl/landed")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        val response = NetworkClient.execute(url = "$baseUrl/hop", followRedirects = false)
        assertEquals(302, response.status)
        assertEquals("$baseUrl/hop", response.url)
        assertEquals("$baseUrl/landed", response.header("Location"))
    }

    @Test
    fun custom_headers_are_forwarded_single_and_iterable() = runBlocking {
        server.createContext("/headers") { exchange ->
            val headers = exchange.requestHeaders
            fun first(name: String): String? =
                headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
            fun all(name: String): List<String> =
                headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: emptyList()
            val body = "${first("X-One")}|${all("X-Multi").joinToString(",")}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val response = NetworkClient.execute(
            url = "$baseUrl/headers",
            headers = mapOf("X-One" to "1", "X-Multi" to listOf("a", "b")),
        )
        assertEquals("1|a,b", response.text)
    }

    @Test
    fun post_body_round_trips() = runBlocking {
        server.createContext("/echo") { exchange ->
            val received = exchange.requestBody.readAllBytes()
            exchange.sendResponseHeaders(200, received.size.toLong())
            exchange.responseBody.use { it.write(received) }
        }
        val response = NetworkClient.execute(url = "$baseUrl/echo", method = "POST", body = "ping-body")
        assertEquals(200, response.status)
        assertEquals("ping-body", response.text)
    }

    @Test
    fun connect_failure_surfaces_as_io_exception() = runBlocking {
        val dead = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = dead.address.port
        dead.stop(0)
        assertFailsWith<IOException> { NetworkClient.execute(url = "http://127.0.0.1:$port/gone") }
    }
}
