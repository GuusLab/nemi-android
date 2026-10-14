package com.guuslab.nemi.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Invisible activity that owns one sign in: it opens the Custom Tab, waits, and returns
 * either the redirect URI (RESULT_OK) or RESULT_CANCELED when the person closed the tab.
 *
 * The state survives process death: the authorize URL comes from the launching intent,
 * whether the tab was opened is kept in the saved instance state, and the PKCE verifier
 * lives in [PendingAuthorizationStore].
 */
public class NemiAuthorizationActivity : ComponentActivity() {
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launched = savedInstanceState?.getBoolean(KEY_LAUNCHED) ?: false
        if (intent.getStringExtra(EXTRA_AUTHORIZE_URL) == null && intent.data == null) {
            // Started without a flow (for example from a stale task). Nothing to do.
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The redirect arrives here through NemiRedirectActivity; onResume handles it.
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        val redirect = intent.data
        when {
            redirect != null -> {
                setResult(Activity.RESULT_OK, Intent().setData(redirect))
                finish()
            }
            !launched -> openBrowser()
            // Back in front without a redirect: the person closed the tab.
            else -> {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LAUNCHED, launched)
    }

    private fun openBrowser() {
        val url = intent.getStringExtra(EXTRA_AUTHORIZE_URL) ?: return finish()
        val builder = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setUrlBarHidingEnabled(false)
        if (intent.hasExtra(EXTRA_TOOLBAR_COLOR)) {
            builder.setDefaultColorSchemeParams(
                CustomTabColorSchemeParams.Builder().setToolbarColor(intent.getIntExtra(EXTRA_TOOLBAR_COLOR, 0)).build(),
            )
        }
        try {
            builder.build().launchUrl(this, Uri.parse(url))
            launched = true
        } catch (_: ActivityNotFoundException) {
            setResult(RESULT_NO_BROWSER)
            finish()
        }
    }

    internal companion object {
        private const val EXTRA_AUTHORIZE_URL = "com.guuslab.nemi.AUTHORIZE_URL"
        private const val EXTRA_TOOLBAR_COLOR = "com.guuslab.nemi.TOOLBAR_COLOR"
        private const val KEY_LAUNCHED = "com.guuslab.nemi.LAUNCHED"

        /** Result code when no browser can open the consent screen. */
        const val RESULT_NO_BROWSER: Int = Activity.RESULT_FIRST_USER + 1

        fun startIntent(context: Context, authorizeUrl: String, toolbarColor: Int?): Intent =
            Intent(context, NemiAuthorizationActivity::class.java).apply {
                putExtra(EXTRA_AUTHORIZE_URL, authorizeUrl)
                if (toolbarColor != null) putExtra(EXTRA_TOOLBAR_COLOR, toolbarColor)
            }

        fun redirectIntent(context: Context, redirect: Uri): Intent =
            Intent(context, NemiAuthorizationActivity::class.java)
                .setData(redirect)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
