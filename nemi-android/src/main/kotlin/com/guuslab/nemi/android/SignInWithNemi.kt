package com.guuslab.nemi.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.guuslab.nemi.IdTokenClaims
import com.guuslab.nemi.NemiOAuthClient
import com.guuslab.nemi.NemiOAuthException
import com.guuslab.nemi.NemiTokens

/** What the browser step produced. Pass it to [NemiAuth.completeSignIn]. */
public sealed class NemiAuthorizationResult {
    /** The browser redirected back. The code is not exchanged yet. */
    public class Redirected internal constructor(public val callbackUri: String) : NemiAuthorizationResult() {
        override fun toString(): String = "Redirected"
    }

    /** The person closed the tab or pressed back. */
    public object Cancelled : NemiAuthorizationResult()

    /** The flow could not start, for example because no browser is installed. */
    public class Failed(public val error: Throwable) : NemiAuthorizationResult()
}

/** The end result of a sign in. */
public sealed class NemiSignInResult {
    /** Signed in. The tokens are already saved in the token store. */
    public data class SignedIn(val tokens: NemiTokens, val idToken: IdTokenClaims?) : NemiSignInResult()

    /** The person closed the tab without deciding. */
    public object Cancelled : NemiSignInResult()

    /**
     * Something went wrong. A [NemiOAuthException] with error `access_denied` means the
     * person pressed Deny on the consent screen.
     */
    public data class Failed(val error: Throwable) : NemiSignInResult() {
        val isAccessDenied: Boolean
            get() = (error as? NemiOAuthException)?.error == NemiOAuthException.ACCESS_DENIED
    }
}

/**
 * `ActivityResultContract` that runs the browser part of Sign in with Nemi.
 *
 * ```
 * private val signIn = registerForActivityResult(SignInWithNemi(config)) { result ->
 *     lifecycleScope.launch { handle(nemiAuth.completeSignIn(result)) }
 * }
 * signIn.launch(null)
 * ```
 *
 * [createIntent] creates the state, nonce and PKCE verifier and persists them (encrypted)
 * before the browser opens, so the result can still be completed after process death.
 */
public class SignInWithNemi(private val config: NemiAuthConfig) :
    ActivityResultContract<NemiSignInRequest?, NemiAuthorizationResult>() {

    override fun createIntent(context: Context, input: NemiSignInRequest?): Intent {
        val oauth = NemiOAuthClient(
            clientId = config.clientId,
            redirectUri = config.redirectUri,
            endpoints = config.endpoints,
            idTokenVerifier = null,
        )
        val request = oauth.createAuthorizationRequest(
            scopes = input?.scopes ?: config.scopes,
            prompt = input?.prompt,
        )
        PendingAuthorizationStore(context).save(request)
        return NemiAuthorizationActivity.startIntent(context, request.url, config.toolbarColor)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): NemiAuthorizationResult {
        val data = intent?.data
        return when {
            resultCode == Activity.RESULT_OK && data != null -> NemiAuthorizationResult.Redirected(data.toString())
            resultCode == NemiAuthorizationActivity.RESULT_NO_BROWSER -> NemiAuthorizationResult.Failed(
                NemiOAuthException("no_browser", "No browser is installed to show the Nemi consent screen"),
            )
            else -> NemiAuthorizationResult.Cancelled
        }
    }
}
