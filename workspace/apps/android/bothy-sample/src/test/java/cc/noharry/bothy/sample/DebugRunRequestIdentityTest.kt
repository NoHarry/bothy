@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import cc.noharry.bothy.kernel.api.AgentRequest
import cc.noharry.bothy.kernel.api.AgentRuntime
import cc.noharry.bothy.kernel.api.model.ModelRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import java.util.concurrent.TimeUnit
import org.junit.Test

class DebugRunRequestIdentityTest {
    @Test fun runnerPassesTheFullRequestThroughAdmissionWithoutDroppingModelOrScene() = runBlocking {
        val fixture = DebugRuntimeMultiSessionFixture("s", "r")
        val request = fixture.request.copy(modelRef = ModelRef("openai", "deepseek-flash", "thinking"), scene = "test-scene")
        var observed: AgentRequest? = null
        val runtime = object : AgentRuntime by fixture.runtime {
            override fun run(request: AgentRequest) = fixture.runtime.run(request).also { observed = request }
        }
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val transcript = DebugTranscriptStore()
        val coordinator = DebugSessionCoordinator(transcript)
        val runner = debugMultiSessionRunner(scope, transcript, coordinator, mutableListOf())
        try {
            coordinator.adoptActiveSessionId("s")
            runner.startPreparedRun("s", request, runtime, fixture.resources(), debugMultiSessionRecorders(request))
            assertTrue(fixture.entered.await(5, TimeUnit.SECONDS))
            assertSame(request, observed)
        } finally {
            fixture.release.complete(Unit)
            job.cancelAndJoin()
        }
    }
}
