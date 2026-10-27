@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.runtime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSessionRunnerMultiSessionMemoryTest {
    @Test
    fun memoryResourcesArePerSession() = memoryResourcesArePerSessionCase()
}

internal fun memoryResourcesArePerSessionCase() {
    runBlocking {
        RuntimeMultiSessionHarness().use { harness ->
            val first = RuntimeMultiSessionFixture("session-a", "run-a")
            val second = RuntimeMultiSessionFixture("session-b", "run-b")
            harness.startHeld(first)
            harness.startHeld(second)
            first.release.complete(Unit)
            assertTrue(awaitUntil { first.memoryCloses.get() == 1 })
            assertEquals(1, first.memoryCloses.get())
            assertEquals(0, second.memoryCloses.get())
            second.release.complete(Unit)
            assertTrue(awaitUntil { second.memoryCloses.get() == 1 })
            assertEquals(1, second.memoryCloses.get())
        }
    }
}
