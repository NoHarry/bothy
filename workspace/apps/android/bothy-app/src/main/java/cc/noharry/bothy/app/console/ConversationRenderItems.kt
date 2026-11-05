package cc.noharry.bothy.app.console
import android.content.res.Resources
import android.util.Log
import cc.noharry.agent.appserver.protocol.ArtifactPreview
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.protocol.view.OverlayPicture
import cc.noharry.agent.appserver.protocol.view.generatedPicture
import cc.noharry.agent.appserver.protocol.view.toHtmlArtifactPreview
import cc.noharry.bothy.app.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Timeline projection of [ConversationItem] rows.
 *
 * Groups consecutive context lookups, consecutive business tools, and consecutive
 * runtime phases so the stream stays scannable (Codex / Claude Code style).
 * Ambient [TranscriptRole.STATUS] is never projected — live status owns the
 * composer strip instead.
 */
internal sealed interface ConversationRenderItem {
    val key: String

    data class Single(val item: ConversationItem) : ConversationRenderItem {
        override val key: String = "item-${item.id}"
    }

    data class ContextToolGroup(
        val tools: List<ConversationItem.ToolCallItem>,
    ) : ConversationRenderItem {
        override val key: String = "context-${tools.first().id}"
    }

    /** Consecutive non-context (business / network / write) tools. */
    data class BusinessToolGroup(
        val tools: List<ConversationItem.ToolCallItem>,
    ) : ConversationRenderItem {
        override val key: String = "business-${tools.first().id}"
    }

    /** Consecutive runtime phase rows + runtime diagnostic messages. */
    data class RuntimePhaseGroup(
        val phases: List<ConversationItem>,
    ) : ConversationRenderItem {
        override val key: String = "runtime-${phases.first().id}"
    }

    /** Visual separator inserted before each user turn after the first. */
    data class TurnDivider(
        val beforeTurnId: Long,
    ) : ConversationRenderItem {
        override val key: String = "divider-$beforeTurnId"
    }

    /** Inline WebView preview emitted right after a successful HTML `write_file` row. */
    data class HtmlArtifact(
        val toolItemId: Long,
        val preview: ArtifactPreview,
    ) : ConversationRenderItem {
        override val key: String = "html-artifact-$toolItemId"
    }

