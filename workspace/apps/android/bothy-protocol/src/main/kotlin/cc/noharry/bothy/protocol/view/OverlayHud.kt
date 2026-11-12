package cc.noharry.bothy.protocol.view

import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.GeneratedPicture
import cc.noharry.agent.appserver.protocol.RunActivity
import cc.noharry.agent.appserver.protocol.RunOutcome
import cc.noharry.agent.appserver.protocol.TokenUsageState
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.view.CardLayout
import cc.noharry.agent.appserver.protocol.view.Control
import cc.noharry.agent.appserver.protocol.view.DisplayChoice
import cc.noharry.agent.appserver.protocol.view.WidgetCard
import cc.noharry.agent.appserver.protocol.view.formatWorkedDuration
import cc.noharry.agent.appserver.protocol.view.headerWords
import cc.noharry.agent.appserver.protocol.view.label

data class OverlayHud(
    val phase: OverlayPhase,
    val phaseLabel: String,
    val elapsedLabel: String,
    val providerLabel: String,
    val requestPreview: String?,
    val progressDone: Int,
    val progressTotal: Int,
    val progressLabel: String,
    val currentStep: String?,
    val planRows: List<OverlayBoardRow>,
    val resultText: String,
    val timingLine: String,
    val toolLines: List<OverlayToolLine>,
    val isRunning: Boolean,
    val headline: String,
    /** Right-column card; A2A client mode has no plan on the wire and shows speech/questions there. */
    val planTitle: String = "Plan",
    /** Shown when [planRows] is empty; null keeps the plan card's own fallback. */
    val planEmptyText: String? = null,
    /** 显示思考过程 is off, so the plan card says where to turn it on instead of [planEmptyText]. */
    val thinkingHidden: Boolean = false,
    /** Tappable picks offered by the finished reply; [resultText] then omits their lines. */
    val choices: List<DisplayChoice> = emptyList(),
    /** Icons of the picks that have one (an icon list), by label. */
    val choiceIcons: Map<String, String> = emptyMap(),
    /** Information the reply shows as cards, such as the weather; several sit side by side ([overlayCardRows]). */
    val cards: List<WidgetCard> = emptyList(),
    /** Values the reply offers to set within a range, confirmed together. */
    val controls: List<Control> = emptyList(),
    /** A picture this turn's image tool is drawing or drew; it leads the card. */
    val picture: GeneratedPicture? = null,
) {
    val progressFraction: Float?
        get() = if (progressTotal > 0) progressDone.toFloat() / progressTotal.toFloat() else null
}

fun OverlayHud.overlaySize(showDetails: Boolean): OverlaySize = when {
    cards.any { it.layout == CardLayout.TRIP } -> OverlaySize.GRAND
    showDetails || choices.size >= OVERLAY_GRID_MIN_CHOICES || cards.size > 1 || cards.any { it.isWide() } ||
        resultText.length > WIDE_TEXT_CHARS || resultText.lines().size > WIDE_TEXT_LINES -> OverlaySize.WIDE
    picture != null || choices.isNotEmpty() || cards.isNotEmpty() || controls.isNotEmpty() || phase == OverlayPhase.LIVE ||
        resultText.length > COMPACT_TEXT_CHARS ->
        OverlaySize.REGULAR
    else -> OverlaySize.COMPACT
}

/** Three columns of comparison, or a strip of five days or more, need the wide card to keep their text whole. */
private fun WidgetCard.isWide(): Boolean =
    (layout == CardLayout.COMPARE && items.size >= 3) || (layout == CardLayout.STRIP && items.size >= WIDE_STRIP_ITEMS)

private const val WIDE_STRIP_ITEMS = 5

private const val WIDE_TEXT_CHARS = 220

private const val WIDE_TEXT_LINES = 7

private const val COMPACT_TEXT_CHARS = 48

/**
 * What the card shows of the local run. Without [showThinking] (设置 → 通用 → 显示思考过程 off) it keeps to what 小博
 * says to the user: no plan, no to-do text as the step, and while working only the spoken progress lines.
 */
