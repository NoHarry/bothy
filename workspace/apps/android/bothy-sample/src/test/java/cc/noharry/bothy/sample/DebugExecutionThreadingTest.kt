@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.AgentEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test

class DebugExecutionThreadingTest {
    @Test fun blockedConstructionAndLateCancellationKeepUiFreeAndCloseOffUi() = runBlocking {
        Executors.newSingleThreadExecutor { Thread(it, "construction-ui") }.asCoroutineDispatcher().use { ui ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val buildThread = AtomicReference<String>()
            val closeThread = AtomicReference<String>()
            val closes = AtomicInteger()
            val delegate = DebugRuntimeMultiSessionFixture("construction", "construction-run").runtime
            val runtime = object : cc.noharry.agent.kernel.api.AgentRuntime by delegate {
                override fun run(request: AgentRequest): Flow<AgentEvent> = emptyFlow()
                override fun close() { closeThread.set(Thread.currentThread().name); closes.incrementAndGet() }
            }
            val job = launch(ui) {
                buildDebugRuntimeOffMain {
                    buildThread.set(Thread.currentThread().name)
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    runtime
                }
            }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                withTimeout(2_000) { withContext(ui) { assertTrue(Thread.currentThread().name.startsWith("construction-ui")) } }
                job.cancel()
                release.countDown()
                job.join()
                assertFalse(buildThread.get().startsWith("construction-ui"))
                assertFalse(closeThread.get().startsWith("construction-ui"))
                assertEquals(1, closes.get())
            } finally { release.countDown(); job.cancelAndJoin() }
        }
    }
}
