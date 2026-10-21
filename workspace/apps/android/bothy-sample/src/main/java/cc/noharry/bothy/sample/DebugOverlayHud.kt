package cc.noharry.bothy.sample

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Demo-oriented floating panel projection.
 * Optimized for audience readability: clear phase, live elapsed, plan progress,
 * current tool, and final result — not for minimizing on-screen footprint.
 */
internal enum class DebugOverlayPhase {
    LIVE,
    DONE,
    FAIL,
    STOP,
    IDLE,
}

internal data class DebugOverlayToolLine(
    val id: Long,
    val name: String,
    val statusLabel: String,
    val durationLabel: String?,
    val isError: Boolean,
    val running: Boolean,
    val argumentsJson: String?,
    val resultJson: String?,
)

internal data class DebugOverlayHud(
    val phase: DebugOverlayPhase,
    val phaseLabel: String,
    val elapsedLabel: String,
    val providerLabel: String,
    val requestPreview: String?,
    val progressDone: Int,
    val progressTotal: Int,
    val progressLabel: String,
    val currentStep: String?,
    val planRows: List<DebugOverlayBoardRow>,
    val resultText: String,
    val timingLine: String,
    val toolLines: List<DebugOverlayToolLine>,
    val isRunning: Boolean,
    val headline: String,
    /** Right-column card; A2A client mode has no plan on the wire and shows speech/questions there. */
    val planTitle: String = "Plan",
    /** Shown when [planRows] is empty; null keeps the plan card's own fallback. */
    val planEmptyText: String? = null,
    /** Tappable picks offered by the finished reply; [resultText] then omits their lines. */
    val choices: List<DebugDisplayChoice> = emptyList(),
    /** Icons of the picks that have one (an icon list), by label. */
    val choiceIcons: Map<String, String> = emptyMap(),
    /** Information the reply shows as cards, such as the weather; several sit side by side ([overlayCardRows]). */
    val cards: List<DebugCard> = emptyList(),
    /** Values the reply offers to set within a range, confirmed together. */
    val controls: List<DebugControl> = emptyList(),
    /** A picture this turn's image tool is drawing or drew; it leads the card. */
    val picture: DebugOverlayPicture? = null,
) {
    val progressFraction: Float?
        get() = if (progressTotal > 0) progressDone.toFloat() / progressTotal.toFloat() else null
}

/** [reference] is null while the picture is still being drawn; [prompt] is what was asked for. */
internal data class DebugOverlayPicture(val reference: String?, val prompt: String)

/** Card width follows what it must show, so content fits without scrolling. Widths are in the stage's dp. */
internal enum class DebugOverlaySize(val landscapeWidthDp: Int, val screenShare: Float = MAP_KEEPING_SHARE) {
    /** Idle or a sentence or two. */
    COMPACT(560),
    /** Running, up to four picks, or a paragraph. */
    REGULAR(760),
    /** Four or more picks (two columns), a long answer, or open details. */
    WIDE(1080),
    /**
     * A trip: tickets, a column per day and the hotels (owner 2026-10-17: 旅行规划可以做一个更大的大卡). It takes most
     * of the screen; the plan is what the user reads then, not the map.
     */
    GRAND(1500, screenShare = 0.86f),
}

/** Every other size leaves the right ~40% of the map visible. */
private const val MAP_KEEPING_SHARE = 0.6f

internal fun DebugOverlayHud.overlaySize(showDetails: Boolean): DebugOverlaySize = when {
    cards.any { it.layout == DebugCardLayout.TRIP } -> DebugOverlaySize.GRAND
    showDetails || choices.size >= OVERLAY_GRID_MIN_CHOICES || cards.size > 1 || cards.any { it.isWide() } ||
        resultText.length > WIDE_TEXT_CHARS || resultText.lines().size > WIDE_TEXT_LINES -> DebugOverlaySize.WIDE
    picture != null || choices.isNotEmpty() || cards.isNotEmpty() || controls.isNotEmpty() || phase == DebugOverlayPhase.LIVE ||
        resultText.length > COMPACT_TEXT_CHARS ->
        DebugOverlaySize.REGULAR
    else -> DebugOverlaySize.COMPACT
}

/** Three columns of comparison, or a strip of five days or more, need the wide card to keep their text whole. */
private fun DebugCard.isWide(): Boolean =
    (layout == DebugCardLayout.COMPARE && items.size >= 3) || (layout == DebugCardLayout.STRIP && items.size >= WIDE_STRIP_ITEMS)

