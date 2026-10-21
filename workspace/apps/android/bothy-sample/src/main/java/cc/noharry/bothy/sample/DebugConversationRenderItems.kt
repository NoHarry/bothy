package cc.noharry.bothy.sample

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Timeline projection of [DebugConversationItem] rows.
 *
 * Groups consecutive context lookups, consecutive business tools, and consecutive
 * runtime phases so the stream stays scannable (Codex / Claude Code style).
 * Ambient [DebugTranscriptRole.STATUS] is never projected — live status owns the
 * composer strip instead.
 */
internal sealed interface DebugConversationRenderItem {
    val key: String

    data class Single(val item: DebugConversationItem) : DebugConversationRenderItem {
        override val key: String = "item-${item.id}"
    }

    data class ContextToolGroup(
        val tools: List<DebugConversationItem.ToolCallItem>,
    ) : DebugConversationRenderItem {
        override val key: String = "context-${tools.first().id}"
    }

    /** Consecutive non-context (business / network / write) tools. */
    data class BusinessToolGroup(
        val tools: List<DebugConversationItem.ToolCallItem>,
    ) : DebugConversationRenderItem {
        override val key: String = "business-${tools.first().id}"
    }

    /** Consecutive runtime phase rows + runtime diagnostic messages. */
    data class RuntimePhaseGroup(
        val phases: List<DebugConversationItem>,
    ) : DebugConversationRenderItem {
        override val key: String = "runtime-${phases.first().id}"
    }

    /** Visual separator inserted before each user turn after the first. */
    data class TurnDivider(
        val beforeTurnId: Long,
    ) : DebugConversationRenderItem {
        override val key: String = "divider-$beforeTurnId"
    }

    /** Inline WebView preview emitted right after a successful HTML `write_file` row. */
    data class HtmlArtifact(
        val toolItemId: Long,
        val preview: DebugArtifactPreview,
    ) : DebugConversationRenderItem {
        override val key: String = "html-artifact-$toolItemId"
    }

    /** A picture from the image tool, right after its tool row; the reference is null while it is drawn. */
    data class GeneratedImage(
        val toolItemId: Long,
        val picture: DebugOverlayPicture,
    ) : DebugConversationRenderItem {
        override val key: String = "generated-image-$toolItemId"
    }
}

/**
 * Keys a lazy list can take: a repeated one gets 「#2」, 「#3」… (ab93, 2026-10-15: two rows with id 1 crashed the
 * console with 「Key "item-1" was already used」 as it opened). Logs the repeats, since rows should never share an id.
 */
internal fun List<String>.uniqueKeys(onRepeat: (List<String>) -> Unit = ::logRepeatedKeys): List<String> {
    val seen = HashMap<String, Int>()
    val keys = map { key ->
        val count = (seen[key] ?: 0) + 1
        seen[key] = count
        if (count == 1) key else "$key#$count"
    }
    seen.filterValues { it > 1 }.keys.takeIf { it.isNotEmpty() }?.let { onRepeat(it.toList()) }
    return keys
}