fun ServerState.overlayHud(nowEpochMillis: Long = System.currentTimeMillis(), showThinking: Boolean = true): OverlayHud {
    val phase = overlayPhase()
    val todos = contextPanel.todo
    val done = todos.count { it.status.toOverlayPlanStatus() == OverlayPlanStatus.DONE }
    val total = todos.size
    val activeTodo = todos.firstOrNull { it.status.toOverlayPlanStatus() == OverlayPlanStatus.ACTIVE }
    val runningTool = items.asReversed().firstOrNull {
        it is ConversationItem.ToolCallItem && it.status == ToolCallStatus.RUNNING
    } as? ConversationItem.ToolCallItem
    val currentStep = when {
        activeTodo != null && showThinking -> activeTodo.content.overlayHudLine(HUD_STEP_MAX)
        // A tool's name reads as the kind of work (see stepPhrase), never as what the model wrote.
        runningTool != null -> "Running ${shortToolName(runningTool.toolName)}"
        isRunning && showThinking -> activity.headerWords(activityTool).overlayHudLine(HUD_STEP_MAX)
        isRunning -> HIDDEN_THINKING_STEP
        else -> null
    }
    val progressLabel = when {
        total > 0 -> "$done / $total"
        isRunning -> "In progress"
        else -> lastRun?.label() ?: "Ready"
    }
    val elapsedLabel = overlayElapsedLabel(nowEpochMillis)
    // Only the latest user turn — never sum historical turns into demo timing.
    val turnItems = items.currentTurnItems()
    val tools = turnItems.overlayToolLines()
    val businessDone = tools.count {
        !it.running && !it.isError && it.name.looksLikeBusinessTool()
    }
    val headline = when (phase) {
        OverlayPhase.LIVE -> currentStep ?: "Agent working"
        OverlayPhase.DONE -> when (lastRun?.outcome) {
            RunOutcome.PARTIAL -> PARTLY_DONE_HEADLINE
            RunOutcome.BLOCKED -> NOT_DONE_HEADLINE
            else -> "Completed"
        }
        OverlayPhase.FAIL -> "Failed"
        OverlayPhase.STOP -> "Stopped"
        OverlayPhase.IDLE -> "Agent ready"
    }
    val pickable = pickableReply()
    val choiceReply = pickable?.reply
    return OverlayHud(
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
            OverlayBoardRow(
                text = todo.content.overlayHudLine(HUD_PLAN_LINE_MAX),
                planStatus = todo.status.toOverlayPlanStatus(),
            )
        },
        resultText = choiceReply?.text?.overlayHudMultiline(HUD_RESULT_MAX) ?: overlayResultText(showThinking),
        thinkingHidden = !showThinking,
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
        picture = pickable?.cards.orEmpty().firstNotNullOfOrNull { card -> card.framedPicture?.let { GeneratedPicture(it, card.title) } }
            ?: turnItems.overlayPicture().takeIf { pickable?.cards.isNullOrEmpty() },
    )
}

/** Board sections derived from HUD (tests + legacy). */
fun ServerState.overlayBoardSections(): List<OverlayBoardSection> {
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
            add(OverlayBoardSection("Plan", hud.planRows))
        }
        add(textOverlaySection("Result", listOf(hud.resultText)))
        add(textOverlaySection("Timing", listOf(hud.timingLine)))
        if (tokenUsage.hasUsage) {
            add(textOverlaySection("Usage", tokenUsage.overlayUsageLines()))
        }
    }
}

internal fun ServerState.overlayPhase(): OverlayPhase {
    val last = lastRun
    return when {
        isRunning -> OverlayPhase.LIVE
        // How the last run ended wins over what the run said last (an intermediate event may have said it failed).
        last != null -> when (last.outcome) {
            RunOutcome.FAILED -> OverlayPhase.FAIL
            RunOutcome.STOPPED -> OverlayPhase.STOP
            // A failed replay is the run evidence check's verdict, for the bench and the console: the person got an answer.
            // So is an answer that some or all of it could not be done (partial, blocked): it says so in its headline.
            RunOutcome.COMPLETED, RunOutcome.PARTIAL, RunOutcome.BLOCKED, RunOutcome.REPLAY_FAILED -> OverlayPhase.DONE
        }
        activity == RunActivity.STOPPED -> OverlayPhase.STOP
        activity == RunActivity.FAILED -> OverlayPhase.FAIL
        else -> OverlayPhase.IDLE
    }
}

private fun OverlayPhase.wireLabel(): String =
    when (this) {
        OverlayPhase.LIVE -> "LIVE"
        OverlayPhase.DONE -> "DONE"
        OverlayPhase.FAIL -> "FAIL"
        OverlayPhase.STOP -> "STOP"
        OverlayPhase.IDLE -> "READY"
    }

