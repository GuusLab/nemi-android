package com.guuslab.nemi.android

import androidx.annotation.ColorInt
import com.guuslab.nemi.NemiEndpoints
import com.guuslab.nemi.NemiScope

/**
 * App level settings for Sign in with Nemi.
 *
 * @param clientId `nemi_app_...` from dev.nemilab.com.
 * @param redirectUri registered exactly, for example `com.example.app:/oauth2redirect`. Its
 *   scheme must match `manifestPlaceholders["nemiRedirectScheme"]`.
 * @param scopes asked for by default.
 * @param toolbarColor Custom Tab toolbar color, or null for the browser default.
 */
public data class NemiAuthConfig @JvmOverloads constructor(
    val clientId: String,
    val redirectUri: String,
    val scopes: List<String> = NemiScope.SIGN_IN,
    val endpoints: NemiEndpoints = NemiEndpoints.Production,
    @ColorInt val toolbarColor: Int? = null,
) {
    init {
        require(clientId.isNotBlank()) { "clientId is required" }
        require(':' in redirectUri) { "redirectUri must be absolute, for example com.example.app:/oauth2redirect" }
    }
}

/** Per sign in overrides. Leave null to use [NemiAuthConfig.scopes]. */
public data class NemiSignInRequest @JvmOverloads constructor(
    val scopes: List<String>? = null,
    /** `consent` always shows the consent screen. */
    val prompt: String? = null,
)
