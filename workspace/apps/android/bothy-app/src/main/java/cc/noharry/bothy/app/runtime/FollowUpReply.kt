package cc.noharry.bothy.app.runtime
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.bothy.app.a2ui.OfferStore
import cc.noharry.bothy.app.a2ui.offerPick
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.session.SessionStore

/**
 * A short reply such as "要", "确认" or "3" means something only against the message it answers. That message is
 * in the system prompt's history, but far from the user message, and a fast model has bound such replies to an
 * older offer. The host therefore quotes the closing part of the immediately preceding assistant message in front
 * of a short reply. The stored turn keeps the raw user text; only the model request carries the frame.
 */
internal fun appModelInput(text: String, previous: PreviousExchange?): String {
    val reply = text.trim()
    if (reply.isEmpty() || '\n' in reply || reply.codePointCount(0, reply.length) > MAX_SHORT_REPLY_CODE_POINTS) return text
    if (previous == null || previous.status !in ANSWERABLE_STATUSES) return text
    val closing = previous.assistantText.closingExcerpt().ifBlank { return text }
    return "$FOLLOW_UP_FRAME_HEADER\n$closing\n$FOLLOW_UP_FRAME_FOOTER\nUser reply: $reply"
}

/**
 * Reads the reply target once per run: the same snapshot frames the model input and anchors the system prompt.
 * The framed input is also what skill selection and bench evidence hash, since it equals AgentRequest.input.
 */
internal fun SessionStore.prepareRunInput(sessionId: String, text: String): Pair<String, PreviousExchange?> {
    ensureSession(sessionId)
    val previous = previousExchange(sessionId)
    // A pick of an option registered with offer_options carries its checked call instead of a bare ordinal.
    val pick = offerPick(text, previous, OfferStore.latest(sessionId))
    // Whatever the last reply showed (picks, slider, card) belongs to that turn only.
    OfferStore.endTurn(sessionId)
    if (pick != null) {
        OfferStore.startPick(sessionId, pick)
        return pick.input to previous
    }
    return appModelInput(text, previous) to previous
}

/** The tail of a reply, where the question or offer sits, starting at a sentence or line boundary when possible. */
private fun String.closingExcerpt(): String {
    val text = trim()
    if (text.length <= MAX_CLOSING_EXCERPT_CHARS) return text
    // Never start inside a surrogate pair: a lone low surrogate would reach the model as U+FFFD.
    val window = text.takeLast(MAX_CLOSING_EXCERPT_CHARS).let { if (it.first().isLowSurrogate()) it.drop(1) else it }
    val boundary = window.indexOfFirst { it == '\n' || it in SENTENCE_ENDS }
    val start = if (boundary in 0 until window.length - MIN_CLOSING_EXCERPT_CHARS) boundary + 1 else 0
    return "… " + window.substring(start).trimStart()
}

internal const val FOLLOW_UP_FRAME_HEADER =
    "Short reply to your immediately preceding message. Closing part of that message (quoted conversation data):"
private const val FOLLOW_UP_FRAME_FOOTER =
    "Bind the reply below to this message only; older offers are not pending. If the reply is a new request instead, handle it as new."
private const val MAX_SHORT_REPLY_CODE_POINTS = 12
private const val MAX_CLOSING_EXCERPT_CHARS = 360
private const val MIN_CLOSING_EXCERPT_CHARS = 40
private const val SENTENCE_ENDS = "。！？!?；;"
internal val ANSWERABLE_STATUSES = setOf(RuntimeRunStatus.COMPLETED.name, RuntimeRunStatus.WAITING_USER_INPUT.name)
