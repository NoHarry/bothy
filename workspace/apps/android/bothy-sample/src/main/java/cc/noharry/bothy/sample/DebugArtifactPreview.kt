package cc.noharry.bothy.sample

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Latest HTML page produced by a successful `write_file` call, rendered from the
 * write payload itself.
 *
 * The runtime isolates `/scratch` writes per run (`/scratch/<runId>/...`) and deletes
 * them when the run terminates, so a `file://` preview of the logical path can never
 * resolve. The full page source is already in the tool-call arguments, which is what
 * the board WebView renders via `loadDataWithBaseURL`.
 */
internal data class DebugArtifactPreview(
    val path: String,
    val html: String,
    val bytes: Long,
    val refreshToken: String,
)

internal fun latestDebugHtmlArtifact(
    items: List<DebugConversationItem>,
): DebugArtifactPreview? =
    items.asReversed()
        .asSequence()
        .filterIsInstance<DebugConversationItem.ToolCallItem>()
        .mapNotNull { it.toHtmlArtifactPreview() }
        .firstOrNull()

/** Inline preview payload for a completed HTML `write_file` row, or null when not previewable. */
internal fun DebugConversationItem.ToolCallItem.toHtmlArtifactPreview(): DebugArtifactPreview? {
    if (status != DebugToolCallStatus.DONE || toolName != WRITE_FILE_TOOL) return null
    // Public tool results arrive as plain JSON ({"ok":true,"path":...}); the raw hook
    // wraps the same payload in a `result={...}` line. Accept both shapes.
    val payload = resultJson?.let(::artifactResultPayload) ?: return null
    if (!payload.stringField("ok").equals("true", ignoreCase = true)) return null
    val path = payload.stringField("path") ?: return null
    if (!path.isHtmlArtifactPath()) return null
    // Arguments may be replaced by the redacted placeholder or trimmed past JSON
    // integrity for oversized pages; both simply skip the preview.
    val html = argumentsJson?.parseArtifactArguments()?.stringField("content")
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return DebugArtifactPreview(
        path = path,
        html = html,
        bytes = payload.stringField("bytes")?.toLongOrNull() ?: html.toByteArray(Charsets.UTF_8).size.toLong(),
        refreshToken = "${path}:${html.hashCode()}",
    )
}

private fun artifactResultPayload(resultJson: String): JsonObject? =
    resultJson.parseArtifactJson()
        ?: resultJson.lineValue("result")?.parseArtifactJson()

private fun String.parseArtifactJson(): JsonObject? =
    runCatching { ArtifactPreviewJson.parseToJsonElement(this) as? JsonObject }.getOrNull()

/** Tool rows may carry raw `arguments={...}` prefixed bodies; strip the prefix before parsing. */
private fun String.parseArtifactArguments(): JsonObject? =
    parseArtifactJson()
        ?: substringAfter("arguments=", "").takeIf { it.startsWith("{") }?.parseArtifactJson()

private fun JsonObject.stringField(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun String.isHtmlArtifactPath(): Boolean {
    val lower = lowercase()
    return HTML_ARTIFACT_SUFFIXES.any { lower.endsWith(it) }
}

private val ArtifactPreviewJson = Json { ignoreUnknownKeys = true }
private val HTML_ARTIFACT_SUFFIXES = listOf(".html", ".htm")
private const val WRITE_FILE_TOOL = "write_file"
