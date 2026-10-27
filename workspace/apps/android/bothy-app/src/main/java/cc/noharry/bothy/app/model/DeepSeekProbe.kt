package cc.noharry.bothy.app.model
import android.content.Context
import cc.noharry.bothy.app.a2a.describe
import cc.noharry.bothy.app.evidence.benchTrustedClient
import cc.noharry.bothy.app.plans.describe
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Whether a profile that is not on api.deepseek.com still reaches DeepSeek's own API behind it (owner 2026-10-18: 台架中转
 * 或别的途径内部也可能是官方 key). Checked on request for the profile's endpoint, base URL and key, and kept only while those
 * stay the same; each check unlocks what it proved, through this profile's own address and key:
 * - [balance]: `/user/balance` answers with DeepSeek's balance fields → the balance query;
 * - [tools]: a one-result search answers with results → the Search and Fetch APIs (web_search, the fetch fallback, photos);
 * - [passthrough] (Messages only): only deferred tools, then a `tool_addition` naming an undeclared tool, each come back
 *   as DeepSeek's own 400 → the relay sends the body on unchanged, so native deferred tools and the system-role run
 *   context work. A gateway that rebuilds the request drops those fields and the model answers instead (2026-10-17).
 */
internal data class DeepSeekProbe(
    val fingerprint: String,
    val balance: DeepSeekCheck,
    val tools: DeepSeekCheck,
    /** null off the Messages endpoint, which is the only one with native updates. */
    val passthrough: DeepSeekCheck?,
    val checkedAt: Long,
)

/** 「balance ✓ 余额：… | tools ✗ HTTP 404 | passthrough ✓ 原样透传」 for logcat; no key. */
internal fun DeepSeekProbe.describe(): String =
    listOfNotNull("balance" to balance, "tools" to tools, passthrough?.let { "passthrough" to it })
        .joinToString(" | ") { (name, check) -> "$name ${if (check.passed) "✓ ${check.url} ${check.note}" else "✗ ${check.note}"}" }

/** Passed when [url] is set: where it worked. [note] says what came back, for the Settings page and logcat. */
internal data class DeepSeekCheck(val url: String?, val note: String) {
    val passed: Boolean get() = url != null
}

/** One check per profile id, beside the profile settings rather than in them. */
internal object DeepSeekProbes {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun saved(context: Context, profileId: String): DeepSeekProbe? =
        prefs(context).getString(profileId, null)?.let { runCatching { decodeDeepSeekProbe(it) }.getOrNull() }

    /** [profile]'s check, only if it was made for the endpoint, base URL and [apiKey] the profile has now. */
    fun current(context: Context, profile: ProviderProfile, apiKey: String): DeepSeekProbe? =
        saved(context, profile.id)?.takeIf { it.fingerprint == deepSeekProbeFingerprint(profile.endpoint, profile.baseUrl, apiKey) }

    suspend fun check(context: Context, profile: ProviderProfile, apiKey: String): DeepSeekProbe = withContext(Dispatchers.IO) {
        probeDeepSeek(profile.endpoint, profile.baseUrl, apiKey, profile.model, deepSeekProbeClient(context)).also { save(context, profile.id, it) }
    }

    fun save(context: Context, profileId: String, probe: DeepSeekProbe) {
        prefs(context).edit().putString(profileId, encodeDeepSeekProbe(probe)).apply()
    }

    /** Runs [block] on this object's own scope, for callers without one (the adb receiver). */
    fun launch(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PROBE_PREFS, Context.MODE_PRIVATE)
}

internal suspend fun probeDeepSeek(
    endpoint: ProviderEndpoint,
    baseUrl: String,
    apiKey: String,
    model: String,
    client: OkHttpClient,
): DeepSeekProbe = withContext(Dispatchers.IO) {
    val key = apiKey.trim()
    // At once, so a relay that does not answer costs one timeout rather than five.
    val balance = async { firstPassing(deepSeekBalanceCandidates(baseUrl)) { url -> checkBalance(client, url, key) } }
    val tools = async { firstPassing(deepSeekToolsCandidates(baseUrl)) { url -> checkSearch(client, url, key) } }
    val passthrough = async { if (endpoint == ProviderEndpoint.MESSAGES) checkPassthrough(client, baseUrl, key, model) else null }
    DeepSeekProbe(
        fingerprint = deepSeekProbeFingerprint(endpoint, baseUrl, key),
        balance = balance.await(),
        tools = tools.await(),
        passthrough = passthrough.await(),
        checkedAt = System.currentTimeMillis(),
    )
}

/**
 * Where DeepSeek's root may sit behind [baseUrl]: the base without its `/v1` or `/anthropic` (a relay keeping DeepSeek's
 * paths), then the base itself (a relay mapping its own prefix onto that root, as the PC car console maps `/v1`).
 */