private const val WIDE_STRIP_ITEMS = 5

/**
 * Two cards to a row on the wide card (深圳 and 广州 side by side); a trip, or a card that needs the wide card itself,
 * takes its own row.
 */
internal fun List<DebugCard>.overlayCardRows(): List<List<DebugCard>> =
    if (size > 1 && none { it.layout == DebugCardLayout.TRIP || it.isWide() }) chunked(CARDS_PER_ROW) else map { listOf(it) }

internal const val CARDS_PER_ROW = 2

/** A finished run that left parts unconfirmed: 部分完成, not 没有完成. */
internal const val PARTLY_DONE_HEADLINE = "Partly done"

/** From this many picks the wide card lays them out in two columns (2×2 already saves a full tile row). */
internal const val OVERLAY_GRID_MIN_CHOICES = 4
private const val WIDE_TEXT_CHARS = 220
private const val WIDE_TEXT_LINES = 7
private const val COMPACT_TEXT_CHARS = 48

/**
 * What the card shows of the local run. Without [showThinking] (设置 → 通用 → 显示思考过程 off) it keeps to what 小博
 * says to the user: no plan, no to-do text as the step, and while working only the spoken progress lines.
 */
internal fun DebugUiState.overlayHud(nowEpochMillis: Long = System.currentTimeMillis(), showThinking: Boolean = true): DebugOverlayHud {
    val phase = overlayPhase()
    val todos = contextPanel.todo
    val done = todos.count { it.status.toOverlayPlanStatus() == DebugOverlayPlanStatus.DONE }
    val total = todos.size
    val activeTodo = todos.firstOrNull { it.status.toOverlayPlanStatus() == DebugOverlayPlanStatus.ACTIVE }
    val runningTool = items.asReversed().firstOrNull {
        it is DebugConversationItem.ToolCallItem && it.status == DebugToolCallStatus.RUNNING
    } as? DebugConversationItem.ToolCallItem
    val currentStep = when {
        activeTodo != null && showThinking -> activeTodo.content.overlayHudLine(HUD_STEP_MAX)
        // A tool's name reads as the kind of work (see stepPhrase), never as what the model wrote.
        runningTool != null -> "Running ${shortToolName(runningTool.toolName)}"
        isRunning && showThinking -> statusHeader(this).overlayHudLine(HUD_STEP_MAX)
        isRunning -> HIDDEN_THINKING_STEP
        else -> null
    }
    val progressLabel = when {
        total > 0 -> "$done / $total"
        isRunning -> "In progress"
        !lastRunSummary.isNullOrBlank() -> lastRunSummary
        else -> "Ready"
    }
    val elapsedLabel = overlayElapsedLabel(nowEpochMillis)
    // Only the latest user turn — never sum historical turns into demo timing.
    val turnItems = items.currentTurnItems()
    val tools = turnItems.overlayToolLines()
    val businessDone = tools.count {
        !it.running && !it.isError && it.name.looksLikeBusinessTool()
    }
    val headline = when (phase) {
        DebugOverlayPhase.LIVE -> currentStep ?: "Agent working"
        DebugOverlayPhase.DONE -> if (lastRunSummary?.endsWith(PARTIAL_SUMMARY) == true) PARTLY_DONE_HEADLINE else "Completed"
        DebugOverlayPhase.FAIL -> "Failed"
        DebugOverlayPhase.STOP -> "Stopped"
        DebugOverlayPhase.IDLE -> "Agent ready"
    }
    val pickable = pickableReply()
    val choiceReply = pickable?.reply
    return DebugOverlayHud(
        phase = phase,
        phaseLabel = phase.wireLabel(),
        elapsedLabel = elapsedLabel,
        providerLabel = providerLabel.takeIf { it.isNotBlank() }.orEmpty(),
        requestPreview = currentRequest?.text?.overlayHudLine(HUD_REQUEST_MAX),
        progressDone = done,
        progressTotal = total,
        progressLabel = progressLabel,
        currentStep = currentStep,
        planRows = todos.takeIf { showThinking }.orEmpty().take(HUD_MAX_PLAN_ROWS).map { todo ->
            DebugOverlayBoardRow(
                text = todo.content.overlayHudLine(HUD_PLAN_LINE_MAX),
                planStatus = todo.status.toOverlayPlanStatus(),
            )
        },
        resultText = choiceReply?.text?.overlayHudMultiline(HUD_RESULT_MAX) ?: overlayResultText(showThinking),
        planEmptyText = HIDDEN_THINKING_PLAN.takeUnless { showThinking },
        // Wall-clock run time is the demo truth; do not sum concurrent tool durations.
        timingLine = buildTimingLine(elapsedLabel, tools.size, businessDone),
        toolLines = tools.takeLast(HUD_MAX_TOOL_LINES),
        isRunning = isRunning,
        headline = headline.overlayHudLine(HUD_HEADLINE_MAX),
        choices = choiceReply?.choices.orEmpty(),
        choiceIcons = pickable?.icons.orEmpty(),
        cards = pickable?.cards.orEmpty(),
        controls = pickable?.controls.orEmpty(),
        // Cards own the stage: the first one's single photo, or none when their pictures sit in their rows (a trip drawn
        // this turn).
        picture = pickable?.cards.orEmpty().firstNotNullOfOrNull { card -> card.framedPicture?.let { DebugOverlayPicture(it, card.title) } }
            ?: turnItems.overlayPicture().takeIf { pickable?.cards.isNullOrEmpty() },
    )
}

