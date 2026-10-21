package cc.noharry.bothy.sample

import android.content.Context

// Where the overlay sends what is typed or tapped on its card in A2A client mode; moved out of DebugOverlayService.

/** Running with a pending question answers it; running otherwise steers; idle sends a new message. */
internal fun Context.submitClientPrompt(text: String) {
    val question = activeClientQuestion()
    when {
        question != null -> DebugA2aClientConsole.answer(this, question.questionId, text, DebugA2aInputSource.OVERLAY)
        DebugA2aClientConsole.state.running -> DebugA2aClientConsole.steer(this, text, DebugA2aInputSource.OVERLAY)
        else -> DebugA2aClientConsole.send(this, text, DebugA2aInputSource.OVERLAY)
    }
}

/** The A2A client's question the user can answer now, if any. */
internal fun activeClientQuestion(): DebugA2aClientItem.Question? =
    DebugA2aClientConsole.state.questionCards.lastOrNull { it.second == DebugQuestionAnswerMode.ACTIVE }?.first

internal fun Context.clientMode(): Boolean = DebugA2aClientConsole.isEnabled(this)

/** The stage shows the A2A view: client mode, or an inbound task holds it. */
internal fun Context.clientView(): Boolean = clientMode() || DebugOverlayFocus.external
