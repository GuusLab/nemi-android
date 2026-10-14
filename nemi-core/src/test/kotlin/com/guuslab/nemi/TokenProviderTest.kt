package com.guuslab.nemi

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TokenProviderTest {
    private val server = MockWebServer()
    private val routes = Routes()
    private val refreshes = AtomicInteger()
    private lateinit var endpoints: NemiEndpoints
    private lateinit var oauth: NemiOAuthClient

    @BeforeTest
    fun setUp() {
        server.dispatcher = routes
        server.start()
        endpoints = endpointsFor(server)
        oauth = NemiOAuthClient(CLIENT_ID, REDIRECT, endpoints = endpoints, clock = fixedClock)
        routes.on("POST /api/oauth/token") {
            Thread.sleep(50) // widen the race window
            val n = refreshes.incrementAndGet()
            json("""{"access_token":"nemi_oat_new$n","expires_in":3600,"refresh_token":"nemi_ort_new$n"}""")
        }
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun expired() = NemiTokens("nemi_oat_old", expiresAt = NOW_MS - 1, refreshToken = "nemi_ort_old")

    @Test
    fun `valid token is returned without refreshing`() = runTest {
        val store = InMemoryTokenStore(NemiTokens("nemi_oat_ok", expiresAt = NOW_MS + 3_600_000, refreshToken = "r"))
        assertEquals("nemi_oat_ok", RefreshingTokenProvider(oauth, store, fixedClock).accessToken())
        assertEquals(0, refreshes.get())
    }

    @Test
    fun `token inside the margin is refreshed`() = runTest {
        val store = InMemoryTokenStore(NemiTokens("nemi_oat_soon", expiresAt = NOW_MS + 30_000, refreshToken = "r"))
        assertEquals("nemi_oat_new1", RefreshingTokenProvider(oauth, store, fixedClock).accessToken())
    }

    @Test
    fun `concurrent callers share one refresh`() = runTest {
        val store = InMemoryTokenStore(expired())
        val provider = RefreshingTokenProvider(oauth, store, fixedClock)
        val tokens = (1..20).map { async { provider.accessToken() } }.awaitAll()
        assertEquals(1, refreshes.get())
        assertEquals(setOf("nemi_oat_new1"), tokens.toSet())
        assertEquals("nemi_ort_new1", store.read()!!.refreshToken)
    }

    @Test
    fun `401 refresh is single flight too`() = runTest {
        val store = InMemoryTokenStore(NemiTokens("nemi_oat_old", expiresAt = NOW_MS + 3_600_000, refreshToken = "nemi_ort_old"))
        val provider = RefreshingTokenProvider(oauth, store, fixedClock)
        val results = (1..10).map { async { provider.refreshAfterUnauthorized("nemi_oat_old") } }.awaitAll()
        assertEquals(1, refreshes.get())
        assertEquals(setOf("nemi_oat_new1"), results.toSet())
    }

    @Test
    fun `client retries once with a refreshed token after 401`() = runTest {
        routes.on("GET /api/v1/me") { request ->
            if (request.getHeader("Authorization") == "Bearer nemi_oat_old") {
                json("""{"error":{"code":"invalid_token","message":"Revoked"}}""", 401)
            } else {
                json("""{"id":"sub1"}""")
            }
        }
        val store = InMemoryTokenStore(NemiTokens("nemi_oat_old", expiresAt = NOW_MS + 3_600_000, refreshToken = "nemi_ort_old"))
        val nemi = NemiClient(RefreshingTokenProvider(oauth, store, fixedClock), baseUrl = endpoints.apiBase)
        assertEquals("sub1", nemi.me().id)
        assertEquals(1, refreshes.get())
    }

    @Test
    fun `invalid_grant clears the store`() = runTest {
        routes.on("POST /api/oauth/token") { json("""{"error":"invalid_grant"}""", 400) }
        val store = InMemoryTokenStore(expired())
        val e = assertFailsWith<NemiOAuthException> { RefreshingTokenProvider(oauth, store, fixedClock).accessToken() }
        assertEquals("invalid_grant", e.error)
        assertNull(store.read())
    }

    @Test
    fun `nobody signed in`() = runTest {
        val e = assertFailsWith<NemiOAuthException> { RefreshingTokenProvider(oauth, InMemoryTokenStore(), fixedClock).accessToken() }
        assertEquals(NemiOAuthException.NOT_SIGNED_IN, e.error)
    }

    @Test
    fun `expired without refresh token`() = runTest {
        val store = InMemoryTokenStore(NemiTokens("a", expiresAt = NOW_MS - 1))
        val e = assertFailsWith<NemiOAuthException> { RefreshingTokenProvider(oauth, store, fixedClock).accessToken() }
        assertEquals(NemiOAuthException.NOT_SIGNED_IN, e.error)
    }
}
