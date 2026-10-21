@file:OptIn(ExperimentalA2aClientApi::class)

package cc.noharry.bothy.sample

import cc.noharry.bothy.a2a.A2aTask
import cc.noharry.bothy.a2a.ExperimentalA2aClientApi
import java.net.URI

/** Overlay stage for client mode, built only from wire items of the current (or latest) task. */
internal fun DebugA2aClientState.overlayHud(nowEpochMillis: Long = System.currentTimeMillis()): DebugOverlayHud {
    val turn = currentTurnItems()
    val terminal = turn.lastOrNull { it is DebugA2aClientItem.Terminal } as? DebugA2aClientItem.Terminal
    val live = running || mirrors.isNotEmpty()
    val phase = when {
        live -> DebugOverlayPhase.LIVE
        terminal == null -> DebugOverlayPhase.IDLE
        terminal.state == A2aTask.Status.FAILED -> DebugOverlayPhase.FAIL
        terminal.state == A2aTask.Status.CANCELLED -> DebugOverlayPhase.STOP
        else -> DebugOverlayPhase.DONE
    }
    val pendingQuestion = questionCards.lastOrNull { it.second != DebugQuestionAnswerMode.NONE }?.first
    val status = (turn.lastOrNull { it is DebugA2aClientItem.Status } as? DebugA2aClientItem.Status)?.text
    val startedAt = runStartedAtEpochMillis ?: mirrors.values.maxOrNull()
    val elapsedMillis = startedAt?.let { (nowEpochMillis - it).coerceAtLeast(0L) } ?: terminal?.elapsedMillis
    val elapsedLabel = elapsedMillis?.let(::formatWorkedDuration) ?: if (live) "0s" else "—"
    val tools = turn.filterIsInstance<DebugA2aClientItem.Tool>().map { tool ->
        DebugOverlayToolLine(
            id = tool.id,
            name = tool.name.substringAfterLast("__").overlayHudLine(TOOL_NAME_MAX),
            statusLabel = tool.phase.ifBlank { "running" },
            durationLabel = tool.finishedAtEpochMillis?.let { formatWorkedDuration((it - tool.startedAtEpochMillis).coerceAtLeast(0L)) },
            isError = tool.phase == TOOL_FAILED,
            running = tool.finishedAtEpochMillis == null,
            argumentsJson = null,
            resultJson = null,
        )
    }
    val speechRows = turn.filterIsInstance<DebugA2aClientItem.Speech>().map {
        DebugOverlayBoardRow("🔊 ${it.text}".overlayHudLine(SPEECH_LINE_MAX), DebugOverlayPlanStatus.DONE)
    }
    val questionRows = listOfNotNull(pendingQuestion).map {
        DebugOverlayBoardRow("问：${it.text}".overlayHudLine(SPEECH_LINE_MAX), DebugOverlayPlanStatus.ACTIVE)
    }
    val display = (turn.lastOrNull { it is DebugA2aClientItem.Display } as? DebugA2aClientItem.Display)?.text
    val lastSpeech = (turn.lastOrNull { it is DebugA2aClientItem.Speech } as? DebugA2aClientItem.Speech)?.text
    val notice = (turn.lastOrNull { it is DebugA2aClientItem.Notice } as? DebugA2aClientItem.Notice)?.text
    // Picks come only from the ended task's display text; a tap goes out like any typed client message.
    val pickable = pickableDisplay()
    val choiceReply = pickable?.reply
    val headline = when (phase) {
        DebugOverlayPhase.LIVE -> pendingQuestion?.let { "等待回答：${it.text}" } ?: status ?: "A2A 任务执行中"
        DebugOverlayPhase.DONE -> if (terminal?.state == A2aTask.Status.INPUT_REQUIRED) "等待你的回复" else "Completed"
        DebugOverlayPhase.FAIL -> "Failed"
        DebugOverlayPhase.STOP -> "Stopped"
        DebugOverlayPhase.IDLE -> "A2A 客户端就绪"
    }
    return DebugOverlayHud(
        phase = phase,
        phaseLabel = if (phase == DebugOverlayPhase.IDLE) "READY" else phase.name,
        elapsedLabel = elapsedLabel,
        providerLabel = "A2A · ${endpointLabel()}${if (speechReady) "" else " · TTS 未配置"}",
        requestPreview = (turn.firstOrNull { it is DebugA2aClientItem.User } as? DebugA2aClientItem.User)?.text?.overlayHudLine(REQUEST_MAX),
        progressDone = 0,
        progressTotal = 0,
        progressLabel = when {
            live -> "In progress"
            lastOutcome != null -> lastOutcome
            else -> "Ready"
        },
        currentStep = status,
        planRows = (speechRows + questionRows).takeLast(HUD_MAX_PLAN_ROWS),
        resultText = (choiceReply?.text ?: display ?: lastSpeech ?: terminal?.text?.takeIf { it.isNotBlank() } ?: notice
            ?: if (live) "等待 A2A 输出…" else "发送一句话，经 A2A 验证显示与播报").overlayHudMultiline(RESULT_MAX),
        timingLine = listOfNotNull(elapsedLabel, "${tools.size} tools".takeIf { tools.isNotEmpty() }).joinToString("  ·  "),
        toolLines = tools.takeLast(HUD_MAX_TOOL_LINES),
        isRunning = live,
        headline = headline.overlayHudLine(HEADLINE_MAX),
        planTitle = "Speech",
        // Status lines are never spoken; an empty speech card must not borrow them.
        planEmptyText = "暂无播报（只播报 channel=speech）",
        choices = choiceReply?.choices.orEmpty(),
        choiceIcons = pickable?.icons.orEmpty(),
        cards = pickable?.cards.orEmpty(),
        controls = pickable?.controls.orEmpty(),
        // The wire carries text only; a picture this car's image tool draws for the task is found by task ID.
        picture = turn.firstNotNullOfOrNull { it.wireTaskId() }?.let(DebugPictureBoard::forTask),
    )
}

/** The A2A task an item came from; the user's own message has none. */
internal fun DebugA2aClientItem.wireTaskId(): String? = when (this) {
    is DebugA2aClientItem.Status -> taskId
    is DebugA2aClientItem.Tool -> taskId
    is DebugA2aClientItem.Speech -> taskId
    is DebugA2aClientItem.Display -> taskId
    is DebugA2aClientItem.Question -> taskId
    is DebugA2aClientItem.Terminal -> taskId
    is DebugA2aClientItem.User, is DebugA2aClientItem.Notice -> null
}

/** Items from the latest new message (late answers included); steering and in-task answers stay in the turn. */
internal fun DebugA2aClientState.currentTurnItems(): List<DebugA2aClientItem> {
    val start = items.indexOfLast { it is DebugA2aClientItem.User && it.kind == DebugA2aClientItem.User.Kind.MESSAGE }
    return if (start >= 0) items.drop(start) else items
}

internal fun DebugA2aClientState.endpointLabel(): String =
    runCatching { URI(endpoint).let { "${it.host}:${it.port}" } }.getOrDefault(endpoint)

private const val TOOL_FAILED = "failed"
private const val TOOL_NAME_MAX = 40
private const val SPEECH_LINE_MAX = 60
private const val REQUEST_MAX = 96
private const val RESULT_MAX = 520
private const val HEADLINE_MAX = 80
