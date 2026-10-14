package com.guuslab.nemi.android

import android.annotation.SuppressLint
import android.content.Context
import com.guuslab.nemi.AuthorizationRequest

/**
 * Keeps the state, nonce and PKCE verifier of the sign in in progress, encrypted on disk,
 * so the flow still completes when Android kills the app while the browser is open.
 *
 * Writes use commit(): the request must be on disk before the browser opens, because the
 * process can be killed right after.
 */
@SuppressLint("ApplySharedPref")
internal class PendingAuthorizationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nemi_pending_authorization", Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher("com.guuslab.nemi.pending")

    fun save(request: AuthorizationRequest) {
        prefs.edit()
            .putString(KEY, cipher.encrypt(request.toJson()))
            .putLong(KEY_CREATED, System.currentTimeMillis())
            .commit()
    }

    /** The pending request, or null when there is none or it is older than [MAX_AGE_MILLIS]. */
    fun load(): AuthorizationRequest? {
        val encrypted = prefs.getString(KEY, null) ?: return null
        if (System.currentTimeMillis() - prefs.getLong(KEY_CREATED, 0) > MAX_AGE_MILLIS) {
            clear()
            return null
        }
        return cipher.decrypt(encrypted)?.let { runCatching { AuthorizationRequest.fromJson(it) }.getOrNull() }
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val KEY = "request"
        const val KEY_CREATED = "created_at"
        const val MAX_AGE_MILLIS = 30 * 60 * 1000L
    }
}
