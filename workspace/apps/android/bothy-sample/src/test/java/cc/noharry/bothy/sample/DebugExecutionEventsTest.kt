package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.AgentEvent
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DebugExecutionEventsTest {
    @Test fun twoSubscribersReceiveTheSameOrderedMessagesWithoutReexecutingProducer() = runBlocking {
        val id = UUID.randomUUID().toString()
        val ui = DebugExecutionEvents.subscribe(id)
        val a2a = DebugExecutionEvents.subscribe(id)
        val messages = listOf(
            DebugExecutionMessage.Runtime(AgentEvent.Started(id)),
            DebugExecutionMessage.ToolStatus("read_file", "c1", "started"),
            DebugExecutionMessage.Speech("正在查询。", false),
            DebugExecutionMessage.Ended,
        )
        var executions = 0
        withContext(Dispatchers.Default) { executions++; messages.forEach { DebugExecutionEvents.publish(id, it) } }
        DebugExecutionEvents.closeRun(id)
        assertEquals(messages, buildList { for (event in ui.events) add(event) })
        assertEquals(messages, buildList { for (event in a2a.events) add(event) })
        assertEquals(1, executions)
        ui.close(); a2a.close()
    }

    @Test fun slowOptionalSubscriberFailsExplicitlyWithoutBlockingOtherSubscriber() = runBlocking {
        val id = UUID.randomUUID().toString()
        val slow = DebugExecutionEvents.subscribe(id, capacity = 1)
        val fast = DebugExecutionEvents.subscribe(id)
        val message = DebugExecutionMessage.Speech("text", false)
        repeat(3) { DebugExecutionEvents.publish(id, message) }
        repeat(3) { assertEquals(message, fast.events.receive()) }
        assertEquals(message, slow.events.receive())
        assertTrue(slow.events.receiveCatching().exceptionOrNull() is IllegalStateException)
        slow.close(); fast.close()
    }

    @Test fun requiredOverflowIsReportedToPublisherRatherThanSilentlyLosingProjection() {
        val id = UUID.randomUUID().toString()
        val logs = DebugExecutionEvents.subscribe(id, required = true, capacity = 1)
        try {
            DebugExecutionEvents.publish(id, DebugExecutionMessage.Stopped)
            assertThrows(IllegalStateException::class.java) { DebugExecutionEvents.publish(id, DebugExecutionMessage.Ended) }
        } finally { logs.close() }
    }

    @Test fun subscriptionRemovalAndRunIdsAreIsolated() = runBlocking {
        val first = DebugExecutionEvents.subscribe("topic-a")
        val second = DebugExecutionEvents.subscribe("topic-b")
        first.close()
        DebugExecutionEvents.publish("topic-a", DebugExecutionMessage.Stopped)
        assertTrue(second.events.tryReceive().isFailure)
        DebugExecutionEvents.publish("topic-b", DebugExecutionMessage.Ended)
        assertEquals(DebugExecutionMessage.Ended, second.events.receive())
        second.close()
    }

    @Test fun diagnosticsFlushAcknowledgesOrderedAsyncWritesAndReportsFailure() = runBlocking {
        val dir = Files.createTempDirectory("execution-diagnostics").toFile()
        val file = dir.resolve("${UUID.randomUUID()}.log")
        DebugDiagnosticWrites.publish(file, "first\n")
        DebugDiagnosticWrites.publish(file, "second\n")
        DebugDiagnosticWrites.flush(file.name)
        assertEquals("first\nsecond\n", file.readText())
        val invalid = dir.resolve(UUID.randomUUID().toString()).apply { mkdir() }
        DebugDiagnosticWrites.publish(invalid, "must fail")
        assertTrue(runCatching { DebugDiagnosticWrites.flush(invalid.name) }.isFailure)
    }

    @Test fun remoteAdapterProjectsSpeechAndStopsWithoutRawPayloadDisclosure() {
        assertNull(a2aExecutionOutput(DebugExecutionMessage.RawTool("arguments", "secret")))
        assertTrue(a2aExecutionOutput(DebugExecutionMessage.Speech("你好", true)) is cc.noharry.agent.a2a.server.A2aOutput.Speech)
        assertEquals(cc.noharry.agent.a2a.server.A2aOutput.State.CANCELED,
            (a2aExecutionOutput(DebugExecutionMessage.Stopped) as cc.noharry.agent.a2a.server.A2aOutput.Result).state)
    }

    @Test fun staleTransportCancellationCannotCancelReplacementInSharedService() = runBlocking {
        DebugRuntimeMultiSessionHarness().use { harness ->
            val first = DebugRuntimeMultiSessionFixture("shared-external", "old-task")
            val replacement = DebugRuntimeMultiSessionFixture("shared-external", "new-task")
            harness.startHeld(first)
            harness.startHeld(replacement)
            harness.runner.cancelRun(first.sessionId, first.runId).join()
            assertTrue(harness.runner.activeRunSnapshots().any { it.runId == replacement.runId && it.isActive })
            replacement.release.complete(Unit)
            assertTrue(awaitUntil { harness.runner.activeRunSnapshots().none { it.isActive } })
        }
    }

    @Test fun finishingRequiredSubscriberDetachesBeforeDrainingAcceptedMessages() = runBlocking {
        val id = UUID.randomUUID().toString()
        val logs = DebugExecutionEvents.subscribe(id, required = true)
        val observer = DebugExecutionEvents.subscribe(id)
        DebugExecutionEvents.publish(id, DebugExecutionMessage.Stopped)
        logs.finish()
        DebugExecutionEvents.publish(id, DebugExecutionMessage.ObserverIssue("ui"))
        assertEquals(DebugExecutionMessage.Stopped, logs.events.receive())
        assertTrue(logs.events.receiveCatching().isClosed)
        assertEquals(DebugExecutionMessage.Stopped, observer.events.receive())
        assertEquals(DebugExecutionMessage.ObserverIssue("ui"), observer.events.receive())
        logs.close(); observer.close()
    }
}
