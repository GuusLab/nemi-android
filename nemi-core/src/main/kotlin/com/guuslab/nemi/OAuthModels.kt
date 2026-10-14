package com.guuslab.nemi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Tokens for one signed in person. Serializable so a token store can persist it. */
@Serializable
public data class NemiTokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    /** Expiry of [accessToken] in epoch milliseconds. */
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("scope") val scope: String? = null,
    @SerialName("id_token") val idToken: String? = null,
) {
    /** The granted scopes. */
    val scopes: Set<String> get() = scope?.split(' ')?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    /** True when the access token expires within [marginMillis] of [nowMillis]. */
    public fun isExpired(nowMillis: Long, marginMillis: Long = 0): Boolean = nowMillis + marginMillis >= expiresAt

    public fun toJson(): String = NemiJson.encodeToString(serializer(), this)

    // Never print raw tokens.
    override fun toString(): String =
        "NemiTokens(tokenType=$tokenType, expiresAt=$expiresAt, scope=$scope, " +
            "hasRefreshToken=${refreshToken != null}, hasIdToken=${idToken != null})"

    public companion object {
        public fun fromJson(json: String): NemiTokens = NemiJson.decodeFromString(serializer(), json)
    }
}

/** The result of a code exchange: the tokens and, when `openid` was granted, the verified id_token. */
public data class NemiCredentials(
    val tokens: NemiTokens,
    val idToken: IdTokenClaims?,
)

/**
 * Everything needed to start a sign in and finish it later. Persist it (it is serializable)
 * when the app can be killed while the browser is open.
 */
@Serializable
public data class AuthorizationRequest(
    @SerialName("url") val url: String,
    @SerialName("state") val state: String,
    @SerialName("nonce") val nonce: String?,
    @SerialName("code_verifier") val codeVerifier: String,
    @SerialName("redirect_uri") val redirectUri: String,
    @SerialName("scopes") val scopes: List<String>,
) {
    public fun toJson(): String = NemiJson.encodeToString(serializer(), this)

    override fun toString(): String = "AuthorizationRequest(url=$url, scopes=$scopes)"

    public companion object {
        public fun fromJson(json: String): AuthorizationRequest = NemiJson.decodeFromString(serializer(), json)
    }
}

/** A successful redirect back to the app, after the state was checked. */
public data class AuthorizationCallback(
    val code: String,
    val state: String,
    val issuer: String?,
) {
    override fun toString(): String = "AuthorizationCallback(state=$state, issuer=$issuer)"
}

/** The OpenID Provider metadata at `/oauth/.well-known/openid-configuration`. */
@Serializable
public data class OpenIdConfiguration(
    @SerialName("issuer") val issuer: String,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
    @SerialName("userinfo_endpoint") val userinfoEndpoint: String? = null,
    @SerialName("jwks_uri") val jwksUri: String,
    @SerialName("revocation_endpoint") val revocationEndpoint: String? = null,
    @SerialName("scopes_supported") val scopesSupported: List<String> = emptyList(),
    @SerialName("response_types_supported") val responseTypesSupported: List<String> = emptyList(),
    @SerialName("grant_types_supported") val grantTypesSupported: List<String> = emptyList(),
    @SerialName("code_challenge_methods_supported") val codeChallengeMethodsSupported: List<String> = emptyList(),
    @SerialName("id_token_signing_alg_values_supported") val idTokenSigningAlgValuesSupported: List<String> = emptyList(),
    @SerialName("subject_types_supported") val subjectTypesSupported: List<String> = emptyList(),
)

/** Claims from `/api/oauth/userinfo`, filtered by the granted scopes. */
@Serializable
public data class NemiUserInfo(
    @SerialName("sub") val sub: String,
    @SerialName("name") val name: String? = null,
    @SerialName("preferred_username") val preferredUsername: String? = null,
    @SerialName("picture") val picture: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("email_verified") val emailVerified: Boolean? = null,
)

/** The verified claims of an id_token. */
public data class IdTokenClaims(
    val issuer: String,
    val audience: List<String>,
    /** Pairwise subject: stable for one developer, different between developers. */
    val subject: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val authTime: Long?,
    val nonce: String?,
    val atHash: String?,
    val name: String?,
    val preferredUsername: String?,
    val picture: String?,
    val email: String?,
    val emailVerified: Boolean?,
    /** Every claim as sent. */
    val raw: JsonObject,
)

@Serializable
internal data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("scope") val scope: String? = null,
    @SerialName("id_token") val idToken: String? = null,
)

@Serializable
internal data class OAuthErrorBody(
    @SerialName("error") val error: String,
    @SerialName("error_description") val errorDescription: String? = null,
)
