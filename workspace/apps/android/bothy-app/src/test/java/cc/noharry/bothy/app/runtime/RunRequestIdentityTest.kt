@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.runtime
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.kernel.api.model.ModelRef
import cc.noharry.bothy.app.session.SessionCoordinator
import cc.noharry.bothy.app.session.TranscriptStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RunRequestIdentityTest {
    @Test fun runnerPassesTheFullRequestThroughAdmissionWithoutDroppingModelOrScene() = runBlocking {
        val fixture = RuntimeMultiSessionFixture("s", "r")
        val request = fixture.request.copy(modelRef = ModelRef("openai", "deepseek-flash", "thinking"), scene = "test-scene")
        var observed: AgentRequest? = null
        val runtime = object : AgentRuntime by fixture.runtime {
            override fun run(request: AgentRequest) = fixture.runtime.run(request).also { observed = request }
        }
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val transcript = TranscriptStore()
        val coordinator = SessionCoordinator(transcript)
        val runner = multiSessionRunner(scope, transcript, coordinator, mutableListOf())
        try {
            coordinator.adoptActiveSessionId("s")
            runner.startPreparedRun("s", request, runtime, fixture.resources(), multiSessionRecorders(request))
            assertTrue(fixture.entered.await(5, TimeUnit.SECONDS))
            assertSame(request, observed)
        } finally {
            fixture.release.complete(Unit)
            job.cancelAndJoin()
        }
    }
}
