package com.guuslab.nemi.android

import android.content.Context
import com.guuslab.nemi.NemiClient
import com.guuslab.nemi.NemiOAuthClient
import com.guuslab.nemi.NemiOAuthException
import com.guuslab.nemi.NemiSdk
import com.guuslab.nemi.NemiTokenStore
import com.guuslab.nemi.NemiTokens
import com.guuslab.nemi.NemiUserInfo
import com.guuslab.nemi.RefreshingTokenProvider
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient

/**
 * Sign in with Nemi on Android. Create one per app (for example in your Application or DI
 * graph) and share it.
 *
 * ```
 * val nemiAuth = NemiAuth(context, NemiAuthConfig(
 *     clientId = "nemi_app_...",
 *     redirectUri = "com.example.app:/oauth2redirect",
 *     scopes = NemiScope.SIGN_IN + NemiScope.GALLERY_READ,
 * ))
 * ```
 *
 * Use [signInContract] (or `rememberSignInWithNemi` in Compose) to start, [completeSignIn]
 * to finish, then call the API through [client]. Tokens refresh on their own.
 */
public class NemiAuth @JvmOverloads constructor(
    context: Context,
    public val config: NemiAuthConfig,
    public val tokenStore: NemiTokenStore = EncryptedTokenStore(context),
    httpClient: OkHttpClient = NemiSdk.defaultHttpClient,
) {
    private val appContext = context.applicationContext
    private val pending = PendingAuthorizationStore(appContext)

    /** The OAuth client for the low level calls (userinfo, revoke, discovery). */
    public val oauth: NemiOAuthClient = NemiOAuthClient(
        clientId = config.clientId,
        redirectUri = config.redirectUri,
        endpoints = config.endpoints,
        httpClient = httpClient,
    )

    /** Hands out access tokens and refreshes them once at a time. */
    public val tokenProvider: RefreshingTokenProvider = RefreshingTokenProvider(oauth, tokenStore)

    /** The REST API, signed in as the current person. */
    public val client: NemiClient = NemiClient(tokenProvider, config.endpoints.apiBase, httpClient)

    /** A new contract for `registerForActivityResult`. */
    public fun signInContract(): SignInWithNemi = SignInWithNemi(config)

    /**
     * Finishes a sign in: checks the state and issuer, exchanges the code with the stored
     * PKCE verifier, verifies the id_token and saves the tokens. Never throws (except for
     * cancellation); failures come back as [NemiSignInResult.Failed].
     */
    public suspend fun completeSignIn(result: NemiAuthorizationResult): NemiSignInResult = when (result) {
        is NemiAuthorizationResult.Cancelled -> {
            pending.clear()
            NemiSignInResult.Cancelled
        }
        is NemiAuthorizationResult.Failed -> {
            pending.clear()
            NemiSignInResult.Failed(result.error)
        }
        is NemiAuthorizationResult.Redirected -> try {
            val request = pending.load() ?: throw NemiOAuthException(
                NemiOAuthException.INVALID_STATE,
                "No sign in is in progress (it expired or was already completed)",
            )
            val credentials = try {
                oauth.handleCallback(result.callbackUri, request)
            } finally {
                pending.clear()
            }
            tokenStore.write(credentials.tokens)
            NemiSignInResult.SignedIn(credentials.tokens, credentials.idToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NemiSignInResult.Failed(e)
        }
    }

    /** True when tokens are stored. They may still need a refresh. */
    public suspend fun isSignedIn(): Boolean = tokenStore.read() != null

    /** The stored tokens, or null. */
    public suspend fun tokens(): NemiTokens? = tokenStore.read()

    /** `GET /api/oauth/userinfo` for the current person. */
    public suspend fun userInfo(): NemiUserInfo = oauth.userInfo(tokenProvider.accessToken())

    /**
     * Signs out: revokes the refresh token (ending the grant's session for this login) when
     * [revoke] is true, then deletes the stored tokens. Local tokens are deleted even if the
     * network call fails.
     */
    public suspend fun signOut(revoke: Boolean = true) {
        val tokens = tokenStore.read()
        try {
            if (revoke && tokens != null) {
                val refresh = tokens.refreshToken
                if (refresh != null) oauth.revoke(refresh, "refresh_token") else oauth.revoke(tokens.accessToken, "access_token")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort; the local session ends regardless.
        } finally {
            tokenStore.clear()
        }
    }
}