private fun ServerState.overlayElapsedLabel(nowEpochMillis: Long): String {
    val started = runStartedAtEpochMillis
    if (started != null && isRunning) {
        return formatWorkedDuration((nowEpochMillis - started).coerceAtLeast(0L))
    }
    lastRun?.let { return formatWorkedDuration(it.durationMs) }
    return if (isRunning) "0s" else "—"
}

private fun ServerState.overlayResultText(showThinking: Boolean = true): String {
    if (isRunning) {
        // Only this turn: an earlier answer shown under "processing" reads as the result of the new request.
        // What the run says before its tools is what the device just said, so it captions the stage.
        val progress = items.currentTurnItems().asReversed().firstOrNull { item ->
            item is ConversationItem.MessageItem &&
                item.role == TranscriptRole.ASSISTANT &&
                item.content.isNotBlank()
        } as? ConversationItem.MessageItem
        return progress?.content?.overlayHudMultiline(HUD_RESULT_MAX).orEmpty()
    }
    if (!showThinking) finishedWithoutThinking()?.let { return it }
    val assistant = items.asReversed().firstOrNull { item ->
        item is ConversationItem.MessageItem &&
            item.role == TranscriptRole.ASSISTANT &&
            item.content.isNotBlank() &&
            // Don't treat protocol error bodies that were mirrored as assistant text.
            !item.content.contains("MODEL_FINISHED_WITHOUT_RUNTIME_FINISH")
    } as? ConversationItem.MessageItem
    val error = items.asReversed().firstOrNull { item ->
        item is ConversationItem.MessageItem && item.role == TranscriptRole.ERROR
    } as? ConversationItem.MessageItem
    // Prefer audience-facing answer even when protocol failed without finish().
    return when {
        assistant != null -> assistant.content.overlayHudMultiline(HUD_RESULT_MAX)
        error != null -> error.content.overlayHudMultiline(HUD_RESULT_MAX)
        else -> lastRun?.label() ?: WAITING_FOR_RUN_TEXT
    }
}

/**
 * A finished run's card with 显示思考过程 off: the run's answer, else its error, else what it said aloud. A run stopped
 * or failed before answering left only the text streamed while it worked, which is the thinking the switch hides.
 */
private fun ServerState.finishedWithoutThinking(): String? {
    val turn = items.currentTurnItems().filterIsInstance<ConversationItem.MessageItem>()
    turn.lastOrNull { it.role == TranscriptRole.ASSISTANT && it.final && it.content.isNotBlank() }
        ?.let { return it.content.overlayHudMultiline(HUD_RESULT_MAX) }
    turn.lastOrNull { it.role == TranscriptRole.ERROR }?.let { return it.content.overlayHudMultiline(HUD_RESULT_MAX) }
    val spoken = turn.lastOrNull { it.role == TranscriptRole.ASSISTANT && it.content.isNotBlank() }?.content
    return spoken?.overlayHudMultiline(HUD_RESULT_MAX) ?: lastRun?.label()
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

private fun List<ConversationItem>.overlayToolLines(): List<OverlayToolLine> =
    mapNotNull { item ->
        val tool = item as? ConversationItem.ToolCallItem ?: return@mapNotNull null
        val durationMs = tool.finishedAt?.let { end -> (end - tool.startedAt).coerceAtLeast(0L) }
        OverlayToolLine(
            id = tool.id,
            name = shortToolName(tool.toolName),
            statusLabel = tool.status.name.lowercase(),
            durationLabel = durationMs?.let { formatWorkedDuration(it) },
            isError = tool.status == ToolCallStatus.FAILED,
            running = tool.status == ToolCallStatus.RUNNING,
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

private fun TokenUsageState.overlayUsageLines(): List<String> {
    val cache = cacheHitRate?.let { " · cache $it%" }.orEmpty()
    return listOf(
        "${inputTokens} in · ${outputTokens} out$cache",
        "Total $totalTokens · $requestCount req",
    )
}

private fun textOverlaySection(title: String, lines: List<String>): OverlayBoardSection =
    OverlayBoardSection(title, lines.map { OverlayBoardRow(it) })

private const val HUD_STEP_MAX = 72

private const val HUD_HEADLINE_MAX = 80

private const val HUD_REQUEST_MAX = 96

private const val HUD_PLAN_LINE_MAX = 48

private const val HUD_RESULT_MAX = 520

private const val HUD_TOOL_NAME_MAX = 40
