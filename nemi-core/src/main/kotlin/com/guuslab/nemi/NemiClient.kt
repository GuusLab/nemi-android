package com.guuslab.nemi

import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The Nemi REST API v1 (`https://nemilab.com/api/v1`).
 *
 * Every call is a suspend function. Errors are thrown as [NemiApiException]. A 429 is retried
 * up to [maxRateLimitRetries] times after waiting for `Retry-After`. A 401 asks the
 * [tokenProvider] for a fresh token once and retries.
 *
 * ```
 * val nemi = NemiClient(RefreshingTokenProvider(oauth, store))
 * val me = nemi.me()
 * val files = nemi.files.list(workspaceId = nemi.workspaces().first().id)
 * ```
 */
public class NemiClient(
    private val tokenProvider: NemiTokenProvider,
    baseUrl: String = NemiEndpoints.Production.apiBase,
    internal val httpClient: OkHttpClient = NemiSdk.defaultHttpClient,
    private val maxRateLimitRetries: Int = 2,
    /** Upper bound for one `Retry-After` wait. */
    private val maxRetryDelayMillis: Long = 60_000,
    private val clock: NemiClock = NemiClock.System,
) {
    /** For a server or script that already holds an access token. */
    public constructor(accessToken: String) : this(StaticTokenProvider(accessToken))

    private val baseUrl: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    public val files: FilesApi = FilesApi(this)
    public val folders: FoldersApi = FoldersApi(this)
    public val gallery: GalleryApi = GalleryApi(this)
    public val docs: DocsApi = DocsApi(this)
    public val sheets: SheetsApi = SheetsApi(this)
    public val forms: FormsApi = FormsApi(this)
    public val canvas: CanvasApi = CanvasApi(this)
    public val calendar: CalendarApi = CalendarApi(this)

    /** `GET /me`: the signed in person. Works with any scope. */
    public suspend fun me(): NemiUser = get("me", NemiUser.serializer())

    /** `GET /workspaces`: needs any `*.read` scope. */
    public suspend fun workspaces(): List<NemiWorkspace> =
        get("workspaces", WorkspacesResponse.serializer()).workspaces

    // Plumbing shared by the namespaces.

    internal suspend fun <T> get(
        path: String,
        serializer: KSerializer<T>,
        query: List<Pair<String, Any?>> = emptyList(),
    ): T = decode(serializer, execute("GET", path, query, null))

    internal suspend fun <B, T> send(
        method: String,
        path: String,
        bodySerializer: KSerializer<B>,
        body: B,
        serializer: KSerializer<T>,
    ): T = decode(serializer, execute(method, path, emptyList(), jsonBody(bodySerializer, body)))

    internal suspend fun <B> sendUnit(method: String, path: String, bodySerializer: KSerializer<B>, body: B) {
        execute(method, path, emptyList(), jsonBody(bodySerializer, body))
    }

    internal suspend fun delete(path: String) {
        execute("DELETE", path, emptyList(), null)
    }

    internal suspend fun <T> list(
        path: String,
        key: String,
        serializer: KSerializer<T>,
        query: List<Pair<String, Any?>> = emptyList(),
    ): List<T> = decodeList(execute("GET", path, query, null), key, serializer)

    internal suspend fun getObject(path: String): JsonObject =
        NemiJson.parseToJsonElement(execute("GET", path, emptyList(), null)).jsonObject

    internal suspend fun execute(
        method: String,
        path: String,
        query: List<Pair<String, Any?>>,
        body: RequestBody?,
    ): String {
        val url = baseUrl.newBuilder().apply {
            path.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
            query.forEach { (key, value) -> if (value != null) addQueryParameter(key, value.toString()) }
        }.build()

        var token = tokenProvider.accessToken()
        var refreshedAfter401 = false
        var rateLimitRetries = 0
        while (true) {
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .method(method, body ?: if (method == "POST" || method == "PATCH") EMPTY_JSON else null)
                .build()
            httpClient.newCall(request).await().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) return text

                if (response.code == 401 && !refreshedAfter401) {
                    refreshedAfter401 = true
                    val fresh = tokenProvider.refreshAfterUnauthorized(token)
                    if (fresh != null) {
                        token = fresh
                        return@use
                    }
                }
                val retryAfter = parseRetryAfterSeconds(response.header("Retry-After"), clock.nowMillis())
                if (response.code == 429 && rateLimitRetries < maxRateLimitRetries) {
                    rateLimitRetries++
                    delay(((retryAfter ?: 1L) * 1000).coerceAtMost(maxRetryDelayMillis))
                    return@use
                }
                throw apiError(response.code, text, retryAfter)
            }
        }
    }

    internal fun <T> decode(serializer: KSerializer<T>, text: String): T = try {
        NemiJson.decodeFromString(serializer, text)
    } catch (e: Exception) {
        throw NemiApiException(200, "invalid_response", "Unexpected response: ${e.message}")
    }

    internal fun <B> jsonBody(serializer: KSerializer<B>, body: B): RequestBody =
        NemiJson.encodeToString(serializer, body).toRequestBody(JSON)

    internal companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_JSON = "{}".toRequestBody(JSON)

        fun apiError(status: Int, body: String?, retryAfterSeconds: Long?): NemiApiException {
            val parsed = try {
                body?.takeIf { it.isNotBlank() }?.let { NemiJson.decodeFromString(ApiErrorEnvelope.serializer(), it).error }
            } catch (_: Exception) {
                null
            }
            val code = parsed?.code ?: NemiApiException.codeForStatus(status)
            return NemiApiException(
                status = status,
                code = code,
                message = parsed?.message ?: "HTTP $status ($code)",
                requiredScope = parsed?.requiredScope,
                retryAfterSeconds = retryAfterSeconds,
            )
        }

        /**
         * Lists come back as `{ "<key>": [...] }`. Accept a bare array or another wrapper key too,
         * so a small server side change does not break apps in the field.
         */
        fun <T> decodeList(text: String, key: String, serializer: KSerializer<T>): List<T> {
            val array = when (val element = NemiJson.parseToJsonElement(text)) {
                is JsonArray -> element
                is JsonObject -> element[key] as? JsonArray ?: element.values.firstOrNull { it is JsonArray } as? JsonArray
                else -> null
            } ?: return emptyList()
            return try {
                array.map { NemiJson.decodeFromJsonElement(serializer, it) }
            } catch (e: Exception) {
                throw NemiApiException(200, "invalid_response", "Unexpected response: ${e.message}")
            }
        }
    }
}
