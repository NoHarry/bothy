@file:OptIn(ExperimentalA2aClientApi::class)

package cc.noharry.bothy.app.a2a
import cc.noharry.agent.a2a.A2aTask
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import cc.noharry.bothy.app.a2ui.QuestionAnswerMode
import cc.noharry.bothy.app.a2ui.pickableDisplay
import cc.noharry.bothy.app.a2ui.questionCards
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.image.PictureBoard
import cc.noharry.bothy.app.overlay.HUD_MAX_PLAN_ROWS
import cc.noharry.bothy.app.overlay.HUD_MAX_TOOL_LINES
import cc.noharry.bothy.app.overlay.OverlayBoardRow
import cc.noharry.bothy.app.overlay.OverlayHud
import cc.noharry.bothy.app.overlay.OverlayPhase
import cc.noharry.bothy.app.overlay.OverlayPlanStatus
import cc.noharry.bothy.app.overlay.OverlayToolLine
import cc.noharry.bothy.app.overlay.currentTurnItems
import cc.noharry.bothy.app.overlay.overlayHud
import cc.noharry.bothy.app.overlay.overlayHudLine
import cc.noharry.bothy.app.overlay.overlayHudMultiline
import cc.noharry.bothy.app.runtime.formatWorkedDuration
import java.net.URI

/** Overlay stage for client mode, built only from wire items of the current (or latest) task. */
internal fun A2aClientState.overlayHud(nowEpochMillis: Long = System.currentTimeMillis()): OverlayHud {
    val turn = currentTurnItems()
    val terminal = turn.lastOrNull { it is A2aClientItem.Terminal } as? A2aClientItem.Terminal
    val live = running || mirrors.isNotEmpty()
    val phase = when {
        live -> OverlayPhase.LIVE
        terminal == null -> OverlayPhase.IDLE
        terminal.state == A2aTask.Status.FAILED -> OverlayPhase.FAIL
        terminal.state == A2aTask.Status.CANCELLED -> OverlayPhase.STOP
        else -> OverlayPhase.DONE
    }
    val pendingQuestion = questionCards.lastOrNull { it.second != QuestionAnswerMode.NONE }?.first
    val status = (turn.lastOrNull { it is A2aClientItem.Status } as? A2aClientItem.Status)?.text
    val startedAt = runStartedAtEpochMillis ?: mirrors.values.maxOrNull()
    val elapsedMillis = startedAt?.let { (nowEpochMillis - it).coerceAtLeast(0L) } ?: terminal?.elapsedMillis
    val elapsedLabel = elapsedMillis?.let(::formatWorkedDuration) ?: if (live) "0s" else "—"
    val tools = turn.filterIsInstance<A2aClientItem.Tool>().map { tool ->
        OverlayToolLine(
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
    val speechRows = turn.filterIsInstance<A2aClientItem.Speech>().map {
        OverlayBoardRow("🔊 ${it.text}".overlayHudLine(SPEECH_LINE_MAX), OverlayPlanStatus.DONE)
    }
    val questionRows = listOfNotNull(pendingQuestion).map {
        OverlayBoardRow("问：${it.text}".overlayHudLine(SPEECH_LINE_MAX), OverlayPlanStatus.ACTIVE)
    }
    val display = (turn.lastOrNull { it is A2aClientItem.Display } as? A2aClientItem.Display)?.text
    val lastSpeech = (turn.lastOrNull { it is A2aClientItem.Speech } as? A2aClientItem.Speech)?.text
    val notice = (turn.lastOrNull { it is A2aClientItem.Notice } as? A2aClientItem.Notice)?.text
    // Picks come only from the ended task's display text; a tap goes out like any typed client message.
    val pickable = pickableDisplay()
    val choiceReply = pickable?.reply
    val headline = when (phase) {
        OverlayPhase.LIVE -> pendingQuestion?.let { "等待回答：${it.text}" } ?: status ?: "A2A 任务执行中"
        OverlayPhase.DONE -> if (terminal?.state == A2aTask.Status.INPUT_REQUIRED) "等待你的回复" else "Completed"
        OverlayPhase.FAIL -> "Failed"
        OverlayPhase.STOP -> "Stopped"
        OverlayPhase.IDLE -> "A2A 客户端就绪"
    }
    return OverlayHud(
        phase = phase,
        phaseLabel = if (phase == OverlayPhase.IDLE) "READY" else phase.name,
        elapsedLabel = elapsedLabel,
        providerLabel = "A2A · ${endpointLabel()}${if (speechReady) "" else " · TTS 未配置"}",
        requestPreview = (turn.firstOrNull { it is A2aClientItem.User } as? A2aClientItem.User)?.text?.overlayHudLine(REQUEST_MAX),
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
        picture = turn.firstNotNullOfOrNull { it.wireTaskId() }?.let(PictureBoard::forTask),
    )
}

/** The A2A task an item came from; the user's own message has none. */
internal fun A2aClientItem.wireTaskId(): String? = when (this) {
    is A2aClientItem.Status -> taskId
    is A2aClientItem.Tool -> taskId
    is A2aClientItem.Speech -> taskId
    is A2aClientItem.Display -> taskId
    is A2aClientItem.Question -> taskId
    is A2aClientItem.Terminal -> taskId
    is A2aClientItem.User, is A2aClientItem.Notice -> null
}

/** Items from the latest new message (late answers included); steering and in-task answers stay in the turn. */
internal fun A2aClientState.currentTurnItems(): List<A2aClientItem> {
    val start = items.indexOfLast { it is A2aClientItem.User && it.kind == A2aClientItem.User.Kind.MESSAGE }
    return if (start >= 0) items.drop(start) else items
}

internal fun A2aClientState.endpointLabel(): String =
    runCatching { URI(endpoint).let { "${it.host}:${it.port}" } }.getOrDefault(endpoint)

private const val TOOL_FAILED = "failed"
private const val TOOL_NAME_MAX = 40
private const val SPEECH_LINE_MAX = 60
private const val REQUEST_MAX = 96
private const val RESULT_MAX = 520
private const val HEADLINE_MAX = 80