/**
 * The latest image tool call of this turn: drawing while it runs, the saved picture once it succeeded, nothing
 * after a failure (the reply explains it). The reference is matched in the result text so a wrapped or escaped
 * result still yields it.
 */
internal fun List<DebugConversationItem>.overlayPicture(): DebugOverlayPicture? =
    asReversed().firstNotNullOfOrNull { item -> (item as? DebugConversationItem.ToolCallItem)?.takeIf { it.isImageGenerateCall() } }
        ?.generatedPicture()

internal fun DebugConversationItem.ToolCallItem.isImageGenerateCall(): Boolean = shortToolName(toolName) == IMAGE_GENERATE_TOOL

/** The picture of one image tool call, for the overlay and the console alike; null for other tools. */
internal fun DebugConversationItem.ToolCallItem.generatedPicture(): DebugOverlayPicture? {
    if (!isImageGenerateCall()) return null
    // Before the raw transcript arrives the console holds a placeholder, not JSON; the prompt is then unknown.
    // The raw transcript stores arguments as `arguments={...}`.
    val arguments = argumentsJson.orEmpty().let { it.substringAfter("arguments=", it) }.trim()
    val prompt = runCatching {
        Json.parseToJsonElement(arguments).jsonObject["prompt"]?.jsonPrimitive?.contentOrNull
    }.getOrNull().orEmpty()
    return when (status) {
        DebugToolCallStatus.RUNNING -> DebugOverlayPicture(null, prompt)
        DebugToolCallStatus.DONE ->
            resultJson?.let { PICTURE_REFERENCE.find(it)?.groupValues?.get(1) }?.let { DebugOverlayPicture(it, prompt) }
        DebugToolCallStatus.FAILED, DebugToolCallStatus.UNKNOWN -> null
    }
}

