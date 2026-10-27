@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.model
import android.content.Context
import cc.noharry.agent.runtime.WebFallbackPage
import cc.noharry.agent.runtime.WebFetchFallback
import cc.noharry.agent.runtime.WebSearchProvider
import cc.noharry.agent.runtime.WebSearchResult
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * DeepSeek's Search and Fetch APIs (api-docs.deepseek.com/zh-cn/guides/search) behind the runtime's web_search tool
 * (owner 2026-10-17): unlike the provider's server-side search, the queries and results reach the model, the event
 * log and the card. The Fetch API also gets pages that refuse the device, for web_fetch ([FetchFallback]). 5 QPS per
 * account, so a refused request (429) or a busy one is tried again a little later.
 */
internal class DeepSeekWebSearch(
    private val apiKey: () -> String,
    private val baseUrl: String = DEEPSEEK_TOOLS_URL,
    private val client: OkHttpClient = SEARCH_CLIENT,
) : WebSearchProvider, WebFetchFallback {
    /** DeepSeek's host, or the relay's a check found the APIs behind ([DeepSeekProbes]). */
    override val label: String = runCatching { java.net.URI(baseUrl).host }.getOrNull() ?: DEEPSEEK_HOST

    override suspend fun search(query: String, count: Int): List<WebSearchResult> {
        val body = buildJsonObject {
            put("query", query)
            put("count", count)
        }
        val results = post("search", body.toString())?.get("results")?.jsonArray ?: throw IOException("Search answered without results.")
        return results.map { it.jsonObject }.map { item ->
            WebSearchResult(
                title = item.text("title"),
                url = item.text("url"),
                summary = item.text("summary").ifBlank { item.text("snippet") },
                site = item.text("site_name").ifBlank { null },
                date = item.text("date").ifBlank { null },
            )
        }.filter { it.url.isNotBlank() }
    }

    override suspend fun fetch(url: String): WebFallbackPage? {
        val page = try {
            post("fetch", buildJsonObject { put("url", url) }.toString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        val content = page.text("content").ifBlank { return null }
        return WebFallbackPage(page.text("title"), content)
    }

    /** The answer's JSON, null for a page that is not there (404), or an exception the tool reports. */
    private suspend fun post(path: String, json: String): JsonObject? {
        for (attempt in 0..RETRIES) {
            if (attempt > 0) delay(RETRY_DELAY_MS * attempt)
            val request = Request.Builder()
                .url("$baseUrl/$path")
                .header("Authorization", "Bearer ${apiKey()}")
                .post(json.toRequestBody(JSON_TYPE))
                .build()
            val (code, text) = client.newCall(request).awaitText()
            when {
                code in HTTP_SUCCESS -> return Json.parseToJsonElement(text).jsonObject
                code == NOT_FOUND -> return null
                code !in RETRIED_CODES || attempt == RETRIES -> throw IOException("DeepSeek $path $code: ${text.errorMessage()}")
            }
        }
        throw IOException("DeepSeek $path did not answer.")
    }
}

/**
 * The answer's status and body. Cancelling the caller cancels the request, so the runtime's search and fallback
 * timeouts hold; a blocking execute() would have kept the tool waiting up to this client's 40 s per attempt.
 */
private suspend fun Call.awaitText(): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = runCatching { response.use { it.code to it.body?.string().orEmpty() } }
            if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
        }
    })
}

private val HTTP_SUCCESS = 200..299

/**
 * How each provider profile searches: the provider's server-side search (default) or DeepSeek's Search API
 * ([DeepSeekWebSearch]). Kept per profile id, beside the profile settings rather than in them.
 */
internal object SearchBackend {
    private val state = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Profile id → whether it searches through the API, for the Settings page. */
    val usesApi: StateFlow<Map<String, Boolean>> = state.asStateFlow()

    fun load(context: Context): Map<String, Boolean> =
        context.getSharedPreferences(SEARCH_PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (id, value) -> (value as? Boolean)?.let { id to it } }.toMap()
            .also { state.value = it }

    fun usesApi(context: Context, profileId: String): Boolean = load(context)[profileId] == true

    fun set(context: Context, profileId: String, api: Boolean) {
        context.getSharedPreferences(SEARCH_PREFS, Context.MODE_PRIVATE).edit().putBoolean(profileId, api).apply()
        load(context)
    }
}

