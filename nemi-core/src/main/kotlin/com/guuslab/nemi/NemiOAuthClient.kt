package com.guuslab.nemi

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLDecoder

/**
 * The OAuth 2.0 / OpenID Connect client for "Sign in with Nemi".
 *
 * Works for public clients (Android, desktop, CLI: no secret, PKCE only) and confidential
 * clients (a server with [clientSecret]). PKCE S256 is always used.
 */
public class NemiOAuthClient(
    public val clientId: String,
    public val redirectUri: String,
    private val clientSecret: String? = null,
    public val endpoints: NemiEndpoints = NemiEndpoints.Production,
    private val httpClient: OkHttpClient = NemiSdk.defaultHttpClient,
    private val clock: NemiClock = NemiClock.System,
    /** Verifies id_tokens. Set to null only if you verify them yourself. */
    public val idTokenVerifier: IdTokenVerifier? = IdTokenVerifier(
        issuer = endpoints.issuer,
        clientId = clientId,
        jwksUri = endpoints.jwks,
        httpClient = httpClient,
        clock = clock,
    ),
) {
    /**
     * Creates a fresh state, nonce (when `openid` is asked) and PKCE pair, and the authorize URL
     * that uses them. Keep the returned request until the redirect comes back.
     */
    public fun createAuthorizationRequest(
        scopes: Collection<String> = NemiScope.SIGN_IN,
        prompt: String? = null,
        extraParameters: Map<String, String> = emptyMap(),
    ): AuthorizationRequest {
        val pkce = Pkce.generate()
        val state = Pkce.randomToken()
        val nonce = if (NemiScope.OPENID in scopes) Pkce.randomToken() else null
        val url = buildAuthorizeUrl(scopes, state, pkce.codeChallenge, nonce, prompt, extraParameters)
        return AuthorizationRequest(url, state, nonce, pkce.codeVerifier, redirectUri, scopes.toList())
    }

    /** Builds `GET /oauth/authorize` exactly as the contract describes it. */
    public fun buildAuthorizeUrl(
        scopes: Collection<String>,
        state: String,
        codeChallenge: String,
        nonce: String? = null,
        prompt: String? = null,
        extraParameters: Map<String, String> = emptyMap(),
    ): String {
        require(scopes.isNotEmpty()) { "Ask for at least one scope" }
        val builder = endpoints.authorization.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", scopes.joinToString(" "))
            .addQueryParameter("state", state)
        if (nonce != null) builder.addQueryParameter("nonce", nonce)
        builder.addQueryParameter("code_challenge", codeChallenge)
            .addQueryParameter("code_challenge_method", Pkce.METHOD)
        if (prompt != null) builder.addQueryParameter("prompt", prompt)
        extraParameters.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    /**
     * Parses the redirect (`com.example.app:/oauth2redirect?code=...&state=...&iss=...`).
     * Checks the state in constant time and the `iss` parameter (RFC 9207), and turns
     * `?error=access_denied` into a [NemiOAuthException].
     */
    public fun parseCallback(callbackUri: String, expectedState: String): AuthorizationCallback {
        val params = queryParameters(callbackUri)
        val state = params["state"]
        if (!constantTimeEquals(state, expectedState)) {
            throw NemiOAuthException(NemiOAuthException.INVALID_STATE, "The state on the redirect does not match")
        }
        params["error"]?.let { throw NemiOAuthException(it, params["error_description"]) }
        val iss = params["iss"]
        if (iss != null && iss != endpoints.issuer) {
            throw NemiOAuthException(NemiOAuthException.INVALID_ISSUER, "Unexpected issuer $iss")
        }
        val code = params["code"]
            ?: throw NemiOAuthException(NemiOAuthException.INVALID_REQUEST, "The redirect has no code")
        return AuthorizationCallback(code, state!!, iss)
    }

    /** Handles a redirect for [request]: checks it, exchanges the code and verifies the id_token. */
    public suspend fun handleCallback(callbackUri: String, request: AuthorizationRequest): NemiCredentials {
        val callback = parseCallback(callbackUri, request.state)
        return exchangeCode(callback.code, request.codeVerifier, request.nonce, request.redirectUri)
    }

    /** `grant_type=authorization_code`. Verifies the id_token (signature, iss, aud, exp, nonce, at_hash). */
    public suspend fun exchangeCode(
        code: String,
        codeVerifier: String,
        nonce: String? = null,
        redirectUri: String = this.redirectUri,
    ): NemiCredentials {
        val tokens = tokenRequest(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "code_verifier" to codeVerifier,
        )
        val claims = tokens.idToken?.let { idToken ->
            idTokenVerifier?.verify(idToken, expectedNonce = nonce, accessToken = tokens.accessToken)
        }
        return NemiCredentials(tokens, claims)
    }

    /**
     * `grant_type=refresh_token`. Refresh tokens rotate on every use: store the returned
     * tokens and drop the old refresh token. Reusing a rotated token revokes the whole chain.
     */
    public suspend fun refresh(refreshToken: String): NemiTokens {
        val tokens = tokenRequest("grant_type" to "refresh_token", "refresh_token" to refreshToken)
        tokens.idToken?.let { idTokenVerifier?.verify(it, expectedNonce = null, accessToken = tokens.accessToken) }
        return if (tokens.refreshToken == null) tokens.copy(refreshToken = refreshToken) else tokens
    }

    /** RFC 7009 revoke. Revoking a refresh token ends the whole grant's session for that login. */
    public suspend fun revoke(token: String, tokenTypeHint: String? = null) {
        val form = FormBody.Builder().add("token", token)
        if (tokenTypeHint != null) form.add("token_type_hint", tokenTypeHint)
        addClientAuth(form)
        val request = Request.Builder().url(endpoints.revocation).post(form.build()).build()
        httpClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw oauthError(response.code, response.body?.string())
        }
    }

    /** `GET /api/oauth/userinfo`. */
    public suspend fun userInfo(accessToken: String): NemiUserInfo {
        val request = Request.Builder().url(endpoints.userInfo)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .build()
        return httpClient.newCall(request).await().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw oauthError(response.code, body)
            NemiJson.decodeFromString(NemiUserInfo.serializer(), body)
        }
    }

    /** Fetches the discovery document. */
    public suspend fun discovery(): OpenIdConfiguration = fetchDiscovery(endpoints.discovery, httpClient)

    private suspend fun tokenRequest(vararg params: Pair<String, String>): NemiTokens {
        val form = FormBody.Builder()
        params.forEach { (key, value) -> form.add(key, value) }
        addClientAuth(form)
        val request = Request.Builder().url(endpoints.token)
            .header("Accept", "application/json")
            .post(form.build())
            .build()
        val requestedAt = clock.nowMillis()
        return httpClient.newCall(request).await().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw oauthError(response.code, body)
            val parsed = try {
                NemiJson.decodeFromString(TokenResponse.serializer(), body)
            } catch (e: Exception) {
                throw NemiOAuthException(NemiOAuthException.SERVER_ERROR, "Unreadable token response", response.code, e)
            }
            NemiTokens(
                accessToken = parsed.accessToken,
                tokenType = parsed.tokenType,
                expiresAt = requestedAt + parsed.expiresIn * 1000,
                refreshToken = parsed.refreshToken,
                scope = parsed.scope,
                idToken = parsed.idToken,
            )
        }
    }

    private fun addClientAuth(form: FormBody.Builder) {
        form.add("client_id", clientId)
        if (clientSecret != null) form.add("client_secret", clientSecret)
    }

    public companion object {
        /** Fetches the discovery document for [issuer] (default: production). */
        public suspend fun discover(
            issuer: String = NemiEndpoints.Production.issuer,
            httpClient: OkHttpClient = NemiSdk.defaultHttpClient,
        ): OpenIdConfiguration = fetchDiscovery(issuer.trimEnd('/') + "/.well-known/openid-configuration", httpClient)

        private suspend fun fetchDiscovery(url: String, httpClient: OkHttpClient): OpenIdConfiguration {
            val request = Request.Builder().url(url).header("Accept", "application/json").build()
            return httpClient.newCall(request).await().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw oauthError(response.code, body)
                NemiJson.decodeFromString(OpenIdConfiguration.serializer(), body)
            }
        }

        internal fun oauthError(status: Int, body: String?): NemiOAuthException {
            val parsed = try {
                body?.let { NemiJson.decodeFromString(OAuthErrorBody.serializer(), it) }
            } catch (_: Exception) {
                null
            }
            return if (parsed != null) {
                NemiOAuthException(parsed.error, parsed.errorDescription, status)
            } else {
                val error = if (status == 401) NemiOAuthException.INVALID_CLIENT else NemiOAuthException.SERVER_ERROR
                NemiOAuthException(error, "HTTP $status", status)
            }
        }

        internal fun queryParameters(uri: String): Map<String, String> {
            val raw = try {
                URI(uri).rawQuery
            } catch (_: Exception) {
                uri.substringAfter('?', "").substringBefore('#')
            } ?: return emptyMap()
            return raw.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
            }
        }
    }
}