internal fun deepSeekRootCandidates(baseUrl: String): List<String> {
    val base = baseUrl.trim().trimEnd('/')
    val root = API_SUFFIXES.firstOrNull { base.endsWith(it) }?.let { base.removeSuffix(it) } ?: base
    return listOf(root, base).filter { it.startsWith("http://") || it.startsWith("https://") }.distinct()
}

internal fun deepSeekBalanceCandidates(baseUrl: String): List<String> = deepSeekRootCandidates(baseUrl).map { "$it/user/balance" }

internal fun deepSeekToolsCandidates(baseUrl: String): List<String> = deepSeekRootCandidates(baseUrl).map { "$it/v1/tools" }

/** Ties a check to what it was made with; the key goes in only as part of a hash. */
internal fun deepSeekProbeFingerprint(endpoint: ProviderEndpoint, baseUrl: String, apiKey: String): String {
    val identity = listOf(endpoint.wireName, baseUrl.trim().trimEnd('/'), apiKey.trim()).joinToString("\n")
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** The bench trust (bundled CA roots) the device needs for some relays, with timeouts for a search. */
internal fun deepSeekRelayClient(context: Context): OkHttpClient =
    benchTrustedClient(context).connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()

private fun deepSeekProbeClient(context: Context): OkHttpClient =
    benchTrustedClient(context).connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

private inline fun firstPassing(urls: List<String>, check: (String) -> DeepSeekCheck): DeepSeekCheck {
    val failed = mutableListOf<String>()
    for (url in urls) {
        val result = check(url)
        if (result.passed) return result
        failed += result.note
    }
    return DeepSeekCheck(null, failed.distinct().joinToString(" / ").ifBlank { "Base URL 不是 http(s) 地址" })
}

private fun checkBalance(client: OkHttpClient, url: String, key: String): DeepSeekCheck = attempt {
    val request = Request.Builder().url(url).header("Authorization", "Bearer $key").header("Accept", "application/json").build()
    val (code, body) = client.call(request)
    val json = body.jsonObjectOrNull()
    when {
        code != HTTP_OK -> DeepSeekCheck(null, body.failure(code))
        json == null || ("balance_infos" !in json && "is_available" !in json) -> DeepSeekCheck(null, "HTTP 200 但不是 DeepSeek 的余额")
        else -> DeepSeekCheck(url, "余额：${parseDeepSeekBalance(body).summary()}")
    }
}

/** One real search with one result (5 元/千次); a path the relay does not have answers 404 without reaching DeepSeek. */
private fun checkSearch(client: OkHttpClient, url: String, key: String): DeepSeekCheck = attempt {
    val body = buildJsonObject {
        put("query", PROBE_QUERY)
        put("count", 1)
    }
    val request = Request.Builder().url("$url/search").header("Authorization", "Bearer $key").post(body.toString().toRequestBody(JSON_TYPE)).build()
    val (code, text) = client.call(request)
    when {
        code != HTTP_OK -> DeepSeekCheck(null, text.failure(code))
        text.jsonObjectOrNull()?.get("results") !is JsonArray -> DeepSeekCheck(null, "HTTP 200 但不是 DeepSeek 的搜索结果")
        else -> DeepSeekCheck(url, "可用")
    }
}

/**
 * Two requests with the run's headers that DeepSeek refuses before the model runs, so a pass costs nothing: only
 * deferred tools ("All tools cannot be deferred", measured on the bench 2026-10-19) shows `defer_loading` arrives, and a
 * system update loading a tool nobody declared ("tool_addition references unknown tool") shows the in-history system
 * message and `tool_addition` arrive. A relay that drops either lets the model answer a few tokens instead.
 */
private fun checkPassthrough(client: OkHttpClient, baseUrl: String, key: String, model: String): DeepSeekCheck {
    if (model.isBlank()) return DeepSeekCheck(null, "没有选模型，无法检测")
    val url = baseUrl.trim().trimEnd('/').let { if (it.endsWith(MESSAGES_PATH)) it else it + MESSAGES_PATH }
    return attempt {
        val (deferCode, deferText) = client.call(messagesRequest(url, key, deferOnlyBody(model)))
        when {
            deferCode == HTTP_BAD_REQUEST && deferText.contains(ALL_DEFERRED_REFUSAL, ignoreCase = true) -> Unit
            deferCode == HTTP_OK -> return@attempt DeepSeekCheck(null, "模型照常回答，中转丢掉了 defer_loading")
            else -> return@attempt DeepSeekCheck(null, deferText.failure(deferCode))
        }
        val (code, text) = client.call(messagesRequest(url, key, toolAdditionBody(model)))
        when {
            code == HTTP_BAD_REQUEST && text.contains(UNKNOWN_TOOL_REFUSAL, ignoreCase = true) -> DeepSeekCheck(url, "原样透传")
            code == HTTP_OK -> DeepSeekCheck(null, "模型照常回答，中转丢掉了 tool_addition")
            else -> DeepSeekCheck(null, text.failure(code))
        }
    }
}

private fun messagesRequest(url: String, key: String, body: JsonObject): Request = Request.Builder().url(url)
    .header("x-api-key", key)
    .header("anthropic-version", ANTHROPIC_VERSION)
    .header("anthropic-beta", TOOL_CHANGES_BETA)
    .post(body.toString().toRequestBody(JSON_TYPE))
    .build()

/** Every tool deferred, which DeepSeek refuses once `defer_loading` reaches it. */
internal fun deferOnlyBody(model: String): JsonObject = probeBody(model, deferredOnly = true)

/** A plain and a deferred tool, and a system update loading a tool nobody declared. */
internal fun toolAdditionBody(model: String): JsonObject = probeBody(model, deferredOnly = false)

private fun probeBody(model: String, deferredOnly: Boolean): JsonObject = buildJsonObject {
    put("model", model)
    put("max_tokens", PROBE_MAX_TOKENS)
    put("stream", false)
    // No thinking, so nothing about its budget is refused ahead of the tools.
    putJsonObject("thinking") { put("type", "disabled") }
    putJsonArray("tools") {
        if (!deferredOnly) add(probeTool(PROBE_TOOL, deferred = false))
        add(probeTool(PROBE_DEFERRED_TOOL, deferred = true))
    }
    putJsonArray("messages") {
        add(buildJsonObject {
            put("role", "user")
            put("content", "hi")
        })
        if (!deferredOnly) {
            add(buildJsonObject {
                put("role", "system")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "tool_addition")
                        putJsonObject("tool") {
                            put("type", "tool_reference")
                            put("name", PROBE_UNDECLARED_TOOL)
                        }
                    })
                })
            })
        }
    }
}

