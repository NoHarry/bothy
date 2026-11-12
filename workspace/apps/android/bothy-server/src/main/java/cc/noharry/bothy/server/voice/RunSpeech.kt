@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.server.voice
import cc.noharry.agent.ThreadEvent
import cc.noharry.bothy.server.voice.VoiceController
import cc.noharry.bothy.server.voice.realtime.RealtimeWaits
import cc.noharry.bothy.server.voice.realtime.realtimeToolResult

/**
 * What a run says aloud as it goes. A run the card's X closed while it worked says nothing more
 * ([VoiceController.closedByHand]). In 端到端 豆包 speaks for 小博: a result no ask_xiaobo waits for is told to it
 * ([RealtimeVoice.tellLate]), or said by 小博 when no conversation is on air.
 */
internal object RunSpeech {
    private val silenced = ArrayDeque<String>()

    /** The card's X: run [requestId] goes on, silently. Request ids never repeat, so only the latest few are kept. */
    @Synchronized
    fun silence(requestId: String) {
        silenced.addLast(requestId)
        if (silenced.size > MAX_SILENCED) silenced.removeFirst()
    }

    @Synchronized
    private fun isSilenced(requestId: String): Boolean = requestId in silenced

    /** [event] of run [requestId], said by [say] (the run's TTS) or told to 豆包; [owns] whether the run is on screen. */
    fun speak(event: ThreadEvent, requestId: String, owns: () -> Boolean, say: () -> Unit) {
        if (isSilenced(requestId)) return
        routeRunSpeech(event, owns, VoiceController.realtimeSpeaks, RealtimeWaits.waiting, { reply -> RealtimeWaits.hold(requestId, reply) },
            VoiceController::tellRealtime, say)
    }

    private const val MAX_SILENCED = 8
}

/** The same terminal reply reaches either an outstanding realtime call or the live voice conversation. */
internal fun routeRunSpeech(
    event: ThreadEvent,
    owns: () -> Boolean,
    realtimeSpeaks: Boolean,
    waiting: Boolean,
    hold: (String) -> Unit,
    tell: (String, () -> Unit) -> Boolean,
    say: () -> Unit,
) {
    if (!realtimeSpeaks) return say()
    if (!owns()) return
    val reply = when (event) {
        is ThreadEvent.TurnCompleted -> event.result.finalResponse.trim()
        is ThreadEvent.TurnFailed -> runtimeFailureFeedback()
        else -> return
    }.takeIf(String::isNotBlank) ?: return
    if (waiting) hold(reply) else if (!tell(realtimeToolResult(reply), say)) say()
}

internal fun runtimeFailureFeedback(): String =
    if (java.util.Locale.getDefault().language.startsWith("zh", ignoreCase = true)) "这次没能完成，请查看屏幕上的结果。"
    else "I couldn't complete this request. Please check the screen."
