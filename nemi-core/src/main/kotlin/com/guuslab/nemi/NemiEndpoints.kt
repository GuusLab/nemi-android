package com.guuslab.nemi

/** Where Nemi lives. [Production] matches the developer platform contract. */
public data class NemiEndpoints(
    val issuer: String,
    val authorization: String,
    val token: String,
    val revocation: String,
    val userInfo: String,
    val jwks: String,
    val discovery: String,
    val apiBase: String,
) {
    public companion object {
        @JvmField
        public val Production: NemiEndpoints = NemiEndpoints(
            issuer = "https://nemilab.com/oauth",
            authorization = "https://nemilab.com/oauth/authorize",
            token = "https://nemilab.com/api/oauth/token",
            revocation = "https://nemilab.com/api/oauth/revoke",
            userInfo = "https://nemilab.com/api/oauth/userinfo",
            jwks = "https://nemilab.com/oauth/jwks",
            discovery = "https://nemilab.com/oauth/.well-known/openid-configuration",
            apiBase = "https://nemilab.com/api/v1",
        )

        /** Builds endpoints from a discovery document, keeping [apiBase]. */
        public fun from(
            configuration: OpenIdConfiguration,
            apiBase: String = Production.apiBase,
        ): NemiEndpoints = NemiEndpoints(
            issuer = configuration.issuer,
            authorization = configuration.authorizationEndpoint,
            token = configuration.tokenEndpoint,
            revocation = configuration.revocationEndpoint ?: Production.revocation,
            userInfo = configuration.userinfoEndpoint ?: Production.userInfo,
            jwks = configuration.jwksUri,
            discovery = configuration.issuer.trimEnd('/') + "/.well-known/openid-configuration",
            apiBase = apiBase,
        )
    }
}

/** Scope names from the contract. */
public object NemiScope {
    public const val OPENID: String = "openid"
    public const val PROFILE: String = "profile"
    public const val EMAIL: String = "email"
    public const val OFFLINE_ACCESS: String = "offline_access"
    public const val FILES_READ: String = "files.read"
    public const val FILES_WRITE: String = "files.write"
    public const val GALLERY_READ: String = "gallery.read"
    public const val GALLERY_WRITE: String = "gallery.write"
    public const val DOCS_READ: String = "docs.read"
    public const val DOCS_WRITE: String = "docs.write"
    public const val SHEETS_READ: String = "sheets.read"
    public const val SHEETS_WRITE: String = "sheets.write"
    public const val FORMS_READ: String = "forms.read"
    public const val FORMS_WRITE: String = "forms.write"
    public const val CANVAS_READ: String = "canvas.read"
    public const val CANVAS_WRITE: String = "canvas.write"
    public const val CALENDAR_READ: String = "calendar.read"
    public const val CALENDAR_WRITE: String = "calendar.write"

    /** `openid profile email offline_access`: sign in and stay signed in. */
    @JvmField
    public val SIGN_IN: List<String> = listOf(OPENID, PROFILE, EMAIL, OFFLINE_ACCESS)
}

/** The `prompt` values Nemi supports on `/oauth/authorize`. */
public object NemiPrompt {
    /** Always show the consent screen, even when every scope was granted before. */
    public const val CONSENT: String = "consent"

    /**
     * Never show a screen. Fails with `login_required` or `consent_required` on the redirect
     * when the person would have to sign in or agree first. Useful for silent checks.
     */
    public const val NONE: String = "none"

    internal val ALL: Set<String> = setOf(CONSENT, NONE)
}
