package cc.noharry.bothy.sample

import android.content.Context
import cc.noharry.bothy.kernel.api.model.ModelClient
import cc.noharry.bothy.kernel.api.model.ModelEvent
import cc.noharry.bothy.kernel.api.model.ModelMessage
import cc.noharry.bothy.kernel.api.model.ModelRequest
import cc.noharry.bothy.kernel.api.model.ServerSideSearchCapability
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.collect

internal data class DebugHostedSearchValidationResult(
    val requestId: String,
    val provider: String,
    val profile: String,
    val searchCapability: String,
    val completed: Boolean,
    val searchEventCount: Int,
    val resultCount: Int,
    val toolCallCount: Int,
    val response: String,
    val error: String = "",
) {
    val passed: Boolean
        get() = completed && error.isBlank() && toolCallCount == 0 && response.isNotBlank()
}

internal suspend fun runDebugHostedSearchValidation(
    requestId: String,
    profile: DebugProviderProfile,
    config: DebugProviderConfig,
    client: ModelClient,
    prompt: String,
): DebugHostedSearchValidationResult {
    val text = StringBuilder()
    var completed = false
    var searchEvents = 0
    var resultCount = 0
    var toolCalls = 0
    return try {
        client.complete(
            ModelRequest(
                messages = listOf(ModelMessage(role = "user", content = prompt)),
                tools = emptyList(),
                modelRef = config.modelRef(profile),
                promptCacheKey = profile.hostedSearchValidationPromptCacheKey(),
                toolChoice = profile.hostedSearchValidationToolChoice(),
            ),
        ).collect { event ->
            when (event) {
                is ModelEvent.TextDelta -> text.append(event.text)
                is ModelEvent.SearchActivity -> {
                    searchEvents += 1
                    resultCount += event.resultCount ?: 0
                }
                is ModelEvent.ToolCall -> toolCalls += 1
                ModelEvent.Completed -> completed = true
                else -> Unit
            }
        }
        DebugHostedSearchValidationResult(
            requestId = requestId,
            provider = profile.provider.label,
            profile = profile.displayLabel,
            searchCapability = profile.searchCapability,
            completed = completed,
            searchEventCount = searchEvents,
            resultCount = resultCount,
            toolCallCount = toolCalls,
            response = text.toString().trim(),
        )
    } catch (error: Exception) {
        DebugHostedSearchValidationResult(
            requestId = requestId,
            provider = profile.provider.label,
            profile = profile.displayLabel,
            searchCapability = profile.searchCapability,
            completed = completed,
            searchEventCount = searchEvents,
            resultCount = resultCount,
            toolCallCount = toolCalls,
            response = text.toString().trim(),
            error = error.message.orEmpty(),
        )
    }
}

private fun DebugProviderProfile.hostedSearchValidationToolChoice(): String? =
    if (ServerSideSearchCapability.fromWireName(searchCapability) == ServerSideSearchCapability.OPENAI_RESPONSES_TOOL) {
        HOSTED_SEARCH_REQUIRED_TOOL_CHOICE
    } else {
        null
    }

private fun DebugProviderProfile.hostedSearchValidationPromptCacheKey(): String =
    "$HOSTED_SEARCH_PROMPT_CACHE_KEY_PREFIX${id.sha256Hex().take(HOSTED_SEARCH_PROMPT_CACHE_HASH_CHARS)}"

internal fun writeHostedSearchValidationEvidence(
    context: Context,
    result: DebugHostedSearchValidationResult,
): File {
    val dir = File(context.filesDir, HOSTED_SEARCH_EVIDENCE_DIR).also { it.mkdirs() }
    return File(dir, result.requestId.hostedSearchEvidenceFileName() + ".json").also { file ->
        file.writeText(result.toEvidenceJson(), Charsets.UTF_8)
    }
}

private fun DebugHostedSearchValidationResult.toEvidenceJson(): String =
    """
    {
      "schemaVersion": 1,
      "requestId": "${requestId.escapeJson()}",
      "provider": "${provider.escapeJson()}",
      "profile": "${profile.escapeJson()}",
      "searchCapability": "${searchCapability.escapeJson()}",
      "completed": $completed,
      "passed": $passed,
      "searchEventCount": $searchEventCount,
      "resultCount": $resultCount,
      "toolCallCount": $toolCallCount,
      "response": "${response.escapeJson()}",
      "error": "${error.escapeJson()}"
    }
    """.trimIndent()

private fun String.escapeJson(): String =
    buildString {
        for (char in this@escapeJson) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }

private fun String.hostedSearchEvidenceFileName(): String =
    map { char -> if (char.isLetterOrDigit() || char in setOf('-', '_')) char else '_' }.joinToString("")

private fun String.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

private const val HOSTED_SEARCH_EVIDENCE_DIR = "web-search-evidence"
private const val HOSTED_SEARCH_REQUIRED_TOOL_CHOICE = "required"
private const val HOSTED_SEARCH_PROMPT_CACHE_KEY_PREFIX = "bothy-runtime:web-search:"
private const val HOSTED_SEARCH_PROMPT_CACHE_HASH_CHARS = 32
