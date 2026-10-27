@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.runtime
import cc.noharry.agent.kernel.api.PostUserInputRequest
import cc.noharry.agent.kernel.api.PostUserInputResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimeInputTest {
    @Test fun answerTargetsTheOriginalRunRegardlessOfUiSelectionAndRejectsReplacedRuns() = runBlocking {
        RuntimeMultiSessionHarness().use { harness ->
            val original = RuntimeMultiSessionFixture("remote", "original")
            val selected = RuntimeMultiSessionFixture("local", "selected")
            harness.startHeld(original)
            harness.startHeld(selected)
            assertEquals("local", harness.coordinator.activeSessionId)
            assertEquals(PostUserInputResult.Accepted, harness.runner.postInput("remote", "original", "answer", "Suzhou", "q"))
            assertEquals(listOf(PostUserInputRequest("original", "answer", "Suzhou", "remote", "q")), original.postedSteering)
            assertTrue(selected.postedSteering.isEmpty())
            assertEquals(2, harness.runner.activeRunCount)
            assertEquals(PostUserInputResult.NotFound, harness.runner.postInput("local", "original", "wrong", "text", "q"))
            val successor = RuntimeMultiSessionFixture("remote", "successor")
            harness.startHeld(successor)
            assertEquals(PostUserInputResult.NotFound, harness.runner.postInput("remote", "original", "late", "text", "q"))
            assertTrue(successor.postedSteering.isEmpty())
            selected.release.complete(Unit)
            successor.release.complete(Unit)
            Unit
        }
    }
}
