package cc.noharry.bothy.sample

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class DebugTokenUsageDelta(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val cachedInputTokens: Int,
    val cacheHitTokens: Int,
    val cacheMissTokens: Int,
    val reasoningTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
)

internal data class DebugTokenUsageState(
    val requestCount: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val totalTokens: Int = 0,
    val cachedInputTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
) {
    val hasUsage: Boolean = requestCount > 0 && totalTokens > 0

    /**
     * Cache hit rate over total input tokens (including cache read/write when the
     * provider reports them separately, e.g. Anthropic).
     *
     * Anthropic reports `input_tokens` excluding cache fields, so warm-cache
     * responses often have `cache_read >> input` and `cache_creation == 0`. The
     * old formula `read / (read + write)` then always shows 100%.
     */
    val cacheHitRate: Int?
        get() {
            if (!hasCacheActivity) return null
            val denominator = cacheRateDenominator
            if (denominator <= 0) return null
            val hit = cachedInputTokens.coerceIn(0, denominator)
            return ((hit.toDouble() / denominator.toDouble()) * PERCENT).toInt()
        }

    val hasCacheActivity: Boolean =
        cachedInputTokens > 0 || cacheWriteTokens > 0 || cacheHitTokens > 0 || cacheMissTokens > 0

    /** Prefer inclusive input; fall back to hit+miss when only cache parts are known. */
    private val cacheRateDenominator: Int
        get() {
            if (inputTokens > 0) return inputTokens
            val known = cacheHitTokens + cacheMissTokens
            return if (known > 0) known else cachedInputTokens + cacheWriteTokens
        }

    /** Two runs' (or a session's) usage together. */
    operator fun plus(other: DebugTokenUsageState): DebugTokenUsageState =
        DebugTokenUsageState(
            requestCount = requestCount + other.requestCount,
            inputTokens = inputTokens + other.inputTokens,
            outputTokens = outputTokens + other.outputTokens,
            reasoningTokens = reasoningTokens + other.reasoningTokens,
            totalTokens = totalTokens + other.totalTokens,
            cachedInputTokens = cachedInputTokens + other.cachedInputTokens,
            cacheWriteTokens = cacheWriteTokens + other.cacheWriteTokens,
            cacheHitTokens = cacheHitTokens + other.cacheHitTokens,
            cacheMissTokens = cacheMissTokens + other.cacheMissTokens,
        )

    fun plus(delta: DebugTokenUsageDelta): DebugTokenUsageState =
        copy(
            requestCount = requestCount + 1,
            inputTokens = inputTokens + delta.inputTokens,
            outputTokens = outputTokens + delta.outputTokens,
            reasoningTokens = reasoningTokens + delta.reasoningTokens,
            totalTokens = totalTokens + delta.totalTokens,
            cachedInputTokens = cachedInputTokens + delta.cachedInputTokens,
            cacheWriteTokens = cacheWriteTokens + delta.cacheWriteTokens,
            cacheHitTokens = cacheHitTokens + delta.cacheHitTokens,
            cacheMissTokens = cacheMissTokens + delta.cacheMissTokens,
        )
}

internal fun cc.noharry.bothy.kernel.api.AgentEvent.TokenUsageDelta.toDebugUsageDelta(): DebugTokenUsageDelta {
    // One event is one model request: the runtime sums the call's usage frames and normalizes Anthropic's exclusive
    // cache counts into inclusive inputTokens, so input already covers the cache reads.
    val input = inputTokens.coerceAtLeast(cacheReadTokens + cacheWriteTokens)
    val miss = (input - cacheReadTokens).coerceAtLeast(0)
    return DebugTokenUsageDelta(
        inputTokens = input,
        outputTokens = outputTokens,
        totalTokens = input + outputTokens + reasoningTokens,
        cachedInputTokens = cacheReadTokens,
        cacheHitTokens = cacheReadTokens,
        cacheMissTokens = miss,
        reasoningTokens = reasoningTokens,
        cacheWriteTokens = cacheWriteTokens,
    )
}

internal fun parseDebugTokenUsage(text: String): DebugTokenUsageDelta? =
    usageObjects(text).mapNotNull(::usageDelta).lastOrNull()

private fun usageObjects(text: String): List<JsonObject> {
    val roots = mutableListOf<JsonObject>()
    parseRoot(text)?.let { roots += it }
    text.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith(SSE_DATA_PREFIX) }
        .map { it.removePrefix(SSE_DATA_PREFIX).trim() }
        .filter { it.isNotBlank() && it != SSE_DONE }
        .mapNotNull(::parseRoot)
        .forEach { roots += it }
    return roots.flatMap { root ->
        listOfNotNull(
            root.objectValue("usage"),
            root.objectValue("response")?.objectValue("usage"),
            // Anthropic message_start nests usage under message.
            root.objectValue("message")?.objectValue("usage"),
        )
    }
}

private fun usageDelta(usage: JsonObject): DebugTokenUsageDelta? {
    val details = usage.objectValue("prompt_tokens_details") ?: usage.objectValue("input_tokens_details")
    val deepSeekHit = usage.intValue("prompt_cache_hit_tokens")
    val anthropicHit = usage.intValue("cache_read_input_tokens")
    val cached = deepSeekHit ?: anthropicHit ?: details?.intValue("cached_tokens") ?: 0
    val deepSeekMiss = usage.intValue("prompt_cache_miss_tokens")
    val anthropicWrite = usage.intValue("cache_creation_input_tokens")
    val anthropicExclusiveCache =
        usage.containsKey("cache_read_input_tokens") || usage.containsKey("cache_creation_input_tokens")
    // DeepSeek miss is uncached input; Anthropic creation is cache write only.
    val cacheWrite = deepSeekMiss ?: anthropicWrite ?: 0
    val rawInput = usage.intValue("prompt_tokens") ?: usage.intValue("input_tokens") ?: 0
    val input = if (anthropicExclusiveCache) {
        rawInput + cached + cacheWrite
    } else {
        maxOf(rawInput, cached + (deepSeekMiss ?: maxOf(rawInput - cached, 0)))
    }
    val miss = deepSeekMiss ?: (input - cached).coerceAtLeast(0)
    val output = usage.intValue("completion_tokens") ?: usage.intValue("output_tokens") ?: 0
    val reasoning = usage.objectValue("completion_tokens_details")
        ?.intValue("reasoning_tokens")
        ?: usage.objectValue("output_tokens_details")?.intValue("reasoning_tokens")
        ?: 0
    val total = usage.intValue("total_tokens") ?: (input + output + reasoning)
    if (input == 0 && output == 0 && total == 0 && cached == 0 && cacheWrite == 0) return null
    return DebugTokenUsageDelta(
        inputTokens = input,
        outputTokens = output,
        totalTokens = total,
        cachedInputTokens = cached,
        cacheHitTokens = cached,
        cacheMissTokens = miss,
        reasoningTokens = reasoning,
        cacheWriteTokens = cacheWrite,
    )
}

private fun parseRoot(text: String): JsonObject? =
    try {
        JSON.parseToJsonElement(text.trim()).jsonObject
    } catch (_: RuntimeException) {
        null
    }

private fun JsonObject.objectValue(name: String): JsonObject? =
    this[name] as? JsonObject

private fun JsonObject.intValue(name: String): Int? =
    this[name]?.jsonPrimitive?.intOrNull

private val JSON = Json { ignoreUnknownKeys = true }
private const val SSE_DATA_PREFIX = "data:"
private const val SSE_DONE = "[DONE]"
private const val PERCENT = 100
