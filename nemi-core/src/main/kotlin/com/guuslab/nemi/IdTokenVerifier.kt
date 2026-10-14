package com.guuslab.nemi

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey

/**
 * Verifies Nemi id_tokens: ES256 signature against the JWKS (`kid`), then `iss`, `aud`,
 * `exp`, `iat`, `nonce` and, when an access token is given, `at_hash`.
 *
 * Keys are cached for [jwksCacheMillis]. An unknown `kid` triggers one refetch (key
 * rotation) and fails if the key is still missing.
 */
public class IdTokenVerifier(
    public val issuer: String,
    public val clientId: String,
    private val jwksUri: String = NemiEndpoints.Production.jwks,
    private val httpClient: OkHttpClient = NemiSdk.defaultHttpClient,
    private val clock: NemiClock = NemiClock.System,
    /** Allowed clock skew for `exp` and `iat`. */
    private val leewaySeconds: Long = 60,
    private val jwksCacheMillis: Long = 60 * 60 * 1000L,
) {
    private val mutex = Mutex()
    private var keys: Map<String, ECPublicKey> = emptyMap()
    private var fetchedAt: Long = Long.MIN_VALUE

    /**
     * Verifies [idToken] and returns its claims, or throws [NemiOAuthException] with
     * error `invalid_id_token`.
     *
     * @param expectedNonce the nonce sent on the authorize request. When non null the token must carry it.
     * @param accessToken the access token issued alongside, to check `at_hash` when present.
     */
    public suspend fun verify(idToken: String, expectedNonce: String?, accessToken: String? = null): IdTokenClaims {
        val jwt = Jose.parse(idToken)
        val alg = jwt.header.string("alg")
        if (alg != "ES256") throw Jose.invalid("Unexpected id_token alg $alg, expected ES256")
        val kid = jwt.header.string("kid") ?: throw Jose.invalid("The id_token has no kid")
        val key = keyFor(kid)
        if (!Jose.verifyEs256(key, jwt.signingInput, jwt.signature)) {
            throw Jose.invalid("The id_token signature is not valid")
        }

        val claims = jwt.payload
        val iss = claims.string("iss")
        if (iss != issuer) throw Jose.invalid("The id_token issuer $iss is not $issuer")

        val audience = when (val aud = claims["aud"]) {
            is JsonPrimitive -> listOfNotNull(aud.contentOrNull)
            is JsonArray -> aud.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }
        if (clientId !in audience) throw Jose.invalid("The id_token audience does not include this client")
        if (audience.size > 1 && claims.string("azp") != clientId) {
            throw Jose.invalid("The id_token azp is not this client")
        }

        val now = clock.nowMillis() / 1000
        val exp = claims.long("exp") ?: throw Jose.invalid("The id_token has no exp")
        if (now - leewaySeconds >= exp) throw Jose.invalid("The id_token has expired")
        val iat = claims.long("iat") ?: throw Jose.invalid("The id_token has no iat")
        if (iat > now + leewaySeconds) throw Jose.invalid("The id_token was issued in the future")

        val nonce = claims.string("nonce")
        if (expectedNonce != null && !constantTimeEquals(nonce, expectedNonce)) {
            throw Jose.invalid("The id_token nonce does not match")
        }

        val atHash = claims.string("at_hash")
        if (atHash != null && accessToken != null) {
            val digest = MessageDigest.getInstance("SHA-256").digest(accessToken.toByteArray(Charsets.US_ASCII))
            if (!constantTimeEquals(atHash, Base64Url.encode(digest.copyOfRange(0, 16)))) {
                throw Jose.invalid("The id_token at_hash does not match the access token")
            }
        }

        return IdTokenClaims(
            issuer = iss,
            audience = audience,
            subject = claims.string("sub") ?: throw Jose.invalid("The id_token has no sub"),
            issuedAt = iat,
            expiresAt = exp,
            authTime = claims.long("auth_time"),
            nonce = nonce,
            atHash = atHash,
            name = claims.string("name"),
            preferredUsername = claims.string("preferred_username"),
            picture = claims.string("picture"),
            email = claims.string("email"),
            emailVerified = (claims["email_verified"] as? JsonPrimitive)?.booleanOrNull,
            raw = claims,
        )
    }

    private suspend fun keyFor(kid: String): ECPublicKey = mutex.withLock {
        val now = clock.nowMillis()
        val fresh = fetchedAt != Long.MIN_VALUE && now - fetchedAt < jwksCacheMillis
        if (fresh) keys[kid]?.let { return@withLock it }
        // Stale cache or unknown kid (the server may have rotated): fetch once.
        keys = fetchKeys()
        fetchedAt = now
        keys[kid] ?: throw Jose.invalid("No signing key with kid $kid")
    }

    private suspend fun fetchKeys(): Map<String, ECPublicKey> {
        val request = Request.Builder().url(jwksUri).header("Accept", "application/json").build()
        val text = httpClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw NemiOAuthException(
                    NemiOAuthException.SERVER_ERROR,
                    "Could not load the JWKS (HTTP ${response.code})",
                    status = response.code,
                )
            }
            response.body?.string().orEmpty()
        }
        val set = NemiJson.decodeFromString(JwkSet.serializer(), text)
        return set.keys
            .filter { it.kty == "EC" && it.crv == "P-256" && it.kid != null && (it.use == null || it.use == "sig") }
            .associate { it.kid!! to Jose.ecPublicKey(it) }
    }
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

internal fun constantTimeEquals(a: String?, b: String?): Boolean {
    if (a == null || b == null) return false
    return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
