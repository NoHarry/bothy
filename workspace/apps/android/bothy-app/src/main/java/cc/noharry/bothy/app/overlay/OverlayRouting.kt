package cc.noharry.bothy.app.overlay
import android.content.Context
import cc.noharry.bothy.app.a2a.A2aClientConsole
import cc.noharry.bothy.app.a2a.A2aClientItem
import cc.noharry.bothy.app.a2a.A2aInputSource
import cc.noharry.bothy.app.a2ui.QuestionAnswerMode
import cc.noharry.bothy.app.a2ui.questionCards
import cc.noharry.bothy.app.console.OverlayFocus

// Where the overlay sends what is typed or tapped on its card in A2A client mode; moved out of OverlayService.

/** Running with a pending question answers it; running otherwise steers; idle sends a new message. */
internal fun Context.submitClientPrompt(text: String) {
    val question = activeClientQuestion()
    when {
        question != null -> A2aClientConsole.answer(this, question.questionId, text, A2aInputSource.OVERLAY)
        A2aClientConsole.state.running -> A2aClientConsole.steer(this, text, A2aInputSource.OVERLAY)
        else -> A2aClientConsole.send(this, text, A2aInputSource.OVERLAY)
    }
}

/** The A2A client's question the user can answer now, if any. */
internal fun activeClientQuestion(): A2aClientItem.Question? =
    A2aClientConsole.state.questionCards.lastOrNull { it.second == QuestionAnswerMode.ACTIVE }?.first

internal fun Context.clientMode(): Boolean = A2aClientConsole.isEnabled(this)

/** The stage shows the A2A view: client mode, or an inbound task holds it. */
internal fun Context.clientView(): Boolean = clientMode() || OverlayFocus.external
