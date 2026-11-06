
package cc.noharry.agent.appserver.protocol.view
import java.net.URI
import cc.noharry.agent.appserver.protocol.A2aClientItem
import cc.noharry.agent.appserver.protocol.A2aClientState
import cc.noharry.agent.appserver.protocol.A2aTaskStatus

/** The A2A task an item came from; the user's own message has none. */
fun A2aClientItem.wireTaskId(): String? = when (this) {
    is A2aClientItem.Status -> taskId
    is A2aClientItem.Tool -> taskId
    is A2aClientItem.Speech -> taskId
    is A2aClientItem.Display -> taskId
    is A2aClientItem.Question -> taskId
    is A2aClientItem.Terminal -> taskId
    is A2aClientItem.User, is A2aClientItem.Notice -> null
}

/** Items from the latest new message (late answers included); steering and in-task answers stay in the turn. */
fun A2aClientState.currentTurnItems(): List<A2aClientItem> {
    val start = items.indexOfLast { it is A2aClientItem.User && it.kind == A2aClientItem.User.Kind.MESSAGE }
    return if (start >= 0) items.drop(start) else items
}

fun A2aClientState.endpointLabel(): String =
    runCatching { URI(endpoint).let { "${it.host}:${it.port}" } }.getOrDefault(endpoint)

/**
 * Client mode's pickable reply, the overlay card's rule: the current task has ended (not cancelled), nothing runs
 * or is mirrored, and its display text lists picks.
 */
fun A2aClientState.pickableDisplay(offers: Map<String, Offer>): PickableReply? {
    if (running || mirrors.isNotEmpty()) return null
    val turn = currentTurnItems()
    val terminal = turn.lastOrNull { it is A2aClientItem.Terminal } as? A2aClientItem.Terminal ?: return null
    if (terminal.state == A2aTaskStatus.CANCELLED) return null
    val display = turn.lastOrNull { it is A2aClientItem.Display } as? A2aClientItem.Display ?: return null
    // This device's own server registered the offer under the A2A context's session; a remote server's never is.
    val offer = contextId?.let { offers["a2a-$it"] }
    return appPickableReply(display.id, display.text.overlayHudMultiline(Int.MAX_VALUE), offer)
}

/** What only the A2A card words itself, in the screen's language. */
interface A2aHudTexts {
    /** A question waiting for an answer, as a row. */
    fun question(text: String): String

    /** The provider line when the device cannot speak the answers. */
    fun providerWithoutSpeech(endpoint: String): String

    /** The empty speech card. */
    val planEmpty: String
}
