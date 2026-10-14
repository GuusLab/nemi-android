package com.guuslab.nemi

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

const val CLIENT_ID = "nemi_app_abcdefghijklmnopqrstuvwx"
const val REDIRECT = "com.example.app:/oauth2redirect"
const val NOW_MS = 1_800_000_000_000L
val NOW_S = NOW_MS / 1000

/** A generated P-256 key that signs id_tokens like the server does. */
class TestSigningKey(val kid: String = "key-1") {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val publicKey = pair.public as ECPublicKey
    private val privateKey = pair.private as ECPrivateKey

    fun jwk(): JsonObject = buildJsonObject {
        put("kty", "EC")
        put("crv", "P-256")
        put("kid", kid)
        put("alg", "ES256")
        put("use", "sig")
        put("x", Base64Url.encode(fixed32(publicKey.w.affineX)))
        put("y", Base64Url.encode(fixed32(publicKey.w.affineY)))
    }

    fun sign(claims: JsonObject, header: JsonObject = buildJsonObject { put("alg", "ES256"); put("kid", kid); put("typ", "JWT") }): String {
        val input = Base64Url.encode(header.toString().toByteArray()) + "." + Base64Url.encode(claims.toString().toByteArray())
        val signer = Signature.getInstance("SHA256withECDSAinP1363Format")
        signer.initSign(privateKey)
        signer.update(input.toByteArray())
        return input + "." + Base64Url.encode(signer.sign())
    }

    private fun fixed32(value: BigInteger): ByteArray {
        val raw = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32 - raw.size) + raw
    }
}

fun jwks(vararg keys: TestSigningKey): String = buildJsonObject {
    put("keys", buildJsonArray { keys.forEach { add(it.jwk()) } })
}.toString()

fun claims(
    issuer: String,
    aud: String = CLIENT_ID,
    exp: Long = NOW_S + 600,
    iat: Long = NOW_S,
    nonce: String? = "nonce-1",
    accessToken: String? = null,
    extra: Map<String, JsonElement> = emptyMap(),
): JsonObject = buildJsonObject {
    put("iss", issuer)
    put("aud", aud)
    put("sub", "pairwise-sub")
    put("iat", iat)
    put("exp", exp)
    put("auth_time", iat)
    if (nonce != null) put("nonce", nonce)
    if (accessToken != null) {
        val digest = MessageDigest.getInstance("SHA-256").digest(accessToken.toByteArray())
        put("at_hash", Base64Url.encode(digest.copyOfRange(0, 16)))
    }
    put("name", "Ada Lovelace")
    put("preferred_username", "ada")
    put("email", "ada@example.com")
    put("email_verified", true)
    extra.forEach { (k, v) -> put(k, v) }
}

val fixedClock = NemiClock { NOW_MS }

fun endpointsFor(server: MockWebServer): NemiEndpoints {
    val base = server.url("/").toString().trimEnd('/')
    return NemiEndpoints(
        issuer = "$base/oauth",
        authorization = "$base/oauth/authorize",
        token = "$base/api/oauth/token",
        revocation = "$base/api/oauth/revoke",
        userInfo = "$base/api/oauth/userinfo",
        jwks = "$base/oauth/jwks",
        discovery = "$base/oauth/.well-known/openid-configuration",
        apiBase = "$base/api/v1",
    )
}

/** Routes requests by "METHOD /path" to handlers, recording every request. */
class Routes : Dispatcher() {
    private val handlers = mutableMapOf<String, (RecordedRequest) -> MockResponse>()
    val counts = mutableMapOf<String, Int>()

    fun on(route: String, handler: (RecordedRequest) -> MockResponse) {
        handlers[route] = handler
    }

    @Synchronized
    override fun dispatch(request: RecordedRequest): MockResponse {
        val route = "${request.method} ${request.requestUrl!!.encodedPath}"
        counts[route] = (counts[route] ?: 0) + 1
        return handlers[route]?.invoke(request) ?: MockResponse().setResponseCode(404)
            .setBody("""{"error":{"code":"not_found","message":"No route $route"}}""")
    }
}

fun json(body: String, code: Int = 200): MockResponse =
    MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

fun form(request: RecordedRequest): Map<String, String> =
    NemiOAuthClient.queryParameters("x:/?" + request.body.readUtf8())

fun JsonObject.str(key: String): String = (this[key] as JsonPrimitive).content
