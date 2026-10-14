package com.guuslab.nemi

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IdTokenVerifierTest {
    private val server = MockWebServer()
    private val routes = Routes()
    private val key = TestSigningKey("key-1")
    private lateinit var endpoints: NemiEndpoints
    private lateinit var verifier: IdTokenVerifier

    @BeforeTest
    fun setUp() {
        server.dispatcher = routes
        server.start()
        endpoints = endpointsFor(server)
        routes.on("GET /oauth/jwks") { json(jwks(key)) }
        verifier = IdTokenVerifier(endpoints.issuer, CLIENT_ID, endpoints.jwks, clock = fixedClock)
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    private suspend fun assertInvalid(token: String, nonce: String? = "nonce-1", accessToken: String? = null): NemiOAuthException {
        val e = assertFailsWith<NemiOAuthException> { verifier.verify(token, nonce, accessToken) }
        assertEquals(NemiOAuthException.INVALID_ID_TOKEN, e.error)
        return e
    }

    @Test
    fun `valid token returns claims`() = runTest {
        val claims = verifier.verify(key.sign(claims(endpoints.issuer, accessToken = "nemi_oat_x")), "nonce-1", "nemi_oat_x")
        assertEquals("pairwise-sub", claims.subject)
        assertEquals(listOf(CLIENT_ID), claims.audience)
        assertEquals("ada@example.com", claims.email)
        assertEquals(true, claims.emailVerified)
        assertEquals("ada", claims.preferredUsername)
    }

    @Test
    fun `keys are cached`() = runTest {
        repeat(3) { verifier.verify(key.sign(claims(endpoints.issuer)), "nonce-1") }
        assertEquals(1, routes.counts["GET /oauth/jwks"])
    }

    @Test
    fun `expired token is refused`() = runTest {
        assertInvalid(key.sign(claims(endpoints.issuer, exp = NOW_S - 120)))
    }

    @Test
    fun `small clock skew is tolerated`() = runTest {
        verifier.verify(key.sign(claims(endpoints.issuer, exp = NOW_S - 30)), "nonce-1")
    }

    @Test
    fun `wrong audience is refused`() = runTest {
        assertInvalid(key.sign(claims(endpoints.issuer, aud = "nemi_app_somebodyelse")))
    }

    @Test
    fun `wrong issuer is refused`() = runTest {
        assertInvalid(key.sign(claims("https://nemilab.com")))
    }

    @Test
    fun `wrong nonce is refused`() = runTest {
        assertInvalid(key.sign(claims(endpoints.issuer, nonce = "other")))
    }

    @Test
    fun `missing nonce is refused when one was sent`() = runTest {
        assertInvalid(key.sign(claims(endpoints.issuer, nonce = null)))
    }

    @Test
    fun `issued in the future is refused`() = runTest {
        assertInvalid(key.sign(claims(endpoints.issuer, iat = NOW_S + 3600, exp = NOW_S + 4000)))
    }

    @Test
    fun `unknown kid refetches once then fails`() = runTest {
        val stranger = TestSigningKey("key-unknown")
        verifier.verify(key.sign(claims(endpoints.issuer)), "nonce-1")
        val e = assertInvalid(stranger.sign(claims(endpoints.issuer)))
        assertTrue("key-unknown" in e.message!!)
        assertEquals(2, routes.counts["GET /oauth/jwks"])
    }

    @Test
    fun `rotated key is picked up`() = runTest {
        verifier.verify(key.sign(claims(endpoints.issuer)), "nonce-1")
        val next = TestSigningKey("key-2")
        routes.on("GET /oauth/jwks") { json(jwks(key, next)) }
        assertEquals("pairwise-sub", verifier.verify(next.sign(claims(endpoints.issuer)), "nonce-1").subject)
    }

    @Test
    fun `signature from another key is refused`() = runTest {
        val impostor = TestSigningKey("key-1") // same kid, different key
        assertInvalid(impostor.sign(claims(endpoints.issuer)))
    }

    @Test
    fun `tampered payload is refused`() = runTest {
        val token = key.sign(claims(endpoints.issuer))
        val parts = token.split('.')
        val forged = claims(endpoints.issuer, extra = mapOf("sub" to kotlinx.serialization.json.JsonPrimitive("admin")))
        assertInvalid(parts[0] + "." + Base64Url.encode(forged.toString().toByteArray()) + "." + parts[2])
    }

    @Test
    fun `alg none and HS256 are refused`() = runTest {
        for (alg in listOf("none", "HS256")) {
            val header = buildJsonObject { put("alg", alg); put("kid", "key-1") }
            assertInvalid(key.sign(claims(endpoints.issuer), header))
        }
    }

    @Test
    fun `at_hash mismatch is refused`() = runTest {
        assertInvalid(key.sign(claims(endpoints.issuer, accessToken = "nemi_oat_a")), accessToken = "nemi_oat_b")
    }

    @Test
    fun `malformed tokens are refused`() = runTest {
        assertInvalid("not-a-jwt")
        assertInvalid("a.b.c")
    }

    @Test
    fun `off curve key is refused`() {
        val jwk = Jwk(kty = "EC", crv = "P-256", kid = "bad", x = Base64Url.encode(ByteArray(32) { 1 }), y = Base64Url.encode(ByteArray(32) { 2 }))
        assertFailsWith<NemiOAuthException> { Jose.ecPublicKey(jwk) }
    }

    @Test
    fun `jose signature converts to DER that java security accepts`() {
        val token = key.sign(claims(endpoints.issuer))
        val jwt = Jose.parse(token)
        val parsedKey = Jose.ecPublicKey(Jwk(kty = "EC", crv = "P-256", kid = "k", x = key.jwk().str("x"), y = key.jwk().str("y")))
        assertTrue(Jose.verifyEs256(parsedKey, jwt.signingInput, jwt.signature))
        val der = Jose.joseToDer(jwt.signature)
        assertEquals(0x30, der[0].toInt())
        assertEquals(der.size - 2, der[1].toInt())
    }
}