/**
 * The search for a run on [profile]: DeepSeek's Search API when the profile is set to it (or the run is a subagent
 * and [forceApi]) and a DeepSeek key is at hand (the profile's own, else another DeepSeek profile's), otherwise null
 * and the provider's own search.
 */
internal fun appWebSearch(
    context: Context,
    config: ProviderConfig,
    profile: ProviderProfile,
    forceApi: Boolean = false,
): DeepSeekWebSearch? {
    if (!forceApi && !SearchBackend.usesApi(context, profile.id)) return null
    return deepSeekTools(context, config, profile)
}

/**
 * Whether web_fetch gets a page through DeepSeek's Fetch API when the site refused the device or the request failed
 * (owner 2026-10-18: 拆成独立选项，主 agent 也走 Fetch 兜底). One switch for the main agent and subagents, apart from how
 * they search; on by default, since it costs only when a page was refused (携程 answers the device with 432).
 */
internal object FetchFallback {
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun set(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FETCH_FALLBACK_PREFS, Context.MODE_PRIVATE)
}

/** The fetch fallback for a run on [profile]: DeepSeek's Fetch API when switched on and a DeepSeek key is at hand. */
internal fun appWebFetchFallback(context: Context, config: ProviderConfig, profile: ProviderProfile): DeepSeekWebSearch? =
    if (FetchFallback.enabled(context)) deepSeekTools(context, config, profile) else null

/** Whether any profile has a DeepSeek key for the Search and Fetch APIs. */
internal fun ProviderConfig.hasDeepSeekKey(context: Context): Boolean = profiles.any { deepSeekToolsUrl(context, it) != null }

/** DeepSeek's tools with [profile]'s key, else another profile's; null without one. */
private fun deepSeekTools(context: Context, config: ProviderConfig, profile: ProviderProfile): DeepSeekWebSearch? =
    (listOf(profile) + config.profiles).firstNotNullOfOrNull { config.deepSeekToolsOf(context, it) }

/**
 * DeepSeek's Search and Fetch APIs with [profile]'s key: on DeepSeek's own host, or behind a relay where the check
 * ([DeepSeekProbes]) found them for the base URL and key the profile has now; null otherwise.
 */
internal fun ProviderConfig.deepSeekToolsOf(context: Context, profile: ProviderProfile): DeepSeekWebSearch? {
    val url = deepSeekToolsUrl(context, profile) ?: return null
    if (profile.isDeepSeek()) return DeepSeekWebSearch(apiKey = { effectiveApiKey(profile) })
    return DeepSeekWebSearch(apiKey = { effectiveApiKey(profile) }, baseUrl = url, client = deepSeekRelayClient(context))
}

private fun ProviderConfig.deepSeekToolsUrl(context: Context, profile: ProviderProfile): String? {
    val key = effectiveApiKey(profile).ifBlank { return null }
    return if (profile.isDeepSeek()) DEEPSEEK_TOOLS_URL else DeepSeekProbes.current(context, profile, key)?.tools?.url
}

internal fun ProviderProfile.isDeepSeek(): Boolean = baseUrl.isDeepSeekUrl()

/** DeepSeek's own API (any of its endpoints), where search, fetch and the balance query work with the same key. */
internal fun String.isDeepSeekUrl(): Boolean = runCatching { java.net.URI(trim()).host }.getOrNull() == DEEPSEEK_HOST

private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun String.errorMessage(): String =
    runCatching { Json.parseToJsonElement(this).jsonObject["error"]?.jsonObject?.text("message") }.getOrNull()?.ifBlank { null } ?: take(MAX_ERROR_CHARS)

private const val DEEPSEEK_HOST = "api.deepseek.com"
private const val DEEPSEEK_TOOLS_URL = "https://api.deepseek.com/v1/tools"
private const val SEARCH_PREFS = "debug_search_backend"
private const val FETCH_FALLBACK_PREFS = "debug_fetch_fallback"
private const val KEY_ENABLED = "enabled"
private const val NOT_FOUND = 404
/** Read timeout, too many requests, and the service's own trouble: worth another try (the API's guidance). */
private val RETRIED_CODES = setOf(408, 429, 500, 502, 503, 504)
private const val RETRIES = 2
private const val RETRY_DELAY_MS = 600L
private const val MAX_ERROR_CHARS = 160
private val JSON_TYPE = "application/json".toMediaType()
private val SEARCH_CLIENT = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(40, TimeUnit.SECONDS)
    .build()
