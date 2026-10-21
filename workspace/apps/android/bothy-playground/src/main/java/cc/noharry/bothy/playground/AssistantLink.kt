package cc.noharry.bothy.playground

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One step Bothy took: the tool it called and whether it is still running, done or failed. */
data class AssistantStep(val tool: String, val state: String)

/** One entry of the turn as it went: what 小博 thought, what it said while working, or a tool it called. */
sealed interface TurnEntry {
    data class Thought(val text: String) : TurnEntry

    data class Said(val text: String) : TurnEntry

    data class Called(val tool: String, val state: String) : TurnEntry
}

/** A turn line as Bothy sends it: `think|…`, `say|…` or `tool|name|state`. */
internal fun turnEntry(line: String): TurnEntry? {
    val kind = line.substringBefore('|')
    val rest = line.substringAfter('|', "")
    return when (kind) {
        "think" -> TurnEntry.Thought(rest)
        "say" -> TurnEntry.Said(rest)
        "tool" -> TurnEntry.Called(rest.substringBefore('|'), rest.substringAfter('|', "done"))
        else -> null
    }
}

/** The conversation as Bothy reports it while the head unit shows the assistant. */
data class AssistantView(
    val listening: Boolean = false,
    val heard: String = "",
    /** The voice line as Bothy's card would put it: the words being heard, else a prompt; [voiceTone] says which. */
    val voiceLine: String = "",
    val voiceTone: String = "",
    /** 小博 is talking. */
    val speaking: Boolean = false,
    val timeline: List<TurnEntry> = emptyList(),
    /** The finished reply's cards, picks and panel ([A2ui]). */
    val a2ui: A2ui = A2ui(),
    val request: String = "",
    val phase: String = "",
    val running: Boolean = false,
    val step: String = "",
    val reply: String = "",
    val steps: List<AssistantStep> = emptyList(),
    val updatedAt: Long = 0L,
    /** When 小博 last worked on the request (took it, ran, or changed its answer); the card's hold counts from here. */
    val workedAt: Long = 0L,
)

/**
 * The head unit's side of Bothy's assistant: while it is in front it takes the assistant over ([announce]), so Bothy hides
 * its floating card and sends the conversation here instead ([AssistantEventReceiver]); a suggestion tapped here is said
 * to Bothy ([say]), 小博 tapped wakes it ([wake]) and the X ends voice ([close]). Both apps hold [PERMISSION], a signature
 * permission, so no other app can listen in or take over.
 */
object Assistant {
    const val PERMISSION = "cc.noharry.bothy.permission.ASSISTANT_SURFACE"
    const val BOTHY_PACKAGE = "cc.noharry.bothy.sample"
    private const val ACTION_TAKE_OVER = "cc.noharry.bothy.sample.ASSISTANT_SURFACE"

    private val current = MutableStateFlow(AssistantView())
    val state: StateFlow<AssistantView> = current

    fun announce(context: Context, active: Boolean) {
        val intent = Intent(ACTION_TAKE_OVER).setPackage(BOTHY_PACKAGE)
            .putExtra("active", active)
            .putExtra("package", context.packageName)
        runCatching { context.sendBroadcast(intent, PERMISSION) }
    }

    fun say(context: Context, text: String) {
        runCatching { context.sendBroadcast(Intent(ACTION_TAKE_OVER).setPackage(BOTHY_PACKAGE).putExtra("text", text), PERMISSION) }
    }

    /** As the wake word: 小博 answers and listens. */
    fun wake(context: Context) = command(context, "wake")

    /** As Bothy's card X: listening ends and 小博 stops talking; what it is doing carries on, silently. */
    fun close(context: Context) = command(context, "close")

    private fun command(context: Context, name: String) {
        runCatching { context.sendBroadcast(Intent(ACTION_TAKE_OVER).setPackage(BOTHY_PACKAGE).putExtra("command", name), PERMISSION) }
    }

    /** A new report; when 小博 last worked on the request is kept across reports that only change what is heard. */
    fun received(next: AssistantView) {
        val before = current.value
        val worked = next.running || next.request != before.request || next.reply != before.reply || next.a2ui != before.a2ui
        current.value = next.copy(workedAt = if (worked) next.updatedAt else before.workedAt)
    }
}

/** Bothy's reports, sent only by an app holding [Assistant.PERMISSION]. */
class AssistantEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Assistant.received(
            AssistantView(
                listening = intent.getBooleanExtra("listening", false),
                heard = intent.getStringExtra("heard").orEmpty(),
                voiceLine = intent.getStringExtra("voiceLine").orEmpty(),
                voiceTone = intent.getStringExtra("voiceTone").orEmpty(),
                speaking = intent.getBooleanExtra("speaking", false),
                timeline = intent.getStringArrayListExtra("timeline").orEmpty().mapNotNull(::turnEntry),
                a2ui = a2uiFrom(intent.getStringExtra("a2ui")),
                request = intent.getStringExtra("request").orEmpty(),
                phase = intent.getStringExtra("phase").orEmpty(),
                running = intent.getBooleanExtra("running", false),
                step = intent.getStringExtra("step").orEmpty(),
                reply = intent.getStringExtra("reply").orEmpty(),
                steps = intent.getStringArrayListExtra("tools").orEmpty().map { line ->
                    AssistantStep(line.substringBefore('|'), line.substringAfter('|', "done"))
                },
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }
}

/**
 * Whether the assistant's card is open, as on the head unit: not while 小博 only listens (the words show in a slim bar),
 * but once a request is taken, while 小博 works on it and for a while after it answered; a follow-up heard meanwhile shows
 * in its header. Closed with its X, it stays closed for that request ([dismissed]).
 */
internal fun AssistantView.open(now: Long, dismissed: String?): Boolean =
    request.isNotBlank() && request != dismissed && (running || (workedAt > 0 && now - workedAt < holdMs()))

/** How long the card stays after 小博 answered: long enough to read a card, longer while a pick or panel waits. */
private fun AssistantView.holdMs(): Long = when {
    a2ui.asks -> PANEL_ASKS_HOLD_MS
    !a2ui.isEmpty -> PANEL_CARD_HOLD_MS
    else -> PANEL_HOLD_MS
}

/** Listening with the card closed: only the slim bar shows. */
internal fun AssistantView.hearing(now: Long, dismissed: String?): Boolean = listening && !open(now, dismissed)

internal const val PANEL_HOLD_MS = 20_000L
private const val PANEL_CARD_HOLD_MS = 45_000L
private const val PANEL_ASKS_HOLD_MS = 90_000L
