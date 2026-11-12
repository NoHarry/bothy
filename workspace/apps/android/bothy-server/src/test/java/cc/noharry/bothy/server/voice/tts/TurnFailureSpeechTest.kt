package cc.noharry.bothy.server.voice.tts
import cc.noharry.agent.*
import cc.noharry.agent.appserver.runtime.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TurnFailureSpeechTest {
    @Test fun spokenFailureGetsACompletionNoticeWithoutDetailedDiagnostics() = runBlocking {
        val id = SpokenRuns.next()
        val spoken = mutableListOf<Pair<String, Boolean>>()
        val announcer = TtsAnnouncer(this, textOutput = { text, final -> spoken += text to final })
        announcer.observe(RuntimeRunScope(id, "session"), ThreadEvent.TurnFailed(id, TurnError("PROVIDER_FAILURE", "credential-bearing diagnostic")), TtsConfig()) { true }
        assertEquals(1, spoken.size)
        assertTrue(spoken.single().second)
        assertFalse(spoken.single().first.contains("credential-bearing diagnostic"))
        announcer.stop()
    }
}
