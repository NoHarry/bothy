package cc.noharry.bothy.app.overlay

import cc.noharry.agent.appserver.protocol.A2aClientAnswerParams
import cc.noharry.agent.appserver.protocol.A2aClientItem
import cc.noharry.agent.appserver.protocol.A2aClientState
import cc.noharry.agent.appserver.protocol.A2aClientTextParams
import cc.noharry.agent.appserver.protocol.A2aInputSource
import cc.noharry.agent.appserver.protocol.FeatureRequests
import cc.noharry.agent.appserver.protocol.QuestionAnswerMode

// Where the overlay sends what is typed or tapped on its card in A2A client mode; moved out of OverlayService.

/** Running with a pending question answers it; running otherwise steers; idle sends a new message. */
internal fun submitClientPrompt(text: String) {
    val question = activeClientQuestion()
    when {
        question != null ->
            OverlayConnection.send(FeatureRequests.A2A_CLIENT_ANSWER, A2aClientAnswerParams(question.questionId, text, A2aInputSource.OVERLAY))
        clientState().running -> OverlayConnection.send(FeatureRequests.A2A_CLIENT_STEER, A2aClientTextParams(text, A2aInputSource.OVERLAY))
        else -> OverlayConnection.send(FeatureRequests.A2A_CLIENT_SEND, A2aClientTextParams(text, A2aInputSource.OVERLAY))
    }
}

/** Client mode as the overlay last heard it from the server. */
internal fun clientState(): A2aClientState = OverlayConnection.state.value.a2aClient

/** The A2A client's question the user can answer now, if any. */
internal fun activeClientQuestion(): A2aClientItem.Question? =
    clientState().questionCards.lastOrNull { it.second == QuestionAnswerMode.ACTIVE }?.first

internal fun clientMode(): Boolean = clientState().enabled

/** The stage shows the A2A view: client mode, or an inbound task holds it. */
internal fun clientView(): Boolean = clientMode() || clientState().inboundFocus
