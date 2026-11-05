
package cc.noharry.bothy.protocol.view

import cc.noharry.agent.appserver.protocol.A2aClientItem
import cc.noharry.agent.appserver.protocol.A2aClientState
import cc.noharry.agent.appserver.protocol.A2aTaskStatus
import cc.noharry.agent.appserver.protocol.QuestionAnswerMode
import cc.noharry.agent.appserver.protocol.view.A2aHudTexts
import cc.noharry.agent.appserver.protocol.view.HUD_MAX_PLAN_ROWS
import cc.noharry.agent.appserver.protocol.view.HUD_MAX_TOOL_LINES
import cc.noharry.agent.appserver.protocol.view.Offer
import cc.noharry.agent.appserver.protocol.view.OverlayBoardRow
import cc.noharry.agent.appserver.protocol.view.OverlayPhase
import cc.noharry.agent.appserver.protocol.view.OverlayPicture
import cc.noharry.agent.appserver.protocol.view.OverlayPlanStatus
import cc.noharry.agent.appserver.protocol.view.OverlayToolLine
import cc.noharry.agent.appserver.protocol.view.currentTurnItems
import cc.noharry.agent.appserver.protocol.view.endpointLabel
import cc.noharry.agent.appserver.protocol.view.formatWorkedDuration
import cc.noharry.agent.appserver.protocol.view.line
import cc.noharry.agent.appserver.protocol.view.overlayHudLine
import cc.noharry.agent.appserver.protocol.view.overlayHudMultiline
import cc.noharry.agent.appserver.protocol.view.pickableDisplay
import cc.noharry.agent.appserver.protocol.view.questionCards
import cc.noharry.agent.appserver.protocol.view.wireTaskId

/**
 * Overlay stage for client mode, built only from wire items of the current (or latest) task. [texts] words what only
 * the card shows (the empty speech card, a question row, the missing TTS) in the screen's language; without it those
 * go without words of their own.
 */
fun A2aClientState.overlayHud(
    nowEpochMillis: Long = System.currentTimeMillis(),
    texts: A2aHudTexts? = null,
    /** Latest offers by session ([cc.noharry.bothy.protocol.client.ServerState.offers]). */
    offers: Map<String, Offer> = emptyMap(),
    /** Pictures drawn for A2A tasks, by task ID. */
    pictures: Map<String, OverlayPicture> = emptyMap(),
): OverlayHud {
    val turn = currentTurnItems()
    val terminal = turn.lastOrNull { it is A2aClientItem.Terminal } as? A2aClientItem.Terminal
    val live = running || mirrors.isNotEmpty()
    val phase = when {
        live -> OverlayPhase.LIVE
        terminal == null -> OverlayPhase.IDLE
        terminal.state == A2aTaskStatus.FAILED -> OverlayPhase.FAIL
        terminal.state == A2aTaskStatus.CANCELLED -> OverlayPhase.STOP
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
        val row = texts?.question(it.text) ?: it.text
        OverlayBoardRow(row.overlayHudLine(SPEECH_LINE_MAX), OverlayPlanStatus.ACTIVE)
    }
    val display = (turn.lastOrNull { it is A2aClientItem.Display } as? A2aClientItem.Display)?.text
    val lastSpeech = (turn.lastOrNull { it is A2aClientItem.Speech } as? A2aClientItem.Speech)?.text
    val notice = (turn.lastOrNull { it is A2aClientItem.Notice } as? A2aClientItem.Notice)?.text
    // Picks come only from the ended task's display text; a tap goes out like any typed client message.
    val pickable = pickableDisplay(offers)
    val choiceReply = pickable?.reply
    val headline = when (phase) {
        OverlayPhase.LIVE -> pendingQuestion?.let { "等待回答：${it.text}" } ?: status ?: "A2A 任务执行中"
        OverlayPhase.DONE -> if (terminal?.state == A2aTaskStatus.INPUT_REQUIRED) "等待你的回复" else "Completed"
        OverlayPhase.FAIL -> "Failed"
        OverlayPhase.STOP -> "Stopped"
        OverlayPhase.IDLE -> "A2A 客户端就绪"
    }
    return OverlayHud(
        phase = phase,
        phaseLabel = if (phase == OverlayPhase.IDLE) "READY" else phase.name,
        elapsedLabel = elapsedLabel,
        providerLabel = texts?.takeUnless { speechReady }?.providerWithoutSpeech(endpointLabel())
            ?: "A2A · ${endpointLabel()}",
        requestPreview = (turn.firstOrNull { it is A2aClientItem.User } as? A2aClientItem.User)?.text?.overlayHudLine(REQUEST_MAX),
        progressDone = 0,
        progressTotal = 0,
        progressLabel = when {
            live -> "In progress"
            else -> lastOutcome?.line() ?: "Ready"
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
        planEmptyText = texts?.planEmpty,
        choices = choiceReply?.choices.orEmpty(),
        choiceIcons = pickable?.icons.orEmpty(),
        cards = pickable?.cards.orEmpty(),
        controls = pickable?.controls.orEmpty(),
        // The wire carries text only; a picture this car's image tool draws for the task is found by task ID.
        picture = turn.firstNotNullOfOrNull { it.wireTaskId() }?.let(pictures::get),
    )
}

private const val TOOL_FAILED = "failed"

private const val TOOL_NAME_MAX = 40

private const val SPEECH_LINE_MAX = 60

private const val REQUEST_MAX = 96

private const val RESULT_MAX = 520

private const val HEADLINE_MAX = 80