private fun probeTool(name: String, deferred: Boolean): JsonObject = buildJsonObject {
    put("name", name)
    put("description", "Connection check.")
    putJsonObject("input_schema") { put("type", "object") }
    if (deferred) put("defer_loading", true)
}

internal fun encodeDeepSeekProbe(probe: DeepSeekProbe): String = buildJsonObject {
    put("fingerprint", probe.fingerprint)
    put("balance", probe.balance.toJson())
    put("tools", probe.tools.toJson())
    probe.passthrough?.let { put("passthrough", it.toJson()) }
    put("checkedAt", probe.checkedAt)
}.toString()

internal fun decodeDeepSeekProbe(text: String): DeepSeekProbe {
    val json = Json.parseToJsonElement(text).jsonObject
    return DeepSeekProbe(
        fingerprint = json.text("fingerprint"),
        balance = json.check("balance") ?: throw IOException("No balance check."),
        tools = json.check("tools") ?: throw IOException("No tools check."),
        passthrough = json.check("passthrough"),
        checkedAt = json["checkedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
    )
}

private fun DeepSeekCheck.toJson(): JsonObject = buildJsonObject {
    url?.let { put("url", it) }
    put("note", note)
}

private fun JsonObject.check(key: String): DeepSeekCheck? =
    (this[key] as? JsonObject)?.let { DeepSeekCheck(it.text("url").ifBlank { null }, it.text("note")) }

private inline fun attempt(block: () -> DeepSeekCheck): DeepSeekCheck = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    DeepSeekCheck(null, "连不上：${error.message ?: error.javaClass.simpleName}".take(MAX_NOTE_CHARS))
}

private fun OkHttpClient.call(request: Request): Pair<Int, String> =
    newCall(request).execute().use { it.code to it.body?.string().orEmpty() }

private fun String.jsonObjectOrNull(): JsonObject? = runCatching { Json.parseToJsonElement(this).jsonObject }.getOrNull()

/** 「HTTP 404」 with the answer's own error message when it has one; never the body itself, which may be a web page. */
private fun String.failure(code: Int): String {
    val message = jsonObjectOrNull()?.let { json ->
        (json["error"] as? JsonObject)?.text("message") ?: json.text("message")
    }?.ifBlank { null }
    return (if (message == null) "HTTP $code" else "HTTP $code：$message").take(MAX_NOTE_CHARS)
}

private fun JsonObject.text(key: String): String = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()

private val API_SUFFIXES = listOf("/v1", "/anthropic")
private val JSON_TYPE = "application/json".toMediaType()
private const val PROBE_PREFS = "debug_deepseek_probe"
private const val PROBE_QUERY = "DeepSeek"
private const val PROBE_TOOL = "connection_check"
private const val PROBE_DEFERRED_TOOL = "connection_check_deferred"
private const val PROBE_UNDECLARED_TOOL = "connection_check_undeclared"
private const val ALL_DEFERRED_REFUSAL = "cannot be deferred"
private const val UNKNOWN_TOOL_REFUSAL = "references unknown tool"
private const val MESSAGES_PATH = "/messages"
private const val ANTHROPIC_VERSION = "2023-06-01"
private const val TOOL_CHANGES_BETA = "mid-conversation-tool-changes-2026-07-01"
private const val PROBE_MAX_TOKENS = 16
private const val HTTP_OK = 200
private const val HTTP_BAD_REQUEST = 400
private const val MAX_NOTE_CHARS = 120
