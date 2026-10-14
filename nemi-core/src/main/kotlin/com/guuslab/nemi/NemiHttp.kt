package com.guuslab.nemi

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** SDK wide constants and the shared HTTP client. */
public object NemiSdk {
    public const val VERSION: String = "0.1.0"
    internal const val USER_AGENT: String = "nemi-kotlin/$VERSION"

    /**
     * The default OkHttp client. Pass your own (for example `NemiSdk.defaultHttpClient.newBuilder()...`)
     * to share connection pools or add logging. Never log the Authorization header.
     */
    @JvmStatic
    public val defaultHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(
                    if (request.header("User-Agent") == null) {
                        request.newBuilder().header("User-Agent", USER_AGENT).build()
                    } else {
                        request
                    },
                )
            }
            .build()
    }
}

/** The clock the SDK reads for expiry checks. Replace it in tests. */
public fun interface NemiClock {
    public fun nowMillis(): Long

    public companion object {
        @JvmField
        public val System: NemiClock = NemiClock { java.lang.System.currentTimeMillis() }
    }
}

internal val NemiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

/** Suspends on an OkHttp call, cancelling it when the coroutine is cancelled. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}

/** Parses `Retry-After` as delta seconds or an HTTP date. */
internal fun parseRetryAfterSeconds(value: String?, nowMillis: Long): Long? {
    if (value.isNullOrBlank()) return null
    value.trim().toLongOrNull()?.let { return it.coerceAtLeast(0) }
    return try {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT")
        val date = format.parse(value.trim()) ?: return null
        ((date.time - nowMillis) / 1000).coerceAtLeast(0)
    } catch (_: Exception) {
        null
    }
}
