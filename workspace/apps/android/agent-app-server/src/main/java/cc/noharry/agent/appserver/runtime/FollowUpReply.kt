package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.InternalAppServerApi
import cc.noharry.agent.appserver.a2ui.OfferStore
import cc.noharry.agent.appserver.a2ui.offerPick
import cc.noharry.agent.appserver.session.SessionStore
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus

/**
 * A short reply such as "要", "确认" or "3" means something only against the message it answers. That message is
 * in the system prompt's history, but far from the user message, and a fast model has bound such replies to an
 * older offer. The host therefore quotes the closing part of the immediately preceding assistant message in front
 * of a short reply. The stored turn keeps the raw user text; only the model request carries the frame.
 */
@InternalAppServerApi
fun appModelInput(text: String, previous: PreviousExchange?): String {
    val reply = text.trim()
    if (reply.isEmpty() || '\n' in reply || !reply.isShortReply()) return text
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

/**
 * A reply as short as 「要」「3」「好的，就第二个」 or "yes, the second one": a few characters for a Chinese persona, whatever
 * the script (as it always was); for another, a few characters of Chinese (or Japanese) or a few words.
 */
private fun String.isShortReply(): Boolean = when {
    AppHost.words.isChinese || any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } ->
        codePointCount(0, length) <= MAX_SHORT_REPLY_CODE_POINTS
    else -> split(WHITESPACE).count { it.isNotEmpty() } <= MAX_SHORT_REPLY_WORDS && length <= MAX_SHORT_REPLY_CHARS
}

/** The tail of a reply, where the question or offer sits, starting at a sentence or line boundary when possible. */
private fun String.closingExcerpt(): String {
    val text = trim()
    if (text.length <= MAX_CLOSING_EXCERPT_CHARS) return text
    // Never start inside a surrogate pair: a lone low surrogate would reach the model as U+FFFD.
    val window = text.takeLast(MAX_CLOSING_EXCERPT_CHARS).let { if (it.first().isLowSurrogate()) it.drop(1) else it }
    // For a persona that is not Chinese, a full stop before a space ends a sentence too (not in 3.5 or example.com); a
    // Chinese reply's 「1. 东广场」 keeps its list whole.
    val fullStops = !AppHost.words.isChinese
    val boundary = window.indices.firstOrNull { i ->
        val char = window[i]
        char == '\n' || char in SENTENCE_ENDS || (fullStops && char == '.' && window.getOrNull(i + 1)?.isWhitespace() == true)
    } ?: -1
    val start = if (boundary in 0 until window.length - MIN_CLOSING_EXCERPT_CHARS) boundary + 1 else 0
    return "… " + window.substring(start).trimStart()
}

@InternalAppServerApi
const val FOLLOW_UP_FRAME_HEADER =
    "Short reply to your immediately preceding message. Closing part of that message (quoted conversation data):"
private const val FOLLOW_UP_FRAME_FOOTER =
    "Bind the reply below to this message only; older offers are not pending. If the reply is a new request instead, handle it as new."
private const val MAX_SHORT_REPLY_CODE_POINTS = 12
private const val MAX_SHORT_REPLY_WORDS = 6
private const val MAX_SHORT_REPLY_CHARS = 40
private val WHITESPACE = Regex("\\s+")
private const val MAX_CLOSING_EXCERPT_CHARS = 360
private const val MIN_CLOSING_EXCERPT_CHARS = 40
private const val SENTENCE_ENDS = "。！？!?；;"
internal val ANSWERABLE_STATUSES = setOf(RuntimeRunStatus.COMPLETED.name, RuntimeRunStatus.WAITING_USER_INPUT.name)