/** A whole file name with its extension: a redacted name (`img-[REDACTED_PHONE].jpg`) matches nothing. */
private val PICTURE_REFERENCE =
    Regex("""\\?"reference\\?"\s*:\s*\\?"($GENERATED_IMAGES_DIR/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+\.(?:jpg|png|webp))\\?"""")

/**
 * What the current turn's finished reply shows beyond its text: picks (with their icons), a panel, cards. [reply]
 * holds the text without the pick lines; it is the whole text when there are no picks.
 */
internal data class DebugPickableReply(
    val messageId: Long,
    val reply: DebugDisplayReply,
    val cards: List<DebugCard> = emptyList(),
    val controls: List<DebugControl> = emptyList(),
    val icons: Map<String, String> = emptyMap(),
)

/** The reply's widgets from this turn's offer; null when the reply shows nothing but text. */
internal fun debugPickableReply(messageId: Long, text: String, offer: DebugOffer?): DebugPickableReply? {
    // Beside a trip card a numbered list is no pick (owner 2026-10-17: 下面又一个酒店选择，这种选择有什么意义): the plan
    // already holds the hotel, and a tap would only send 「第1个」 back while the reply asks something else. Beside any
    // other card the list is the card's items again (owner 2026-10-18: 还是出现了选一个这种控件: a hotel card, the same
    // hotels registered with offer_options without a call, and marked 推荐): a pick only when the reply asks which one
    // or a registered option does something when picked.
    val cards = offer?.cards.orEmpty()
    val options = offer?.options.orEmpty()
    val listsOnly = cards.any { it.layout == DebugCardLayout.TRIP } && options.isEmpty()
    val labels = if (cards.isEmpty() || options.any { it.call != null }) options.map { it.label } else emptyList()
    val reply = if (listsOnly) DebugDisplayReply(text, emptyList()) else text.displayChoiceReply(labels, markedPicks = cards.isEmpty())
    val widgets = DebugPickableReply(messageId, reply, cards, offer?.controls.orEmpty(), offer?.icons.orEmpty())
    return widgets.takeIf { reply.choices.isNotEmpty() || it.cards.isNotEmpty() || it.controls.isNotEmpty() }
}

/**
 * The current turn's finished reply when it offers picks; the overlay card and the console show the same list.
 * A partial/blocked reply that asks for a pick shows FAIL yet is still answerable, so only a live or stopped run
 * closes it. Only this turn's reply counts: an older offer is never pending.
 */
internal fun DebugUiState.pickableReply(): DebugPickableReply? =
    if (isRunning || overlayPhase() == DebugOverlayPhase.STOP) {
        null
    } else {
        items.currentTurnItems().finishedReplyChoices(DebugOfferStore.latest(activeSessionId))
    }

private fun List<DebugConversationItem>.finishedReplyChoices(offer: DebugOffer?): DebugPickableReply? {
    val reply = asReversed().firstOrNull { item ->
        item is DebugConversationItem.MessageItem &&
            item.role == DebugTranscriptRole.ASSISTANT &&
            item.content.isNotBlank() &&
            !item.content.trimStart().startsWith("TTS:", ignoreCase = true) &&
            !item.content.contains("MODEL_FINISHED_WITHOUT_RUNTIME_FINISH")
    } as? DebugConversationItem.MessageItem ?: return null
    return debugPickableReply(reply.id, reply.content.overlayHudMultiline(Int.MAX_VALUE).displayReplyText(), offer)
}

/** Board sections derived from HUD (tests + legacy). */
internal fun DebugUiState.overlayBoardSections(): List<DebugOverlayBoardSection> {
    val hud = overlayHud()
    return buildList {
        add(
            textOverlaySection(
                "Run",
                listOfNotNull(
                    "${hud.phaseLabel} · ${hud.elapsedLabel}",
                    hud.headline,
                    hud.providerLabel.takeIf { it.isNotBlank() },
                ),
            ),
        )
        if (hud.planRows.isNotEmpty()) {
            add(DebugOverlayBoardSection("Plan", hud.planRows))
        }
        add(textOverlaySection("Result", listOf(hud.resultText)))
        add(textOverlaySection("Timing", listOf(hud.timingLine)))
        if (tokenUsage.hasUsage) {
            add(textOverlaySection("Usage", tokenUsage.overlayUsageLines()))
        }
    }
}

private fun DebugUiState.overlayPhase(): DebugOverlayPhase =
    when {
        isRunning -> DebugOverlayPhase.LIVE
        // Prefer lastRunSummary outcome over sticky status strings that may still
        // contain "Failed" from an intermediate AgentEvent before finalize.
        lastRunSummary?.contains("Failed", ignoreCase = true) == true ||
            lastRunSummary?.contains("replay failed", ignoreCase = true) == true -> DebugOverlayPhase.FAIL
        lastRunSummary?.contains("Stopped", ignoreCase = true) == true ||
            status.contains("Stopped", ignoreCase = true) -> DebugOverlayPhase.STOP
        lastRunSummary?.startsWith("Worked for", ignoreCase = true) == true ||
            status.startsWith("Worked for", ignoreCase = true) -> DebugOverlayPhase.DONE
        // Only treat status as FAIL when there is no successful completion summary.
        status.contains("fail", ignoreCase = true) ||
            status.startsWith("Failed", ignoreCase = true) -> DebugOverlayPhase.FAIL
        !lastRunSummary.isNullOrBlank() -> DebugOverlayPhase.DONE
        else -> DebugOverlayPhase.IDLE
    }

private fun DebugOverlayPhase.wireLabel(): String =
    when (this) {
        DebugOverlayPhase.LIVE -> "LIVE"
        DebugOverlayPhase.DONE -> "DONE"
        DebugOverlayPhase.FAIL -> "FAIL"
        DebugOverlayPhase.STOP -> "STOP"
        DebugOverlayPhase.IDLE -> "READY"
    }

private fun DebugUiState.overlayElapsedLabel(nowEpochMillis: Long): String {
    val started = runStartedAtEpochMillis
    if (started != null && isRunning) {
        return formatWorkedDuration((nowEpochMillis - started).coerceAtLeast(0L))
    }
    lastRunSummary?.let { summary ->
        extractWorkedDurationLabel(summary)?.let { return it }
    }
    return if (isRunning) "0s" else "—"
}

private fun extractWorkedDurationLabel(summary: String): String? {
    val worked = Regex("""Worked for ([^·]+)""").find(summary)?.groupValues?.getOrNull(1)?.trim()
    if (!worked.isNullOrBlank()) return worked
    val after = Regex("""(?:Failed|Stopped) after ([^·]+)""").find(summary)?.groupValues?.getOrNull(1)?.trim()
    return after?.takeIf { it.isNotBlank() }
}

private fun DebugUiState.overlayResultText(showThinking: Boolean = true): String {
    if (isRunning) {
        // Only this turn: an earlier answer shown under "processing" reads as the result of the new request.
        // A spoken progress line is what the device just said, so it captions the stage without its marker.
        val progress = items.currentTurnItems().asReversed().firstOrNull { item ->
            item is DebugConversationItem.MessageItem &&
                item.role == DebugTranscriptRole.ASSISTANT &&
                item.content.isNotBlank()
        } as? DebugConversationItem.MessageItem
        // The step itself is on the progress row, so no progress text leaves the body empty.
        val shown = if (showThinking) progress?.content else progress?.content?.spokenProgress()?.joinToString("\n")
        return shown?.replace(TTS_PROGRESS_PREFIX, "")?.overlayHudMultiline(HUD_RESULT_MAX).orEmpty()
    }
    if (!showThinking) finishedWithoutThinking()?.let { return it }
    val assistant = items.asReversed().firstOrNull { item ->
        item is DebugConversationItem.MessageItem &&
            item.role == DebugTranscriptRole.ASSISTANT &&
            item.content.isNotBlank() &&
            !item.content.trimStart().startsWith("TTS:", ignoreCase = true) &&
            // Don't treat protocol error bodies that were mirrored as assistant text.
            !item.content.contains("MODEL_FINISHED_WITHOUT_RUNTIME_FINISH")
    } as? DebugConversationItem.MessageItem
    val error = items.asReversed().firstOrNull { item ->
        item is DebugConversationItem.MessageItem && item.role == DebugTranscriptRole.ERROR
    } as? DebugConversationItem.MessageItem
    // Prefer audience-facing answer even when protocol failed without finish().
    return when {
        assistant != null -> assistant.content.overlayHudMultiline(HUD_RESULT_MAX)
        error != null -> error.content.overlayHudMultiline(HUD_RESULT_MAX)
        !lastRunSummary.isNullOrBlank() -> lastRunSummary
        else -> "Waiting for a run — send a prompt to start the demo"
    }
}

/**
 * A finished run's card with 显示思考过程 off: the run's answer, else its error, else what it said aloud. A run stopped
 * or failed before answering left only the text streamed while it worked, which is the thinking the switch hides.
 */
private fun DebugUiState.finishedWithoutThinking(): String? {
    val turn = items.currentTurnItems().filterIsInstance<DebugConversationItem.MessageItem>()
    turn.lastOrNull { it.role == DebugTranscriptRole.ASSISTANT && it.final && it.content.isNotBlank() }
        ?.let { return it.content.replace(TTS_PROGRESS_PREFIX, "").overlayHudMultiline(HUD_RESULT_MAX) }
    turn.lastOrNull { it.role == DebugTranscriptRole.ERROR }?.let { return it.content.overlayHudMultiline(HUD_RESULT_MAX) }
    val spoken = turn.lastOrNull { it.role == DebugTranscriptRole.ASSISTANT && it.content.isNotBlank() }
        ?.content?.spokenProgress()?.joinToString("\n")?.takeIf { it.isNotBlank() }
    return spoken?.overlayHudMultiline(HUD_RESULT_MAX) ?: lastRunSummary?.takeIf { it.isNotBlank() }
}

private fun buildTimingLine(
    elapsedLabel: String,
    toolCount: Int,
    businessDone: Int,
): String =
    buildList {
        add(elapsedLabel)
        if (toolCount > 0) add("$toolCount tools")
        if (businessDone > 0) add("$businessDone business")
    }.joinToString("  ·  ")

/** Items belonging to the latest user turn (after the last TurnHeader). */
internal fun List<DebugConversationItem>.currentTurnItems(): List<DebugConversationItem> {
    val turnStart = indexOfLast { it is DebugConversationItem.TurnHeader }
    return if (turnStart >= 0) drop(turnStart + 1) else this
}

private fun List<DebugConversationItem>.overlayToolLines(): List<DebugOverlayToolLine> =
    mapNotNull { item ->
        val tool = item as? DebugConversationItem.ToolCallItem ?: return@mapNotNull null
        val durationMs = tool.finishedAt?.let { end -> (end - tool.startedAt).coerceAtLeast(0L) }
        DebugOverlayToolLine(
            id = tool.id,
            name = shortToolName(tool.toolName),
            statusLabel = tool.status.name.lowercase(),
            durationLabel = durationMs?.let { formatWorkedDuration(it) },
            isError = tool.status == DebugToolCallStatus.FAILED,
            running = tool.status == DebugToolCallStatus.RUNNING,
            argumentsJson = tool.argumentsJson,
            resultJson = tool.resultJson,
        )
    }

private fun String.looksLikeBusinessTool(): Boolean {
    val lower = lowercase()
    if (lower in setOf("update_todo", "finish", "search_tools", "read_file", "list_dir", "grep", "web_search")) {
        return false
    }
    return lower.contains("command_") || lower.contains("mcp__")
}

private fun shortToolName(name: String): String {
    val trimmed = name.substringAfterLast('.').substringAfterLast("__")
    return trimmed.ifBlank { name }.overlayHudLine(HUD_TOOL_NAME_MAX)
}

private fun DebugTokenUsageState.overlayUsageLines(): List<String> {
    val cache = cacheHitRate?.let { " · cache $it%" }.orEmpty()
    return listOf(
        "${inputTokens} in · ${outputTokens} out$cache",
        "Total $totalTokens · $requestCount req",
    )
}

private fun textOverlaySection(title: String, lines: List<String>): DebugOverlayBoardSection =
    DebugOverlayBoardSection(title, lines.map { DebugOverlayBoardRow(it) })

internal fun String.toOverlayPlanStatus(): DebugOverlayPlanStatus =
    when {
        isOverlayPlanActive() -> DebugOverlayPlanStatus.ACTIVE
        lowercase() in setOf("completed", "done", "success") -> DebugOverlayPlanStatus.DONE
        lowercase().contains("fail") -> DebugOverlayPlanStatus.FAILED
        else -> DebugOverlayPlanStatus.WAITING
    }

private fun String.isOverlayPlanActive(): Boolean =
    lowercase() in setOf("in_progress", "running", "active", "doing")

internal fun String.overlayHudLine(maxChars: Int): String {
    val single = lineSequence().joinToString(" ").trim().replace(Regex("""\s+"""), " ")
    return if (single.length <= maxChars) single else single.take(maxChars - 1) + "…"
}

internal fun String.overlayHudMultiline(maxChars: Int): String {
    // Models often emit literal "\n" / markdown; normalize for audience reading.
    val text = trim()
        .replace("\\r\\n", "\n")
        .replace("\\\\n", "\n")
        .replace("\\n", "\n")
        .replace("\\t", " ")
        .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
        // The voice skill names its parts 说/看; a model sometimes writes the label itself on its own line.
        .lines().filterNot { it.trim() in SKILL_SECTION_LABELS }.joinToString("\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
    if (text.length <= maxChars) return text
    return text.take(maxChars - 1) + "…"
}

private val SKILL_SECTION_LABELS = setOf("说", "看", "说：", "看：", "说:", "看:")
// Anywhere in a line, as the announcer finds it: streamed rounds run together ("…temperature.TTS: 先查…").
internal val TTS_PROGRESS_PREFIX = Regex("""(?i)(?<![A-Za-z0-9_])TTS:\s*""")

private const val HIDDEN_THINKING_STEP = "正在处理"
private const val HIDDEN_THINKING_PLAN = "思考过程已隐藏（设置 → 通用 → 显示思考过程）"

internal const val HUD_MAX_PLAN_ROWS = 8
internal const val HUD_MAX_TOOL_LINES = 10
private const val HUD_STEP_MAX = 72
private const val HUD_HEADLINE_MAX = 80
private const val HUD_REQUEST_MAX = 96
private const val HUD_PLAN_LINE_MAX = 48
private const val HUD_RESULT_MAX = 520
private const val HUD_TOOL_NAME_MAX = 40