internal fun List<DebugConversationItem>.toRenderItems(): List<DebugConversationRenderItem> {
    val renderItems = mutableListOf<DebugConversationRenderItem>()
    val contextGroup = mutableListOf<DebugConversationItem.ToolCallItem>()
    val businessGroup = mutableListOf<DebugConversationItem.ToolCallItem>()
    val runtimeGroup = mutableListOf<DebugConversationItem>()
    var turnCount = 0

    fun flushContext() {
        if (contextGroup.isNotEmpty()) {
            renderItems += DebugConversationRenderItem.ContextToolGroup(contextGroup.toList())
            contextGroup.clear()
        }
    }

    fun flushBusiness() {
        if (businessGroup.isEmpty()) return
        if (businessGroup.size == 1) {
            renderItems += DebugConversationRenderItem.Single(businessGroup.single())
        } else {
            renderItems += DebugConversationRenderItem.BusinessToolGroup(businessGroup.toList())
        }
        // Inline page previews and pictures surface right after the tool row(s) that produced them,
        // so a collapsed "Used N tools" group never hides them.
        businessGroup.forEach { tool ->
            tool.toHtmlArtifactPreview()?.let { preview ->
                renderItems += DebugConversationRenderItem.HtmlArtifact(tool.id, preview)
            }
            tool.generatedPicture()?.let { picture ->
                renderItems += DebugConversationRenderItem.GeneratedImage(tool.id, picture)
            }
        }
        businessGroup.clear()
    }

    fun flushRuntime() {
        if (runtimeGroup.isEmpty()) return
        if (runtimeGroup.size == 1) {
            renderItems += DebugConversationRenderItem.Single(runtimeGroup.single())
        } else {
            renderItems += DebugConversationRenderItem.RuntimePhaseGroup(runtimeGroup.toList())
        }
        runtimeGroup.clear()
    }

    fun flushToolsAndRuntime() {
        flushContext()
        flushBusiness()
        flushRuntime()
    }

    forEach { item ->
        when {
            item.isAmbientStatus() -> Unit // status bar / Worked-for owns this
            item is DebugConversationItem.ToolCallItem && item.isContextLookupTool() -> {
                flushBusiness()
                flushRuntime()
                contextGroup += item
            }
            item is DebugConversationItem.ToolCallItem -> {
                flushContext()
                flushRuntime()
                businessGroup += item
            }
            item.isRuntimeTimelineNoise() -> {
                flushContext()
                flushBusiness()
                runtimeGroup += item
            }
            item is DebugConversationItem.TurnHeader -> {
                flushToolsAndRuntime()
                if (turnCount > 0) {
                    renderItems += DebugConversationRenderItem.TurnDivider(beforeTurnId = item.id)
                }
                turnCount += 1
                renderItems += DebugConversationRenderItem.Single(item)
            }
            else -> {
                flushToolsAndRuntime()
                renderItems += DebugConversationRenderItem.Single(item)
            }
        }
    }
    flushToolsAndRuntime()
    return renderItems
}

internal fun DebugConversationRenderItem.autoScrollKey(): String =
    when (this) {
        is DebugConversationRenderItem.Single -> item.autoScrollKey()
        is DebugConversationRenderItem.ContextToolGroup -> tools.joinToString("|") { it.autoScrollKey() }
        is DebugConversationRenderItem.BusinessToolGroup -> tools.joinToString("|") { it.autoScrollKey() }
        is DebugConversationRenderItem.RuntimePhaseGroup -> phases.joinToString("|") { it.autoScrollKey() }
        is DebugConversationRenderItem.TurnDivider -> key
        is DebugConversationRenderItem.HtmlArtifact -> "$key:${preview.refreshToken}"
        is DebugConversationRenderItem.GeneratedImage -> "$key:${picture.reference}"
    }

/**
 * Live thinking row id while the run is active and no later user-visible content
 * (assistant / tool / confirmation) has arrived yet. Used to keep a fixed one-line
 * tail ticker; when later content appears the row collapses to a summary.
 */
internal fun streamingThinkingItemId(
    items: List<DebugConversationItem>,
    isRunning: Boolean,
): Long? {
    if (!isRunning) return null
    val thinking = items.asReversed()
        .filterIsInstance<DebugConversationItem.MessageItem>()
        .firstOrNull { it.role == DebugTranscriptRole.THINKING }
        ?: return null
    val index = items.indexOfFirst { it.id == thinking.id }
    if (index < 0) return null
    val hasLaterVisible = items.asSequence().drop(index + 1).any { it.endsLiveThinkingStream() }
    return if (hasLaterVisible) null else thinking.id
}

private fun DebugConversationItem.endsLiveThinkingStream(): Boolean =
    when (this) {
        is DebugConversationItem.MessageItem ->
            role == DebugTranscriptRole.ASSISTANT || role == DebugTranscriptRole.ERROR
        is DebugConversationItem.ToolCallItem -> true
        is DebugConversationItem.ConfirmationItem -> true
        is DebugConversationItem.TurnHeader -> true
        is DebugConversationItem.RuntimePhaseItem -> false
    }

