package cc.noharry.bothy.sample

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import cc.noharry.agent.voice.VoiceSessionState
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
internal object DebugAssistantSurface {
    const val PERMISSION = "cc.noharry.bothy.permission.ASSISTANT_SURFACE"
    const val ACTION_TAKE_OVER = "cc.noharry.bothy.sample.ASSISTANT_SURFACE"
    const val ACTION_EVENT = "cc.noharry.bothy.ASSISTANT_EVENT"
    const val EXTRA_ACTIVE = "active"
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_TEXT = "text"
    const val EXTRA_COMMAND = "command"
    const val COMMAND_WAKE = "wake"
    const val COMMAND_CLOSE = "close"

    private val main = Handler(Looper.getMainLooper())
    private val surface = MutableStateFlow<String?>(null)
    private var hud: DebugOverlayHud? = null
    private var timeline: List<String> = emptyList()
    private var request: String? = null
    private var voice = VoiceSessionState()
    private var sent: Map<String, Any>? = null
    private val lapse = Runnable { surface.value = null }

    /** The package now showing the assistant, or null while the floating card does. */
    val target: StateFlow<String?> = surface

    val active: Boolean get() = surface.value != null

    /** While [scope] lives: [onTaken] hears whether a surface has the assistant, and the surface hears the voice session. */
    fun follow(context: Context, scope: CoroutineScope, onTaken: (Boolean) -> Unit) {
        scope.launch { target.collect { onTaken(it != null) } }
        scope.launch { DebugVoiceController.state.collect { voiceChanged(context, it) } }
    }

    /** [pkg] shows the assistant from now (renewing the lease), or nobody does when null; it gets the state at once. */
    fun takeOver(context: Context, pkg: String?) {
        main.removeCallbacks(lapse)
        if (pkg != null) main.postDelayed(lapse, TAKEOVER_LEASE_MS)
        if (surface.value != pkg) sent = null
        surface.value = pkg
        send(context)
    }

    /**
     * The run changed: the card's [next], with the turn's lines and the whole request of [run] (none for an A2A client's).
     * The head unit shows the model's thinking whenever there is any, as a car's assistant shows how it thinks; 显示思考过程
     * is about Bothy's own card.
     */
    fun runChanged(context: Context, next: DebugOverlayHud, run: DebugUiState?) =
        hudChanged(context, next, run?.surfaceTimeline(showThinking = true).orEmpty(), run?.currentRequest?.text)

    /** The run as the card shows it, with this turn's [turn] lines and the whole [fullRequest] (the card's is cut short). */
    fun hudChanged(context: Context, next: DebugOverlayHud, turn: List<String>, fullRequest: String?) {
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
        val payload = assistantPayload(hud, voice, DebugVoiceController.listening, timeline, request)
        if (payload == sent) return
        sent = payload
        val intent = Intent(ACTION_EVENT).setPackage(pkg)
        payload.forEach { (key, value) ->
            when (value) {
                is String -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                is List<*> -> intent.putStringArrayListExtra(key, ArrayList(value.map { it.toString() }))
            }
        }
        context.sendBroadcast(intent, PERMISSION)
    }

    private const val TAKEOVER_LEASE_MS = 25_000L
}

/**
 * The conversation as the surface shows it: tools as `name|running`, `name|done` or `name|error`; the voice line as the
 * card would say it (the words being heard, or a prompt) with its tone; the turn's [timeline] ([surfaceTimeline]).
 */
internal fun assistantPayload(
    hud: DebugOverlayHud?,
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
internal fun DebugUiState.surfaceTimeline(showThinking: Boolean): List<String> =
    items.currentTurnItems().mapNotNull { item ->
        when (item) {
            is DebugConversationItem.MessageItem -> item.content.trim().takeIf { it.isNotEmpty() && !item.final }?.let { text ->
                when (item.role) {
                    DebugTranscriptRole.THINKING -> if (showThinking) "think|${text.takeLast(MAX_THOUGHT)}" else null
                    DebugTranscriptRole.ASSISTANT -> "say|${text.replace(TTS_PROGRESS_PREFIX, "").trim().takeLast(MAX_THOUGHT)}"
                    else -> null
                }
            }
            is DebugConversationItem.ToolCallItem -> {
                val state = when (item.status) {
                    DebugToolCallStatus.RUNNING -> "running"
                    DebugToolCallStatus.FAILED -> "error"
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

/** A surface taking over or giving back the assistant, or saying something for the user ([DebugAssistantSurface]). */
class DebugAssistantSurfaceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DebugAssistantSurface.ACTION_TAKE_OVER) return
        when (intent.getStringExtra(DebugAssistantSurface.EXTRA_COMMAND)) {
            // As the wake word would: 小博 answers and listens (or listens anew).
            DebugAssistantSurface.COMMAND_WAKE -> {
                DebugOverlayControl.waker?.invoke(DebugWakeSource.SURFACE) ?: startWokenVoice(context.applicationContext, DebugWakeSource.SURFACE)
                return
            }
            // As the card's X: voice ends and 小博 stops talking.
            DebugAssistantSurface.COMMAND_CLOSE -> {
                DebugVoiceController.closedByHand()
                return
            }
        }
        intent.getStringExtra(DebugAssistantSurface.EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            DebugRuntimeSession.submitText(context.applicationContext, it)
            return
        }
        val pkg = intent.getStringExtra(DebugAssistantSurface.EXTRA_PACKAGE)?.takeIf { intent.getBooleanExtra(DebugAssistantSurface.EXTRA_ACTIVE, false) }
        DebugAssistantSurface.takeOver(context.applicationContext, pkg)
    }
}
