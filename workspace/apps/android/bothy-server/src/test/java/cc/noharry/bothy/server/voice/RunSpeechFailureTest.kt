package cc.noharry.bothy.server.voice

import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.TurnError
import org.junit.Assert.*
import org.junit.Test

class RunSpeechFailureTest {
    private val failed = ThreadEvent.TurnFailed("voice", TurnError("PROVIDER", "credential-bearing diagnostic"))

    @Test fun realtimeFailureReachesTheWaitingCallWithoutDetails() {
        var held: String? = null
        routeRunSpeech(failed, { true }, true, true, { held = it }, { _, _ -> error("waiting call owns reply") }, { error("realtime owns speech") })
        assertNotNull(held)
        assertFalse(held!!.contains("credential-bearing diagnostic"))
    }

    @Test fun realtimeFailureIsToldToTheConversationOrFallsBackToLocalSpeech() {
        var wire: String? = null
        var said = 0
        routeRunSpeech(failed, { true }, true, false, {}, { text, _ -> wire = text; true }, { said++ })
        assertNotNull(wire)
        assertFalse(wire!!.contains("credential-bearing diagnostic"))
        assertEquals(0, said)
        routeRunSpeech(failed, { true }, true, false, {}, { _, _ -> false }, { said++ })
        assertEquals(1, said)
    }

    @Test fun aBackgroundFailureDoesNotInterruptTheCurrentVoiceConversation() {
        routeRunSpeech(failed, { false }, true, false, { error("not owned") }, { _, _ -> error("not owned") }, { error("not owned") })
    }
}
