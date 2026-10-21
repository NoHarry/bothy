@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import cc.noharry.bothy.kernel.api.AgentEvent

/**
 * What a run says aloud as it goes. A run the card's X closed while it worked says nothing more
 * ([DebugVoiceController.closedByHand]). In 端到端 豆包 speaks for 小博: a result no ask_xiaobo waits for is told to it
 * ([DebugRealtimeVoice.tellLate]), or said by 小博 when no conversation is on air.
 */
internal object DebugRunSpeech {
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
    fun speak(event: AgentEvent, requestId: String, owns: () -> Boolean, say: () -> Unit) {
        when {
            isSilenced(requestId) -> Unit
            !DebugVoiceController.realtimeSpeaks -> say()
            event is AgentEvent.ResultAvailable && owns() ->
                event.result.response.displayReplyText().takeIf(String::isNotBlank)?.let { reply ->
                    when {
                        // The waiting ask_xiaobo answers with it, even if its wait runs out before the reply reaches it.
                        DebugRealtimeWaits.waiting -> DebugRealtimeWaits.hold(reply)
                        !DebugVoiceController.tellRealtime(realtimeToolResult(reply), say) -> say()
                    }
                }
        }
    }

    private const val MAX_SILENCED = 8
}