internal fun DebugConversationRenderItem.ContextToolGroup.summaryLabel(): String {
    val reads = tools.count { it.toolName.isReadToolName() }
    val lists = tools.count { it.toolName.isListToolName() }
    val searches = tools.count { it.toolName.isSearchToolName() }
    val parts = mutableListOf<String>()
    if (reads > 0) parts += "Read ${reads.countLabel("file", "files")}"
    if (lists > 0) parts += "Listed ${lists.countLabel("dir", "dirs")}"
    if (searches > 0) parts += if (searches == 1) "searched code" else "searched code $searches times"
    return parts.joinToString(" and ").ifBlank { "Context lookups" }
}

internal fun DebugConversationRenderItem.BusinessToolGroup.summaryLabel(): String {
    val names = tools.map { it.toolName }.distinct()
    return when {
        names.size == 1 -> {
            val name = names.single()
            if (tools.size == 1) name else "$name × ${tools.size}"
        }
        else -> "Used ${tools.size} tools"
    }
}

internal fun DebugConversationRenderItem.RuntimePhaseGroup.summaryLabel(): String {
    val n = phases.size
    val titles = phases.mapNotNull { phase ->
        when (phase) {
            is DebugConversationItem.RuntimePhaseItem -> phase.title
            is DebugConversationItem.MessageItem ->
                phase.content.lineSequence().firstOrNull { it.isNotBlank() }
            else -> null
        }
    }
    val preview = titles.firstOrNull()?.take(RUNTIME_GROUP_PREVIEW_CHARS).orEmpty()
    return when {
        preview.isBlank() -> "Runtime · $n steps"
        n == 1 -> preview
        else -> "Runtime · $n steps · $preview"
    }
}

internal fun DebugConversationRenderItem.ContextToolGroup.status(): DebugToolCallStatus =
    tools.aggregateToolStatus()

internal fun DebugConversationRenderItem.BusinessToolGroup.status(): DebugToolCallStatus =
    tools.aggregateToolStatus()

internal fun DebugConversationItem.ToolCallItem.contextLookupLabel(): String =
    when {
        toolName.isSearchToolName() -> searchLookupLabel()
        toolName.isListToolName() -> "Listed ${displayPath() ?: "directory"}"
        else -> "Read ${displayPath() ?: "file"}"
    }

internal fun DebugConversationItem.ToolCallItem.businessToolLineLabel(): String {
    if (toolName.equals("web_search", ignoreCase = true)) {
        val query = argumentField("query", "q", "pattern")
        if (!query.isNullOrBlank()) return "web_search · ${query.cleanLabel()}"
    }
    return toolName
}

private fun List<DebugConversationItem.ToolCallItem>.aggregateToolStatus(): DebugToolCallStatus =
    when {
        any { it.status == DebugToolCallStatus.RUNNING } -> DebugToolCallStatus.RUNNING
        any { it.status == DebugToolCallStatus.FAILED } -> DebugToolCallStatus.FAILED
        any { it.status == DebugToolCallStatus.UNKNOWN } -> DebugToolCallStatus.UNKNOWN
        else -> DebugToolCallStatus.DONE
    }

private fun DebugConversationItem.isAmbientStatus(): Boolean =
    this is DebugConversationItem.MessageItem && role == DebugTranscriptRole.STATUS

private fun DebugConversationItem.isRuntimeTimelineNoise(): Boolean =
    this is DebugConversationItem.RuntimePhaseItem ||
        (this is DebugConversationItem.MessageItem && role == DebugTranscriptRole.RUNTIME)

