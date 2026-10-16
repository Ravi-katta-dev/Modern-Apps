package com.vayunmathur.everysync.core.network

import com.vayunmathur.everysync.core.platform.MemorySecureStore
import com.vayunmathur.everysync.core.platform.RelayCursorStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Identity + init coverage for [SharedRelayTransport]: stable device id,
 * load-or-create PQC identity (race-safe re-read), register-on-init, and
 * device-scoped channels. Runs against the fake HTTP client (no network).
 */
class SharedTransportTest {

    private class FakeHttp(val log: MutableMap<String, MutableList<String>> = mutableMapOf()) : RelayHttpClient {
        val posts = mutableListOf<Pair<String, String>>()
        var status = 200

        override suspend fun post(url: String, jsonBody: String): RelayHttpResponse {
            posts.add(url to jsonBody)
            return RelayHttpResponse(status, """{"seq":${log.size},"actions":[]}""")
        }

        override fun subscribe(wsUrl: String, channel: String): Flow<String> = emptyFlow()
    }

    private class TestTransport(
        store: MemorySecureStore = MemorySecureStore(),
        crypto: PqcCrypto = FakePqcCrypto(),
        val http: FakeHttp = FakeHttp(),
    ) : SharedRelayTransport(store, crypto) {
        val backingStore = store
        override fun client(): RelayHttpClient = http
    }

    @Test
    fun initRegistersAndAssignsStableId() = kotlinx.coroutines.runBlocking {
        val store = MemorySecureStore()
        val t = TestTransport(store)
        assertTrue(t.init())
        val firstId = t.deviceId
        assertTrue(firstId.isNotBlank())
        // Registered to /register with our id.
        assertTrue(t.http.posts.any { (url, _) -> url.endsWith("/register") })

        // Second transport on the same store reuses the id + identity.
        val t2 = TestTransport(store)
        assertTrue(t2.init())
        assertEquals(firstId, t2.deviceId)
    }

    @Test
    fun identitySurvivesReload() = kotlinx.coroutines.runBlocking {
        val store = MemorySecureStore()
        val t = TestTransport(store)
        t.init()
        val bundle = t.publicBundle.copyOf()
        assertTrue(bundle.isNotEmpty())

        val t2 = TestTransport(store)
        t2.init()
        assertTrue(bundle.contentEquals(t2.publicBundle))
    }

    @Test
    fun vaultIdsAreUnique() = kotlinx.coroutines.runBlocking {
        val t = TestTransport()
        t.init()
        val ids = (1..50).map { t.newVaultId() }.toSet()
        assertEquals(50, ids.size)
        // UUID v4 shape.
        assertTrue(ids.all { it.length == 36 && it[14] == '4' })
    }

    @Test
    fun vaultKeysAreUnique256Bit() = kotlinx.coroutines.runBlocking {
        val t = TestTransport()
        t.init()
        val k1 = t.newVaultKey()
        val k2 = t.newVaultKey()
        assertEquals(32, k1.size)
        assertEquals(32, k2.size)
        assertNotEquals(k1.toList(), k2.toList())
    }

    @Test
    fun inboxChannelIsDeviceScoped() {
        assertEquals("inbox:aaa", inboxChannel("aaa"))
        assertNotEquals(inboxChannel("aaa"), inboxChannel("bbb"))
    }

    @Test
    fun cursorStoreAdvancesForwardOnly() = kotlinx.coroutines.runBlocking {
        val cursors = RelayCursorStore(MemorySecureStore())
        assertEquals(0, cursors.get("notes", "v1", "notes:v1"))
        assertTrue(cursors.advance("notes", "v1", "notes:v1", 7))
        assertEquals(7, cursors.get("notes", "v1", "notes:v1"))
        // Stale or equal seq never moves the cursor.
        assertTrue(!cursors.advance("notes", "v1", "notes:v1", 7))
        assertTrue(!cursors.advance("notes", "v1", "notes:v1", 3))
        assertEquals(7, cursors.get("notes", "v1", "notes:v1"))
    }

    @Test
    fun memoryStoreHonorsOnlyIfAbsent() = kotlinx.coroutines.runBlocking {
        val store = MemorySecureStore()
        store.setBytes("k", "a".encodeToByteArray(), onlyIfAbsent = true)
        store.setBytes("k", "b".encodeToByteArray(), onlyIfAbsent = true)
        assertEquals("a", store.getBytes("k")!!.decodeToString())
        store.setBytes("k", "b".encodeToByteArray())
        assertEquals("b", store.getBytes("k")!!.decodeToString())
        store.delete("k")
        assertEquals(null, store.getBytes("k"))
    }
}
