package cc.noharry.bothy.app.overlay
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import cc.noharry.bothy.voice.VoiceSessionState
import cc.noharry.bothy.app.a2a.currentTurnItems
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.mcp.McpToolCatalogs
import cc.noharry.bothy.app.runtime.RuntimeSession
import cc.noharry.bothy.app.session.ConversationItem
import cc.noharry.bothy.app.session.ToolCallStatus
import cc.noharry.bothy.app.session.TranscriptRole
import cc.noharry.bothy.app.session.UiState
import cc.noharry.bothy.app.tools.PlaygroundMode
import cc.noharry.bothy.app.voice.VoiceController
import cc.noharry.bothy.app.voice.WakeSource
import cc.noharry.bothy.app.voice.tts.TtsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Another app that shows the assistant in its own screen, as a car's head unit does (Bothy Playground's 虚拟座舱): while
 * it is in front it takes over, so the floating card and bubble stay hidden and the conversation goes to it instead (what
 * is heard, the request, the thinking and the steps, the reply); it can also say something for the user, wake 小博 (its
 * avatar tapped) and close voice (its X, as the card's X does). Both apps hold [PERMISSION], a
 * signature permission, so only an app signed like this one can take over or listen. A takeover lapses unless renewed
 * within [TAKEOVER_LEASE_MS], so a surface that died never leaves the assistant hidden.
 */
internal object AssistantSurface {
    const val PERMISSION = "cc.noharry.bothy.permission.ASSISTANT_SURFACE"
    const val ACTION_TAKE_OVER = "cc.noharry.bothy.ASSISTANT_SURFACE"
    const val ACTION_EVENT = "cc.noharry.bothy.ASSISTANT_EVENT"
    /** The loudness now, for the surface's voice light: `heard` (the user) and `spoken` (小博), each 0..1. */
    const val ACTION_LEVEL = "cc.noharry.bothy.ASSISTANT_LEVEL"
    const val EXTRA_ACTIVE = "active"
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_TEXT = "text"
    const val EXTRA_COMMAND = "command"
    /** The Playground drives in real navigation ([PlaygroundMode]); sent as it takes over and with mode_changed. */
    const val EXTRA_REAL_NAV = "realNav"
    const val COMMAND_WAKE = "wake"
    const val COMMAND_CLOSE = "close"
    /** Bothy Playground (re)started: its servers may offer other tools than when they were last listed. */
    const val COMMAND_TOOLS_CHANGED = "tools_changed"
    /** Bothy Playground switched between its simulated car and real navigation: other tools, and a new conversation. */
    const val COMMAND_MODE_CHANGED = "mode_changed"
    /** The Playground's servers, as its connectors name them ([DebugMcpConnectors]). */
    val PLAYGROUND_SERVERS = listOf("cabin", "home")

    private val main = Handler(Looper.getMainLooper())
    private val surface = MutableStateFlow<String?>(null)
    private var hud: OverlayHud? = null
    private var timeline: List<String> = emptyList()
    private var request: String? = null
    private var voice = VoiceSessionState()
    private var sent: Map<String, Any>? = null
    private val lapse = Runnable { surface.value = null }
    private var appContext: Context? = null
    private val mic = MicLevelPlayhead()
    private var levelsOn = false
    /** When the speaker last dismissed voice (「退出」「退下吧」): the surface closes its card with it. */
    private var dismissedAt = 0L

    /**
     * The loudness, [LEVEL_MS] apart, while a surface has the assistant and someone speaks (owner 2026-10-25: 「底部光效要
     * 跟随人说话或者tts播报上下跳动更明显」): the user's while voice listens, 小博's while it talks, as the card's own light
     * follows them ([VoiceAura]). One last zero when both stop.
     */
    private val levels = object : Runnable {
        override fun run() {
            val context = appContext ?: return
            val pkg = surface.value
            val on = pkg != null && (VoiceController.listening || TtsActivity.active.value)
            if (pkg != null && (on || levelsOn)) {
                val heard = if (on) auraHeardLevel(mic, System.nanoTime()) else 0f
                val spoken = if (on) auraSpokenLevel() else 0f
                runCatching {
                    context.sendBroadcast(Intent(ACTION_LEVEL).setPackage(pkg).putExtra("heard", heard).putExtra("spoken", spoken), PERMISSION)
                }
            }
            levelsOn = on
            if (on) main.postDelayed(this, LEVEL_MS)
        }
    }

    private fun levelsChanged(context: Context) {
        appContext = context.applicationContext
        main.removeCallbacks(levels)
        main.post(levels)
    }

    /** The package now showing the assistant, or null while the floating card does. */
    val target: StateFlow<String?> = surface

    val active: Boolean get() = surface.value != null

    /** While [scope] lives: [onTaken] hears whether a surface has the assistant, and the surface hears the voice session. */
    fun follow(context: Context, scope: CoroutineScope, onTaken: (Boolean) -> Unit) {
        scope.launch { target.collect { onTaken(it != null); levelsChanged(context) } }
        scope.launch { VoiceController.state.collect { voiceChanged(context, it) } }
        scope.launch { VoiceController.active.collect { levelsChanged(context) } }
        // 小博 speaking, with or without a voice session: the surface holds its card's countdown until it is done.
        scope.launch { TtsActivity.active.collect { levelsChanged(context); send(context) } }
        scope.launch { VoiceController.dismissals.collect { dismissedAt = System.currentTimeMillis(); send(context) } }
    }

    /** [pkg] shows the assistant from now (renewing the lease), or nobody does when null; it gets the state at once. */
    fun takeOver(context: Context, pkg: String?) {
        main.removeCallbacks(lapse)
        if (pkg != null) main.postDelayed(lapse, TAKEOVER_LEASE_MS)
        // The reports come from the overlay's view of the run ([runChanged]): a surface needs it running, though hidden;
        // with 语音唤醒 off nothing else had started it, and the head unit showed no card.
        if (pkg != null) OverlayControl.ensureRunning(context)
        if (surface.value != pkg) sent = null
        surface.value = pkg
        send(context)
    }

    /**
     * The run changed: the card's [next], with the turn's lines and the whole request of [run] (none for an A2A client's).
     * The head unit shows the model's thinking whenever there is any, as a car's assistant shows how it thinks; 显示思考过程
     * is about Bothy's own card.
     */
    fun runChanged(context: Context, next: OverlayHud, run: UiState?) =
        hudChanged(context, next, run?.surfaceTimeline(showThinking = true).orEmpty(), run?.currentRequest?.text)

    /** The run as the card shows it, with this turn's [turn] lines and the whole [fullRequest] (the card's is cut short). */
    fun hudChanged(context: Context, next: OverlayHud, turn: List<String>, fullRequest: String?) {
        hud = next
        timeline = turn
        request = fullRequest
        send(context)
    }

    fun voiceChanged(context: Context, state: VoiceSessionState) {
        voice = state
        send(context)
    }

    private fun send(context: Context) {
        val pkg = surface.value ?: return
        val payload = assistantPayload(hud, voice, VoiceController.listening, timeline, request) +
            mapOf("speaking" to (voice.assistantSpeaking || TtsActivity.active.value), "dismissedAt" to dismissedAt)
        if (payload == sent) return
        sent = payload
        val intent = Intent(ACTION_EVENT).setPackage(pkg)
        payload.forEach { (key, value) ->
            when (value) {
                is String -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is List<*> -> intent.putStringArrayListExtra(key, ArrayList(value.map { it.toString() }))
            }
        }
        context.sendBroadcast(intent, PERMISSION)
    }

    private const val TAKEOVER_LEASE_MS = 25_000L
    private const val LEVEL_MS = 66L
}

/**
 * The conversation as the surface shows it: tools as `name|running`, `name|done` or `name|error`; the voice line as the
 * card would say it (the words being heard, or a prompt) with its tone; the turn's [timeline] ([surfaceTimeline]).
 */
internal fun assistantPayload(
    hud: OverlayHud?,
    voice: VoiceSessionState,
    listening: Boolean,
    timeline: List<String> = emptyList(),
    request: String? = null,
): Map<String, Any> = mapOf(
    "listening" to listening,
    "heard" to voice.liveText,
    "voiceLine" to (if (listening) overlayVoiceLine(voice, null, System.currentTimeMillis()).text else ""),
    "voiceTone" to (if (listening) overlayVoiceLine(voice, null, System.currentTimeMillis()).tone.name else ""),
    "speaking" to voice.assistantSpeaking,
    "timeline" to timeline,
    "request" to (request?.trim()?.takeIf { it.isNotEmpty() && hud?.requestPreview != null } ?: hud?.requestPreview.orEmpty()),
    "phase" to hud?.phaseLabel.orEmpty(),
    "running" to (hud?.isRunning == true),
    "step" to hud?.currentStep.orEmpty(),
    "reply" to hud?.resultText.orEmpty(),
    SURFACE_A2UI to (hud?.takeUnless { it.isRunning }?.surfaceA2ui() ?: ""),
    "tools" to hud?.toolLines.orEmpty().takeLast(MAX_TOOLS).map { "${it.name}|${if (it.running) "running" else if (it.isError) "error" else "done"}" },
)

/**
 * This turn as the surface lists it, in order: what the model thought (`think|…`, only with 显示思考过程 on), what it said
 * while working (`say|…`) and each tool (`tool|name|running`, `done` or `error`).
 */
internal fun UiState.surfaceTimeline(showThinking: Boolean): List<String> =
    items.currentTurnItems().mapNotNull { item ->
        when (item) {
            is ConversationItem.MessageItem -> item.content.trim().takeIf { it.isNotEmpty() && !item.final }?.let { text ->
                when (item.role) {
                    TranscriptRole.THINKING -> if (showThinking) "think|${text.takeLast(MAX_THOUGHT)}" else null
                    TranscriptRole.ASSISTANT -> "say|${text.replace(TTS_PROGRESS_PREFIX, "").trim().takeLast(MAX_THOUGHT)}"
                    else -> null
                }
            }
            is ConversationItem.ToolCallItem -> {
                val state = when (item.status) {
                    ToolCallStatus.RUNNING -> "running"
                    ToolCallStatus.FAILED -> "error"
                    else -> "done"
                }
                "tool|${item.toolName.substringAfterLast('.').substringAfterLast("__")}|$state"
            }
            else -> null
        }
    }.takeLast(MAX_TIMELINE)

private const val MAX_TOOLS = 6
private const val MAX_TIMELINE = 14
private const val MAX_THOUGHT = 360

/** A surface taking over or giving back the assistant, or saying something for the user ([AssistantSurface]). */
class AssistantSurfaceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AssistantSurface.ACTION_TAKE_OVER) return
        if (intent.hasExtra(AssistantSurface.EXTRA_REAL_NAV)) {
            PlaygroundMode.report(context, intent.getBooleanExtra(AssistantSurface.EXTRA_REAL_NAV, false))
        }
        when (intent.getStringExtra(AssistantSurface.EXTRA_COMMAND)) {
            // As the wake word would: 小博 answers and listens (or listens anew).
            AssistantSurface.COMMAND_WAKE -> {
                OverlayControl.waker?.invoke(WakeSource.SURFACE) ?: startWokenVoice(context.applicationContext, WakeSource.SURFACE)
                return
            }
            // As the card's X: voice ends and 小博 stops talking.
            AssistantSurface.COMMAND_CLOSE -> {
                VoiceController.closedByHand()
                return
            }
            // The next run sees what the Playground's servers offer now, not what they offered at start.
            AssistantSurface.COMMAND_TOOLS_CHANGED -> {
                AssistantSurface.PLAYGROUND_SERVERS.forEach { McpToolCatalogs.onServerChanged(context.applicationContext, it) }
                return
            }
            // And in a new session: what was said about the simulated car does not follow into real driving, nor back.
            AssistantSurface.COMMAND_MODE_CHANGED -> {
                AssistantSurface.PLAYGROUND_SERVERS.forEach { McpToolCatalogs.onServerChanged(context.applicationContext, it) }
                RuntimeSession.newSession(context.applicationContext)
                return
            }
            null -> Unit
            // A command this version does not know is not a hand-over.
            else -> return
        }
        intent.getStringExtra(AssistantSurface.EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            RuntimeSession.submitText(context.applicationContext, it)
            return
        }
        val pkg = intent.getStringExtra(AssistantSurface.EXTRA_PACKAGE)?.takeIf { intent.getBooleanExtra(AssistantSurface.EXTRA_ACTIVE, false) }
        AssistantSurface.takeOver(context.applicationContext, pkg)
    }
}
