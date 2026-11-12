@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.server.console
import cc.noharry.agent.appserver.runtime.RuntimeRunScope
import cc.noharry.agent.*
import cc.noharry.bothy.server.voice.tts.TtsAnnouncer
import cc.noharry.bothy.server.voice.tts.TtsConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * An asynchronous question is said once per question. How questions are kept, shown and answered late is the server's
 * (`UserQuestionsTest` in agent-app-server); what another agent's task shows of one is the A2A plugin's.
 */
class UserQuestionsTest {
    @Test fun asyncQuestionsSpeakOncePerIdWithoutFinalizingTheOriginalTask() = runBlocking {
        val spoken = mutableListOf<Pair<String, Boolean>>()
        val speaker = TtsAnnouncer(this, textOutput = { text, final -> spoken += text to final })
        val scope = RuntimeRunScope("run", "session")
        val question = Question("run/q/0", "run", "去哪？", async = true)
        val event = ThreadEvent.ItemStarted(question)
        repeat(2) { speaker.observe(scope, event, TtsConfig()) { true } }
        speaker.observe(scope, event.copy(item = question.copy(id = "run/q/1")), TtsConfig()) { true }
        assertEquals(listOf("去哪？" to false, "去哪？" to false), spoken)
        speaker.stop()
    }
}
