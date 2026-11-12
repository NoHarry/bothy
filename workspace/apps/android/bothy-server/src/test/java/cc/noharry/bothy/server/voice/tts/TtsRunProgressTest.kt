@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.server.voice.tts

import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.appserver.runtime.RuntimeRunScope
import cc.noharry.agent.spi.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.spi.reliability.RuntimeCompletionSummary
import cc.noharry.agent.spi.reliability.RuntimeResult
import cc.noharry.agent.spi.reliability.RuntimeRunStatus
import cc.noharry.bothy.server.runtime.testSpokenNote
import cc.noharry.bothy.server.runtime.testTurnCompleted
import cc.noharry.bothy.server.runtime.testTurnFailed
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** One run's end leaves the progress another run has said alone: a line said once is not said again. */
class TtsRunProgressTest {
    @Test
    fun anotherRunsPartialAnswerKeepsThisRunsSpokenProgress() = runBlocking {
        val partial = testTurnCompleted(
            RuntimeResult("a", RuntimeRunStatus.COMPLETED, "要去哪个停车场？", completion = RuntimeCompletionSummary(RuntimeCompletionOutcome.PARTIAL)),
        )
        assertEquals(listOf(PROGRESS), progressAround(partial))
    }

    @Test
    fun anotherRunsFailureKeepsThisRunsSpokenProgress() = runBlocking {
        assertEquals(listOf(PROGRESS), progressAround(testTurnFailed("a", "PROVIDER_FAILURE", "failed")))
    }

    /** What run b says of [PROGRESS], noted twice, with run a ending ([aEnds]) in between. */
    private suspend fun progressAround(aEnds: ThreadEvent): List<String> = kotlinx.coroutines.coroutineScope {
        val spoken = mutableListOf<String>()
        val announcer = TtsAnnouncer(this, textOutput = { text, final -> if (!final) spoken += text })
        fun saysProgress() {
            announcer.observe(B, testSpokenNote("b", PROGRESS), TtsConfig()) { true }
            announcer.observe(B, toolStarted("b"), TtsConfig()) { true }
        }
        saysProgress()
        announcer.observe(RuntimeRunScope("a", "s"), aEnds, TtsConfig()) { true }
        saysProgress()
        announcer.stop()
        spoken
    }

    private companion object {
        val B = RuntimeRunScope("b", "s")
        const val PROGRESS = "我先查一下沿途的路况。"
    }
}
