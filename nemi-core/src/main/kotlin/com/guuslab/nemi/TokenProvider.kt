package com.guuslab.nemi

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persists a person's tokens. On Android use `EncryptedTokenStore` from `nemi-android`. */
public interface NemiTokenStore {
    public suspend fun read(): NemiTokens?
    public suspend fun write(tokens: NemiTokens)
    public suspend fun clear()
}

/** Keeps tokens in memory only. Good for servers that hold tokens per request, and for tests. */
public class InMemoryTokenStore(initial: NemiTokens? = null) : NemiTokenStore {
    @Volatile
    private var tokens: NemiTokens? = initial

    override suspend fun read(): NemiTokens? = tokens
    override suspend fun write(tokens: NemiTokens) {
        this.tokens = tokens
    }

    override suspend fun clear() {
        tokens = null
    }
}

/** Supplies access tokens to [NemiClient]. */
public interface NemiTokenProvider {
    /** A valid access token, refreshing first when needed. */
    public suspend fun accessToken(): String

    /**
     * Called once after the API answered 401 for [rejectedAccessToken]. Return a new token
     * to retry with, or null to give up and surface the 401.
     */
    public suspend fun refreshAfterUnauthorized(rejectedAccessToken: String): String? = null
}

/** A fixed access token, for scripts and servers that manage refresh themselves. */
public class StaticTokenProvider(private val token: String) : NemiTokenProvider {
    override suspend fun accessToken(): String = token
}

/**
 * Reads tokens from a [NemiTokenStore] and refreshes them through [oauth] when they are about
 * to expire. Refreshes are single flight: concurrent callers wait on one mutex, and whoever
 * comes second sees the fresh token in the store instead of refreshing again. That matters
 * because refresh tokens rotate and presenting a rotated one revokes the whole chain.
 *
 * When the server answers `invalid_grant` the store is cleared and the person has to sign in again.
 */
public class RefreshingTokenProvider(
    private val oauth: NemiOAuthClient,
    private val store: NemiTokenStore,
    private val clock: NemiClock = NemiClock.System,
    /** Refresh this long before the access token expires. */
    private val refreshMarginMillis: Long = 60_000,
) : NemiTokenProvider {
    private val mutex = Mutex()

    override suspend fun accessToken(): String {
        val current = store.read() ?: throw notSignedIn()
        if (!current.isExpired(clock.nowMillis(), refreshMarginMillis)) return current.accessToken
        return mutex.withLock {
            val latest = store.read() ?: throw notSignedIn()
            if (!latest.isExpired(clock.nowMillis(), refreshMarginMillis)) {
                latest.accessToken
            } else {
                refreshLocked(latest).accessToken
            }
        }
    }

    override suspend fun refreshAfterUnauthorized(rejectedAccessToken: String): String? = mutex.withLock {
        val latest = store.read() ?: return@withLock null
        // Somebody else refreshed while this request was in flight.
        if (latest.accessToken != rejectedAccessToken) return@withLock latest.accessToken
        if (latest.refreshToken == null) return@withLock null
        refreshLocked(latest).accessToken
    }

    /** Forces a refresh now and returns the new tokens. */
    public suspend fun refreshNow(): NemiTokens = mutex.withLock {
        refreshLocked(store.read() ?: throw notSignedIn())
    }

    private suspend fun refreshLocked(tokens: NemiTokens): NemiTokens {
        val refreshToken = tokens.refreshToken ?: throw NemiOAuthException(
            NemiOAuthException.NOT_SIGNED_IN,
            "The access token expired and there is no refresh token (ask for offline_access)",
        )
        try {
            val fresh = oauth.refresh(refreshToken)
            store.write(fresh.copy(idToken = fresh.idToken ?: tokens.idToken))
            return fresh
        } catch (e: NemiOAuthException) {
            if (e.error == NemiOAuthException.INVALID_GRANT) store.clear()
            throw e
        }
    }

    private fun notSignedIn() = NemiOAuthException(NemiOAuthException.NOT_SIGNED_IN, "Nobody is signed in")
}
