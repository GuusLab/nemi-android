package com.guuslab.nemi

import java.security.MessageDigest
import java.security.SecureRandom

/** A PKCE pair (RFC 7636). Nemi only accepts the `S256` method. */
public data class PkcePair(
    val codeVerifier: String,
    val codeChallenge: String,
) {
    val codeChallengeMethod: String get() = Pkce.METHOD

    override fun toString(): String = "PkcePair(codeChallenge=$codeChallenge)"
}

/** PKCE S256 helpers. */
public object Pkce {
    public const val METHOD: String = "S256"
    private val random = SecureRandom()

    /** Creates a fresh verifier (43 characters, 256 bits of entropy) and its S256 challenge. */
    public fun generate(): PkcePair {
        val verifier = randomToken(32)
        return PkcePair(verifier, challengeFor(verifier))
    }

    /** `base64url(sha256(ascii(verifier)))` without padding. */
    public fun challengeFor(codeVerifier: String): String {
        require(codeVerifier.length in 43..128) { "A PKCE code_verifier must be 43 to 128 characters" }
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return Base64Url.encode(digest)
    }

    /** A random base64url string built from [bytes] random bytes, used for state and nonce too. */
    public fun randomToken(bytes: Int = 32): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return Base64Url.encode(buffer)
    }
}
