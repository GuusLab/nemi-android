package com.guuslab.nemi

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthClientTest {
    private val server = MockWebServer()
    private val routes = Routes()
    private val key = TestSigningKey()
    private lateinit var endpoints: NemiEndpoints
    private lateinit var oauth: NemiOAuthClient

    @BeforeTest
    fun setUp() {
        server.dispatcher = routes
        server.start()
        endpoints = endpointsFor(server)
        routes.on("GET /oauth/jwks") { json(jwks(key)) }
        oauth = NemiOAuthClient(CLIENT_ID, REDIRECT, endpoints = endpoints, clock = fixedClock)
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    @Test
    fun `production endpoints match the contract`() {
        val p = NemiEndpoints.Production
        assertEquals("https://nemilab.com/oauth", p.issuer)
        assertEquals("https://nemilab.com/oauth/authorize", p.authorization)
        assertEquals("https://nemilab.com/api/oauth/token", p.token)
        assertEquals("https://nemilab.com/api/oauth/revoke", p.revocation)
        assertEquals("https://nemilab.com/api/oauth/userinfo", p.userInfo)
        assertEquals("https://nemilab.com/oauth/jwks", p.jwks)
        assertEquals("https://nemilab.com/oauth/.well-known/openid-configuration", p.discovery)
        assertEquals("https://nemilab.com/api/v1", p.apiBase)
    }

    @Test
    fun `authorize url carries every parameter`() {
        val prod = NemiOAuthClient(CLIENT_ID, REDIRECT)
        val request = prod.createAuthorizationRequest(
            listOf("openid", "profile", "email", "files.read", "offline_access"),
            prompt = "consent",
        )
        val url = request.url.toHttpUrl()
        assertEquals("https", url.scheme)
        assertEquals("nemilab.com", url.host)
        assertEquals("/oauth/authorize", url.encodedPath)
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals(CLIENT_ID, url.queryParameter("client_id"))
        assertEquals(REDIRECT, url.queryParameter("redirect_uri"))
        assertEquals("openid profile email files.read offline_access", url.queryParameter("scope"))
        assertEquals(request.state, url.queryParameter("state"))
        assertEquals(request.nonce, url.queryParameter("nonce"))
        assertEquals(Pkce.challengeFor(request.codeVerifier), url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("consent", url.queryParameter("prompt"))
        assertTrue("scope=openid%20profile" in request.url)
        assertTrue(request.codeVerifier !in request.url)
    }

    @Test
    fun `no nonce without openid and request survives json`() {
        val request = oauth.createAuthorizationRequest(listOf("files.read"))
        assertNull(request.nonce)
        assertNull(request.url.toHttpUrl().queryParameter("nonce"))
        assertNull(request.url.toHttpUrl().queryParameter("prompt"))
        assertEquals(request, AuthorizationRequest.fromJson(request.toJson()))
    }

    @Test
    fun `callback is parsed and checked`() {
        val ok = oauth.parseCallback("$REDIRECT?code=abc%2B1&state=s1&iss=${java.net.URLEncoder.encode(endpoints.issuer, "UTF-8")}", "s1")
        assertEquals("abc+1", ok.code)
        assertEquals(endpoints.issuer, ok.issuer)

        assertEquals(
            NemiOAuthException.INVALID_STATE,
            assertFailsWith<NemiOAuthException> { oauth.parseCallback("$REDIRECT?code=abc&state=evil", "s1") }.error,
        )
        assertEquals(
            NemiOAuthException.ACCESS_DENIED,
            assertFailsWith<NemiOAuthException> { oauth.parseCallback("$REDIRECT?error=access_denied&state=s1", "s1") }.error,
        )
        assertEquals(
            NemiOAuthException.INVALID_ISSUER,
            assertFailsWith<NemiOAuthException> { oauth.parseCallback("$REDIRECT?code=a&state=s1&iss=https%3A%2F%2Fevil.example", "s1") }.error,
        )
        assertEquals(
            NemiOAuthException.INVALID_REQUEST,
            assertFailsWith<NemiOAuthException> { oauth.parseCallback("$REDIRECT?state=s1", "s1") }.error,
        )
    }

    @Test
    fun `code exchange posts the form and verifies the id_token`() = runTest {
        val idToken = key.sign(claims(endpoints.issuer, nonce = "n1", accessToken = "nemi_oat_1"))
        routes.on("POST /api/oauth/token") {
            json(
                """{"access_token":"nemi_oat_1","token_type":"Bearer","expires_in":3600,
                "refresh_token":"nemi_ort_1","scope":"openid profile email offline_access","id_token":"$idToken"}""",
            )
        }
        val credentials = oauth.exchangeCode("the-code", "v".repeat(43), nonce = "n1")
        assertEquals("nemi_oat_1", credentials.tokens.accessToken)
        assertEquals("nemi_ort_1", credentials.tokens.refreshToken)
        assertEquals(NOW_MS + 3_600_000, credentials.tokens.expiresAt)
        assertEquals(setOf("openid", "profile", "email", "offline_access"), credentials.tokens.scopes)
        assertEquals("pairwise-sub", assertNotNull(credentials.idToken).subject)

        val sent = form(server.takeRequestFor("POST /api/oauth/token"))
        assertEquals("authorization_code", sent["grant_type"])
        assertEquals("the-code", sent["code"])
        assertEquals(REDIRECT, sent["redirect_uri"])
        assertEquals("v".repeat(43), sent["code_verifier"])
        assertEquals(CLIENT_ID, sent["client_id"])
        assertNull(sent["client_secret"])
    }

    @Test
    fun `code exchange with a bad nonce fails`() = runTest {
        val idToken = key.sign(claims(endpoints.issuer, nonce = "n1"))
        routes.on("POST /api/oauth/token") { json("""{"access_token":"a","expires_in":10,"id_token":"$idToken"}""") }
        val e = assertFailsWith<NemiOAuthException> { oauth.exchangeCode("c", "v".repeat(43), nonce = "n2") }
        assertEquals(NemiOAuthException.INVALID_ID_TOKEN, e.error)
    }

    @Test
    fun `confidential client sends its secret`() = runTest {
        val confidential = NemiOAuthClient(CLIENT_ID, "https://example.com/cb", "nemi_cs_secret", endpoints, clock = fixedClock)
        routes.on("POST /api/oauth/token") { json("""{"access_token":"a","expires_in":10}""") }
        confidential.exchangeCode("c", "v".repeat(43))
        assertEquals("nemi_cs_secret", form(server.takeRequestFor("POST /api/oauth/token"))["client_secret"])
    }

    @Test
    fun `refresh rotates the refresh token`() = runTest {
        routes.on("POST /api/oauth/token") { json("""{"access_token":"nemi_oat_2","expires_in":3600,"refresh_token":"nemi_ort_2"}""") }
        val tokens = oauth.refresh("nemi_ort_1")
        assertEquals("nemi_oat_2", tokens.accessToken)
        assertEquals("nemi_ort_2", tokens.refreshToken)
        val sent = form(server.takeRequestFor("POST /api/oauth/token"))
        assertEquals("refresh_token", sent["grant_type"])
        assertEquals("nemi_ort_1", sent["refresh_token"])
        assertEquals(CLIENT_ID, sent["client_id"])
    }

    @Test
    fun `token errors map to NemiOAuthException`() = runTest {
        routes.on("POST /api/oauth/token") {
            json("""{"error":"invalid_grant","error_description":"Refresh token was already used"}""", 400)
        }
        val e = assertFailsWith<NemiOAuthException> { oauth.refresh("nemi_ort_old") }
        assertEquals("invalid_grant", e.error)
        assertEquals("Refresh token was already used", e.errorDescription)
        assertEquals(400, e.status)
    }

    @Test
    fun `revoke posts the token`() = runTest {
        routes.on("POST /api/oauth/revoke") { json("{}") }
        oauth.revoke("nemi_ort_1", "refresh_token")
        val sent = form(server.takeRequestFor("POST /api/oauth/revoke"))
        assertEquals("nemi_ort_1", sent["token"])
        assertEquals("refresh_token", sent["token_type_hint"])
        assertEquals(CLIENT_ID, sent["client_id"])
    }

    @Test
    fun `userinfo and discovery decode`() = runTest {
        routes.on("GET /api/oauth/userinfo") {
            json("""{"sub":"s","name":"Ada","preferred_username":"ada","email":"a@x.com","email_verified":true}""")
        }
        routes.on("GET /oauth/.well-known/openid-configuration") {
            json(
                """{"issuer":"${endpoints.issuer}","authorization_endpoint":"${endpoints.authorization}",
                "token_endpoint":"${endpoints.token}","jwks_uri":"${endpoints.jwks}","userinfo_endpoint":"${endpoints.userInfo}",
                "revocation_endpoint":"${endpoints.revocation}","code_challenge_methods_supported":["S256"],"unknown":1}""",
            )
        }
        val info = oauth.userInfo("nemi_oat_1")
        assertEquals("ada", info.preferredUsername)
        assertEquals(true, info.emailVerified)
        assertEquals("Bearer nemi_oat_1", server.takeRequestFor("GET /api/oauth/userinfo").getHeader("Authorization"))

        val config = oauth.discovery()
        assertEquals(listOf("S256"), config.codeChallengeMethodsSupported)
        assertEquals(endpoints.token, NemiEndpoints.from(config).token)
    }

    @Test
    fun `tokens never print raw values`() {
        val tokens = NemiTokens("nemi_oat_secret", expiresAt = 1, refreshToken = "nemi_ort_secret")
        assertTrue("secret" !in tokens.toString())
        assertEquals(tokens, NemiTokens.fromJson(tokens.toJson()))
    }
}

/** Takes recorded requests until one matches [route]. */
fun MockWebServer.takeRequestFor(route: String): okhttp3.mockwebserver.RecordedRequest {
    while (true) {
        val request = takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) ?: error("No request for $route")
        if ("${request.method} ${request.requestUrl!!.encodedPath}" == route) return request
    }
}