private fun DebugConversationItem.autoScrollKey(): String =
    when (this) {
        is DebugConversationItem.TurnHeader -> "$id:${preview.hashCode()}:${fullText.hashCode()}:$collapsed"
        is DebugConversationItem.MessageItem -> "$id:$role:${content.hashCode()}:$collapsed"
        is DebugConversationItem.RuntimePhaseItem -> "$id:${title.hashCode()}:${body.hashCode()}:$collapsed:$markdown"
        is DebugConversationItem.ConfirmationItem -> "$id:$pendingId:$domain:$mode:$status:$resolvedAt"
        is DebugConversationItem.ToolCallItem -> {
            "$id:$toolCallId:$toolName:$status:${argumentsJson?.hashCode()}:${resultJson?.hashCode()}:$finishedAt:$collapsed"
        }
    }

private fun DebugConversationItem.ToolCallItem.isContextLookupTool(): Boolean =
    toolName.isReadToolName() || toolName.isListToolName() || toolName.isSearchToolName()

private fun String.isReadToolName(): Boolean =
    equals(READ_FILE_TOOL, ignoreCase = true) || startsWith(READ_TOOL_PREFIX, ignoreCase = true)

private fun String.isListToolName(): Boolean =
    equals(LIST_DIR_TOOL, ignoreCase = true) || startsWith(LIST_TOOL_PREFIX, ignoreCase = true)

private fun String.isSearchToolName(): Boolean =
    equals(GREP_TOOL, ignoreCase = true) ||
        startsWith(GREP_TOOL, ignoreCase = true) ||
        startsWith(SEARCH_TOOL_PREFIX, ignoreCase = true)

private fun DebugConversationItem.ToolCallItem.searchLookupLabel(): String {
    val query = argumentField("pattern", "query", "keyword", "regex")
    val path = displayPath()
    return when {
        !query.isNullOrBlank() && !path.isNullOrBlank() -> "Searched for ${query.cleanLabel()} in $path"
        !query.isNullOrBlank() -> "Searched for ${query.cleanLabel()}"
        !path.isNullOrBlank() -> "Searched $path"
        else -> "Searched code"
    }
}

private fun DebugConversationItem.ToolCallItem.displayPath(): String? =
    argumentField("path", "file", "directory", "root")?.shortPath()

private fun DebugConversationItem.ToolCallItem.argumentField(vararg names: String): String? {
    val json = argumentsJson?.substringAfter(ARGUMENTS_PREFIX, argumentsJson)?.trim() ?: return null
    if (!json.startsWith(JSON_OBJECT_START)) return null
    val obj = runCatching { DebugToolDisplayJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
    return names.firstNotNullOfOrNull { name -> obj[name]?.jsonPrimitive?.contentOrNull }
}

private fun Int.countLabel(one: String, many: String): String =
    "$this ${if (this == 1) one else many}"

private fun String.shortPath(): String {
    val clean = cleanLabel()
    return clean.substringAfterLast(PATH_SEPARATOR).ifBlank { clean }
}

private fun String.cleanLabel(): String =
    lineSequence().joinToString(" ").trim()

private val DebugToolDisplayJson = Json { ignoreUnknownKeys = true }

private const val READ_FILE_TOOL = "read_file"
private const val LIST_DIR_TOOL = "list_dir"
private const val GREP_TOOL = "grep"
private const val READ_TOOL_PREFIX = "read"
private const val LIST_TOOL_PREFIX = "list"
private const val SEARCH_TOOL_PREFIX = "search"
private const val ARGUMENTS_PREFIX = "arguments="
private const val JSON_OBJECT_START = "{"
private const val PATH_SEPARATOR = '/'
private const val RUNTIME_GROUP_PREVIEW_CHARS = 36

/** Once per set of repeats: the list is keyed again on every streamed change. */
private fun logRepeatedKeys(keys: List<String>) {
    if (loggedRepeats.size > MAX_LOGGED_REPEATS) loggedRepeats.clear()
    if (loggedRepeats.add(keys.toSet())) Log.w(RENDER_TAG, "repeated row keys: $keys")
}

private val loggedRepeats: MutableSet<Set<String>> = java.util.Collections.synchronizedSet(LinkedHashSet())
private const val RENDER_TAG = "DebugConversation"
private const val MAX_LOGGED_REPEATS = 64
