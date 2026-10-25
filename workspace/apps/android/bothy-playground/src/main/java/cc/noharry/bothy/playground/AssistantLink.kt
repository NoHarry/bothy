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
            .putExtra("realNav", HeadUnitModes.realNav)
        runCatching { context.sendBroadcast(intent, PERMISSION) }
    }

    fun say(context: Context, text: String) {
        runCatching { context.sendBroadcast(Intent(ACTION_TAKE_OVER).setPackage(BOTHY_PACKAGE).putExtra("text", text), PERMISSION) }
    }

    /** As the wake word: 小博 answers and listens. */
    fun wake(context: Context) = command(context, "wake")

    /** As Bothy's card X: listening ends and 小博 stops talking; what it is doing carries on, silently. */
    fun close(context: Context) = command(context, "close")

    /** The servers here may offer other tools than when Bothy last listed them (this app started): it lists them again. */
    fun toolsChanged(context: Context) = command(context, "tools_changed")

    /**
     * Switched between the simulated car and real navigation ([HeadUnitModes]): Bothy lists the tools again and starts a new
     * conversation (owner 2026-10-25: 「每次切完要换个新session,避免之前的记忆影响」).
     */
    fun modeChanged(context: Context) = command(context, "mode_changed")

    /** Each command says the mode too: in real navigation Bothy offers no car control ([HeadUnitModes]). */
    private fun command(context: Context, name: String) {
        val intent = Intent(ACTION_TAKE_OVER).setPackage(BOTHY_PACKAGE).putExtra("command", name).putExtra("realNav", HeadUnitModes.realNav)
        runCatching { context.sendBroadcast(intent, PERMISSION) }
    }

    /** A new report. */
    fun received(next: AssistantView) {
        current.value = next.after(current.value)
    }
}

/**
 * This report after [before], with when 小博 last worked on the request: kept across reports that only change what is
 * heard. The first since the head unit started carries Bothy's last turn, done before: it opens no card unless 小博 is
 * still on it.
 */
internal fun AssistantView.after(before: AssistantView): AssistantView {
    val changed = request != before.request || reply != before.reply || a2ui != before.a2ui
    val worked = running || (before.updatedAt > 0L && changed)
    return copy(workedAt = if (worked) updatedAt else before.workedAt)
}

/**
 * The loudness Bothy reports a few times a second while voice is on: the user's ([heard]) and 小博's ([spoken]), each
 * 0..1, for the bar's light ([VoiceGlow]). Read every frame, so plain fields, not state.
 */
internal object VoiceLevels {
    @Volatile var heard = 0f
        private set
    @Volatile var spoken = 0f
        private set
    @Volatile var at = 0L
        private set

    fun received(heard: Float, spoken: Float) {
        this.heard = heard
        this.spoken = spoken
        at = android.os.SystemClock.elapsedRealtime()
    }

    /** The louder voice now; 0 once the reports stop (Bothy older than this, or voice over). */
    fun now(): Float = if (android.os.SystemClock.elapsedRealtime() - at < STALE_MS) maxOf(heard, spoken) else 0f

    private const val STALE_MS = 400L
}

private const val ACTION_LEVEL = "cc.noharry.bothy.ASSISTANT_LEVEL"

/** Bothy's reports, sent only by an app holding [Assistant.PERMISSION]. */
class AssistantEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_LEVEL) {
            VoiceLevels.received(intent.getFloatExtra("heard", 0f), intent.getFloatExtra("spoken", 0f))
            return
        }
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
 * in its header. Closed with its X, it stays closed for that request ([dismissed]); touched, it stays open until closed
 * ([held], owner 2026-10-25, as Bothy's own card).
 */
internal fun AssistantView.open(now: Long, dismissed: String?, held: String? = null): Boolean =
    request.isNotBlank() && request != dismissed && (running || request == held || (workedAt > 0 && now - workedAt < holdMs()))

/** When the open card folds by itself, for the countdown on its X ([CloseCountdown]); 0 while 小博 works or it is [held]. */
internal fun AssistantView.closesAt(held: String?): Long = if (running || request == held || workedAt <= 0L) 0L else workedAt + holdMs()

/** How long the card stays after 小博 answered: long enough to read a card, longer while a pick or panel waits. */
internal fun AssistantView.holdMs(): Long = when {
    a2ui.asks -> PANEL_ASKS_HOLD_MS
    !a2ui.isEmpty -> PANEL_CARD_HOLD_MS
    else -> PANEL_HOLD_MS
}

/**
 * Whether the card goes as voice ends ([before] listening, this not; owner 2026-10-25: 「退出的时候卡片也关闭」): 「退出」,
 * the X or 小博 tapped, or an idle end. Not while 小博 still works on the request, nor once the card was touched ([held]).
 */
internal fun AssistantView.foldsWithVoice(before: Boolean, held: String?): Boolean =
    before && !listening && !running && request.isNotBlank() && request != held

/** Listening with the card closed: only the slim bar shows. */
internal fun AssistantView.hearing(now: Long, dismissed: String?, held: String? = null): Boolean = listening && !open(now, dismissed, held)

internal const val PANEL_HOLD_MS = 20_000L
private const val PANEL_CARD_HOLD_MS = 45_000L
private const val PANEL_ASKS_HOLD_MS = 90_000L