    /** A picture from the image tool, right after its tool row; the reference is null while it is drawn. */
    data class GeneratedImage(
        val toolItemId: Long,
        val picture: OverlayPicture,
    ) : ConversationRenderItem {
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

internal fun List<ConversationItem>.toRenderItems(): List<ConversationRenderItem> {
    val renderItems = mutableListOf<ConversationRenderItem>()
    val contextGroup = mutableListOf<ConversationItem.ToolCallItem>()
    val businessGroup = mutableListOf<ConversationItem.ToolCallItem>()
    val runtimeGroup = mutableListOf<ConversationItem>()
    var turnCount = 0

    fun flushContext() {
        if (contextGroup.isNotEmpty()) {
            renderItems += ConversationRenderItem.ContextToolGroup(contextGroup.toList())
            contextGroup.clear()
        }
    }

    fun flushBusiness() {
        if (businessGroup.isEmpty()) return
        if (businessGroup.size == 1) {
            renderItems += ConversationRenderItem.Single(businessGroup.single())
        } else {
            renderItems += ConversationRenderItem.BusinessToolGroup(businessGroup.toList())
        }
        // Inline page previews and pictures surface right after the tool row(s) that produced them,
        // so a collapsed "Used N tools" group never hides them.
        businessGroup.forEach { tool ->
            tool.toHtmlArtifactPreview()?.let { preview ->
                renderItems += ConversationRenderItem.HtmlArtifact(tool.id, preview)
            }
            tool.generatedPicture()?.let { picture ->
                renderItems += ConversationRenderItem.GeneratedImage(tool.id, picture)
            }
        }
        businessGroup.clear()
    }

    fun flushRuntime() {
        if (runtimeGroup.isEmpty()) return
        if (runtimeGroup.size == 1) {
            renderItems += ConversationRenderItem.Single(runtimeGroup.single())
        } else {
            renderItems += ConversationRenderItem.RuntimePhaseGroup(runtimeGroup.toList())
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
            item is ConversationItem.ToolCallItem && item.isContextLookupTool() -> {
                flushBusiness()
                flushRuntime()
                contextGroup += item
            }
            item is ConversationItem.ToolCallItem -> {
                flushContext()
                flushRuntime()
                businessGroup += item
            }
            item.isRuntimeTimelineNoise() -> {
                flushContext()
                flushBusiness()
                runtimeGroup += item
            }
            item is ConversationItem.TurnHeader -> {
                flushToolsAndRuntime()
                if (turnCount > 0) {
                    renderItems += ConversationRenderItem.TurnDivider(beforeTurnId = item.id)
                }
                turnCount += 1
                renderItems += ConversationRenderItem.Single(item)
            }
            else -> {
                flushToolsAndRuntime()
                renderItems += ConversationRenderItem.Single(item)
            }
        }
    }
    flushToolsAndRuntime()
    return renderItems
}

internal fun ConversationRenderItem.autoScrollKey(isFolded: (ConversationItem) -> Boolean): String =
    when (this) {
        is ConversationRenderItem.Single -> item.autoScrollKey(isFolded(item))
        is ConversationRenderItem.ContextToolGroup -> tools.joinToString("|") { it.autoScrollKey(isFolded(it)) }
        is ConversationRenderItem.BusinessToolGroup -> tools.joinToString("|") { it.autoScrollKey(isFolded(it)) }
        is ConversationRenderItem.RuntimePhaseGroup -> phases.joinToString("|") { it.autoScrollKey(isFolded(it)) }
        is ConversationRenderItem.TurnDivider -> key
        is ConversationRenderItem.HtmlArtifact -> "$key:${preview.refreshToken}"
        is ConversationRenderItem.GeneratedImage -> "$key:${picture.reference}"
    }

/**
 * Live thinking row id while the run is active and no later user-visible content
 * (assistant / tool / confirmation) has arrived yet. Used to keep a fixed one-line
 * tail ticker; when later content appears the row collapses to a summary.
 */
internal fun streamingThinkingItemId(
    items: List<ConversationItem>,
    isRunning: Boolean,
): Long? {
    if (!isRunning) return null
    val thinking = items.asReversed()
        .filterIsInstance<ConversationItem.MessageItem>()
        .firstOrNull { it.role == TranscriptRole.THINKING }
        ?: return null
    val index = items.indexOfFirst { it.id == thinking.id }
    if (index < 0) return null
    val hasLaterVisible = items.asSequence().drop(index + 1).any { it.endsLiveThinkingStream() }
    return if (hasLaterVisible) null else thinking.id
}

private fun ConversationItem.endsLiveThinkingStream(): Boolean =
    when (this) {
        is ConversationItem.MessageItem ->
            role == TranscriptRole.ASSISTANT || role == TranscriptRole.ERROR
        is ConversationItem.ToolCallItem -> true
        is ConversationItem.ConfirmationItem -> true
        is ConversationItem.TurnHeader -> true
        is ConversationItem.RuntimePhaseItem -> false
    }

internal fun ConversationRenderItem.ContextToolGroup.summaryLabel(resources: Resources): String {
    val reads = tools.count { it.toolName.isReadToolName() }
    val lists = tools.count { it.toolName.isListToolName() }
    val searches = tools.count { it.toolName.isSearchToolName() }
    val parts = mutableListOf<String>()
    if (reads > 0) parts += resources.getQuantityString(R.plurals.console_read_files, reads, reads)
    if (lists > 0) parts += resources.getQuantityString(R.plurals.console_listed_dirs, lists, lists)
    if (searches > 0) {
        parts += if (searches == 1) {
            resources.getString(R.string.console_searched_code_once)
        } else {
            resources.getQuantityString(R.plurals.console_searched_code_times, searches, searches)
        }
    }
    return parts.joinToString(resources.getString(R.string.console_lookups_joiner))
        .ifBlank { resources.getString(R.string.console_context_lookups) }
}

internal fun ConversationRenderItem.BusinessToolGroup.summaryLabel(resources: Resources): String {
    val names = tools.map { it.toolName }.distinct()
    return when {
        names.size == 1 -> {
            val name = names.single()
            if (tools.size == 1) name else "$name × ${tools.size}"
        }
        else -> resources.getQuantityString(R.plurals.console_used_tools, tools.size, tools.size)
    }
}

internal fun ConversationRenderItem.RuntimePhaseGroup.summaryLabel(resources: Resources): String {
    val n = phases.size
    val titles = phases.mapNotNull { phase ->
        when (phase) {
            is ConversationItem.RuntimePhaseItem -> phase.title
            is ConversationItem.MessageItem ->
                phase.content.lineSequence().firstOrNull { it.isNotBlank() }
            else -> null
        }
    }
    val preview = titles.firstOrNull()?.take(RUNTIME_GROUP_PREVIEW_CHARS).orEmpty()
    return when {
        preview.isBlank() -> resources.getQuantityString(R.plurals.console_runtime_steps, n, n)
        n == 1 -> preview
        else -> resources.getQuantityString(R.plurals.console_runtime_steps_preview, n, n, preview)
    }
}

internal fun ConversationRenderItem.ContextToolGroup.status(): ToolCallStatus =
    tools.aggregateToolStatus()

internal fun ConversationRenderItem.BusinessToolGroup.status(): ToolCallStatus =
    tools.aggregateToolStatus()

internal fun ConversationItem.ToolCallItem.contextLookupLabel(resources: Resources): String {
    val path = displayPath()
    return when {
        toolName.isSearchToolName() -> searchLookupLabel(resources)
        toolName.isListToolName() ->
            path?.let { resources.getString(R.string.console_listed_path, it) }
                ?: resources.getString(R.string.console_listed_directory)
        else -> path?.let { resources.getString(R.string.console_read_path, it) }
            ?: resources.getString(R.string.console_read_file)
    }
}

internal fun ConversationItem.ToolCallItem.businessToolLineLabel(): String {
    if (toolName.equals("web_search", ignoreCase = true)) {
        val query = argumentField("query", "q", "pattern")
        if (!query.isNullOrBlank()) return "web_search · ${query.cleanLabel()}"
    }
    return toolName
}

private fun List<ConversationItem.ToolCallItem>.aggregateToolStatus(): ToolCallStatus =
    when {
        any { it.status == ToolCallStatus.RUNNING } -> ToolCallStatus.RUNNING
        any { it.status == ToolCallStatus.FAILED } -> ToolCallStatus.FAILED
        any { it.status == ToolCallStatus.UNKNOWN } -> ToolCallStatus.UNKNOWN
        else -> ToolCallStatus.DONE
    }

private fun ConversationItem.isAmbientStatus(): Boolean =
    this is ConversationItem.MessageItem && role == TranscriptRole.STATUS

private fun ConversationItem.isRuntimeTimelineNoise(): Boolean =
    this is ConversationItem.RuntimePhaseItem ||
        (this is ConversationItem.MessageItem && role == TranscriptRole.RUNTIME)

private fun ConversationItem.autoScrollKey(collapsed: Boolean): String =
    when (this) {
        is ConversationItem.TurnHeader -> "$id:${preview.hashCode()}:${fullText.hashCode()}:$collapsed"
        is ConversationItem.MessageItem -> "$id:$role:${content.hashCode()}:$collapsed"
        is ConversationItem.RuntimePhaseItem -> "$id:${title.hashCode()}:${body.hashCode()}:$collapsed:$markdown"
        is ConversationItem.ConfirmationItem -> "$id:$pendingId:$domain:$mode:$status:$resolvedAt"
        is ConversationItem.ToolCallItem -> {
            "$id:$toolCallId:$toolName:$status:${argumentsJson?.hashCode()}:${resultJson?.hashCode()}:$finishedAt:$collapsed"
        }
    }

private fun ConversationItem.ToolCallItem.isContextLookupTool(): Boolean =
    toolName.isReadToolName() || toolName.isListToolName() || toolName.isSearchToolName()

private fun String.isReadToolName(): Boolean =
    equals(READ_FILE_TOOL, ignoreCase = true) || startsWith(READ_TOOL_PREFIX, ignoreCase = true)

private fun String.isListToolName(): Boolean =
    equals(LIST_DIR_TOOL, ignoreCase = true) || startsWith(LIST_TOOL_PREFIX, ignoreCase = true)

private fun String.isSearchToolName(): Boolean =
    equals(GREP_TOOL, ignoreCase = true) ||
        startsWith(GREP_TOOL, ignoreCase = true) ||
        startsWith(SEARCH_TOOL_PREFIX, ignoreCase = true)

private fun ConversationItem.ToolCallItem.searchLookupLabel(resources: Resources): String {
    val query = argumentField("pattern", "query", "keyword", "regex")
    val path = displayPath()
    return when {
        !query.isNullOrBlank() && !path.isNullOrBlank() ->
            resources.getString(R.string.console_searched_for_in, query.cleanLabel(), path)
        !query.isNullOrBlank() -> resources.getString(R.string.console_searched_for, query.cleanLabel())
        !path.isNullOrBlank() -> resources.getString(R.string.console_searched_path, path)
        else -> resources.getString(R.string.console_searched_code)
    }
}

private fun ConversationItem.ToolCallItem.displayPath(): String? =
    argumentField("path", "file", "directory", "root")?.shortPath()

private fun ConversationItem.ToolCallItem.argumentField(vararg names: String): String? {
    val arguments = argumentsJson ?: return null
    val json = arguments.substringAfter(ARGUMENTS_PREFIX, arguments).trim()
    if (!json.startsWith(JSON_OBJECT_START)) return null
    val obj = runCatching { ToolDisplayJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
    return names.firstNotNullOfOrNull { name -> obj[name]?.jsonPrimitive?.contentOrNull }
}

private fun String.shortPath(): String {
    val clean = cleanLabel()
    return clean.substringAfterLast(PATH_SEPARATOR).ifBlank { clean }
}

private fun String.cleanLabel(): String =
    lineSequence().joinToString(" ").trim()

private val ToolDisplayJson = Json { ignoreUnknownKeys = true }

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
