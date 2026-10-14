package com.guuslab.nemi

/** Base class for every error this SDK throws on purpose. */
public open class NemiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * An OAuth or OpenID Connect failure: an RFC 6749 error from the token endpoint,
 * an error on the redirect (`access_denied`), a state mismatch or an id_token that
 * failed verification (`invalid_id_token`).
 */
public class NemiOAuthException(
    /** RFC 6749 error code, for example `invalid_grant` or `access_denied`. */
    public val error: String,
    /** Human readable detail from the server, when there is one. */
    public val errorDescription: String? = null,
    /** HTTP status, when the error came from an HTTP response. */
    public val status: Int? = null,
    cause: Throwable? = null,
) : NemiException(if (errorDescription != null) "$error: $errorDescription" else error, cause) {
    public companion object {
        public const val ACCESS_DENIED: String = "access_denied"
        public const val INVALID_GRANT: String = "invalid_grant"
        public const val INVALID_SCOPE: String = "invalid_scope"
        public const val INVALID_CLIENT: String = "invalid_client"
        public const val INVALID_REQUEST: String = "invalid_request"
        public const val INVALID_STATE: String = "invalid_state"
        public const val INVALID_ID_TOKEN: String = "invalid_id_token"
        public const val INVALID_ISSUER: String = "invalid_issuer"
        public const val NOT_SIGNED_IN: String = "not_signed_in"
        public const val SERVER_ERROR: String = "server_error"
    }
}

/** An error from the REST API (`{ "error": { "code", "message", "required_scope" } }`). */
public class NemiApiException(
    /** HTTP status code. */
    public val status: Int,
    /** Error code, for example `insufficient_scope`, `not_found` or `rate_limited`. */
    public val code: String,
    message: String,
    /** The scope the call needs, for `insufficient_scope`. */
    public val requiredScope: String? = null,
    /** Seconds from `Retry-After`, for `rate_limited`. */
    public val retryAfterSeconds: Long? = null,
) : NemiException(message) {
    public companion object {
        public const val INVALID_REQUEST: String = "invalid_request"
        public const val INVALID_TOKEN: String = "invalid_token"
        public const val INSUFFICIENT_SCOPE: String = "insufficient_scope"
        public const val FORBIDDEN: String = "forbidden"
        public const val NOT_FOUND: String = "not_found"
        public const val CONFLICT: String = "conflict"
        public const val TOO_LARGE: String = "too_large"
        public const val RATE_LIMITED: String = "rate_limited"
        public const val SERVER_ERROR: String = "server_error"

        /** The code the contract assigns to an HTTP status, used when the body has none. */
        public fun codeForStatus(status: Int): String = when (status) {
            400 -> INVALID_REQUEST
            401 -> INVALID_TOKEN
            403 -> FORBIDDEN
            404 -> NOT_FOUND
            409 -> CONFLICT
            413 -> TOO_LARGE
            429 -> RATE_LIMITED
            else -> SERVER_ERROR
        }
    }
}
