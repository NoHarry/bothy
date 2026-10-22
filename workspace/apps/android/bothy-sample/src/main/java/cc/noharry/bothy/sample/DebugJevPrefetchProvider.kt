@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.ToolPrefetchProvider
import cc.noharry.agent.kernel.api.ToolPrefetchRequest
import cc.noharry.agent.kernel.api.ToolPrefetchTrace
import cc.noharry.agent.kernel.api.ToolProvider
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Host-owned selector; ordinary execution/identity/concurrency stay with the underlying provider. */
internal class DebugJevPrefetchProvider(
    private val delegate: ToolProvider,
    private val config: DebugJevConfig,
    private val apiKey: String,
    private val sessionContext: String,
    private val transport: (suspend (String) -> String)? = null,
) : ToolProvider by delegate, ToolPrefetchProvider {
    override val providerConcurrency: ProviderConcurrencyDeclaration = delegate.providerConcurrency
    override val prefetchTimeoutMillis: Long get() = config.timeoutMillis
    override val prefetchOnSearch: Boolean get() = true

    override suspend fun prefetchTools(request: ToolPrefetchRequest, trace: ToolPrefetchTrace): List<String> {
        if (!config.enabled || config.validationError() != null || apiKey.isBlank() || request.tools.isEmpty()) return emptyList()
        val candidates = if (config.mode != DebugJevMode.FLAT) selectDomainCandidates(request, trace) else request
        if (candidates.tools.isEmpty()) return emptyList()
        val body = jevQuestionBody(candidates, config.model, sessionContext)
        val result = exchange(body, trace)
        return selectJevTools(result, candidates, config)
    }

    private suspend fun selectDomainCandidates(request: ToolPrefetchRequest, trace: ToolPrefetchTrace): ToolPrefetchRequest {
        val domains = jevDomains(request.tools, config.mode == DebugJevMode.TWO_STAGE_SUBDOMAINS)
        val body = jevDomainQuestionBody(request, domains, config.model, sessionContext)
        val result = exchange(body, trace)
        val selected = selectJevDomains(result, domains, config.domainMinScore)
        return request.copy(tools = selected.flatMap { it.tools }.distinctBy { it.name })
    }

    private suspend fun exchange(body: String, trace: ToolPrefetchTrace): String {
        trace.request(body)
        val result = transport?.invoke(body) ?: executeJevRequest(config, apiKey, body, trace)
        trace.response(result)
        return result
    }
}

internal fun jevQuestionBody(request: ToolPrefetchRequest, model: String, sessionContext: String): String =
    buildJsonObject {
        put("model", model)
        put("state", buildJsonObject {
            put("latest_user_request", request.input)
            put("recent_context", sessionContext)
        })
        put("questions", buildJsonObject {
            request.tools.forEachIndexed { index, tool ->
                put("tool_$index", buildJsonObject {
                    put("type", "noul")
                    put("instructions", buildJsonObject {
                        put("question", JEV_RELEVANCE_QUESTION)
                        put("tool_name", tool.name)
                        put("tool_description", tool.description)
                    })
                })
            }
        })
    }.toString()

internal fun selectJevTools(response: String, request: ToolPrefetchRequest, config: DebugJevConfig): List<String> {
    val scores = jevScores(response, "tool", request.tools.size)
    return request.tools.mapIndexed { index, tool ->
        tool.name to scores[index]
    }.filter { it.second >= config.minScore }
        .sortedByDescending { it.second }
        .take(minOf(config.maxTools, request.maxTools))
        .map { it.first }
}

internal fun jevScores(response: String, prefix: String, count: Int): List<Double> {
    val answers = Json.parseToJsonElement(response).jsonObject["answers"]?.jsonObject
        ?: error("Missing selector answers.")
    return (0 until count).map { index ->
        val score = (answers["${prefix}_$index"] as? JsonObject)?.get("noul")?.jsonPrimitive?.doubleOrNull
        require(score != null && score.isFinite() && score in 0.0..1.0) { "Invalid selector score." }
        score
    }
}

internal suspend fun executeJevRequest(
    config: DebugJevConfig, apiKey: String, body: String, trace: ToolPrefetchTrace,
): String {
    val metrics = DebugJevHttpMetrics()
    val client = JEV_HTTP_CLIENT.newBuilder()
        .eventListener(metrics)
        .callTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
        .connectTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
        .build()
    val request = Request.Builder().url(config.endpoint).header("Authorization", "Bearer $apiKey")
        .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    return try {
        executeJevCall(client, request)
    } finally {
        trace.diagnostics(metrics.snapshot())
    }
}

private suspend fun executeJevCall(client: OkHttpClient, request: Request): String =
    suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("Selector connection failed."))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use {
                        if (!it.isSuccessful) throw IOException("Selector HTTP ${it.code}.")
                        it.body?.string() ?: throw IOException("Empty selector response.")
                    }
                    if (continuation.isActive) continuation.resume(text)
                } catch (_: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("Selector response failed."))
                }
            }
        })
    }

private const val JEV_MAX_IDLE_CONNECTIONS = 5
private const val JEV_KEEP_ALIVE_MINUTES = 5L
private val JEV_HTTP_CLIENT = OkHttpClient.Builder()
    .connectionPool(ConnectionPool(JEV_MAX_IDLE_CONNECTIONS, JEV_KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
    .retryOnConnectionFailure(false).followRedirects(false).build()
private const val JEV_RELEVANCE_QUESTION =
    "Does this tool directly support an explicit part of the latest request, including required prerequisites? " +
        "Use its description; multiple complementary tools can be relevant."
