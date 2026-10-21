@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugRuntimeSessionRunnerMultiSessionFinalizationTest {
    @Test
    fun stopSelectedDoesNotCancelOthers() = stopSelectedDoesNotCancelOthersCase()

    @Test
    fun runCompletionAndResourceCloseAreIsolated() = runCompletionAndResourceCloseAreIsolatedCase()

    @Test
    fun stopAllDrainsOnce() = stopAllDrainsOnceCase()

    @Test
    fun terminalConfirmationPersistsExactlyOneTurnsJsonlRow() =
        terminalConfirmationPersistsExactlyOneTurnsJsonlRowCase()

    @Test
    fun uncooperativeCancelDoesNotDeadlockAdmission() =
        uncooperativeCancelDoesNotDeadlockAdmissionCase()
}

internal fun stopSelectedDoesNotCancelOthersCase() {
    runBlocking {
        DebugRuntimeMultiSessionHarness().use { harness ->
            val selected = DebugRuntimeMultiSessionFixture("session-a", "run-a")
            val background = (1..3).map { DebugRuntimeMultiSessionFixture("session-bg-$it", "run-bg-$it") }
            harness.startHeld(selected)
            background.forEach { harness.startHeld(it) }
            harness.coordinator.adoptActiveSessionId("session-a")
            assertTrue(harness.runner.isRunning)
            harness.runner.stopRun().join()
            assertFalse(harness.runner.isRunning)
            assertEquals(1, selected.runtimeCloses.get())
            assertEquals(1, selected.memoryCloses.get())
            background.forEach { fixture ->
                assertEquals(0, fixture.runtimeCloses.get())
                assertEquals(0, fixture.memoryCloses.get())
                assertTrue(fixture.entered.await(2, TimeUnit.SECONDS))
            }
            assertEquals(3, harness.runner.activeRunCount)
            background.forEach { it.release.complete(Unit) }
        }
    }
}

internal fun runCompletionAndResourceCloseAreIsolatedCase() {
    runBlocking {
        DebugRuntimeMultiSessionHarness().use { harness ->
            val finishing = DebugRuntimeMultiSessionFixture("session-done", "run-done")
            val held = DebugRuntimeMultiSessionFixture("session-live", "run-live")
            harness.startHeld(finishing)
            harness.startHeld(held)
            finishing.release.complete(Unit)
            assertTrue(awaitUntil { finishing.runtimeCloses.get() == 1 && harness.runner.activeRunCount == 1 })
            assertEquals(1, finishing.runtimeCloses.get())
            assertEquals(1, finishing.memoryCloses.get())
            assertEquals(0, held.runtimeCloses.get())
            assertEquals(0, held.memoryCloses.get())
            assertEquals(1, harness.runner.activeRunCount)
            assertTrue(harness.runner.activeRunSnapshots().single { it.isActive }.sessionId == "session-live")
            held.release.complete(Unit)
        }
    }
}

internal fun stopAllDrainsOnceCase() {
    runBlocking {
        DebugRuntimeMultiSessionHarness().use { harness ->
            val fixtures = (1..4).map { DebugRuntimeMultiSessionFixture("session-$it", "run-$it") }
            fixtures.forEach { harness.startHeld(it) }
            harness.runner.stopAllRuns().join()
            assertEquals(0, harness.runner.activeRunCount)
            fixtures.forEach { fixture ->
                assertEquals(1, fixture.runtimeCloses.get())
                assertEquals(1, fixture.memoryCloses.get())
            }
            harness.runner.stopAllRuns().join()
            fixtures.forEach { fixture ->
                assertEquals(1, fixture.runtimeCloses.get())
                assertEquals(1, fixture.memoryCloses.get())
            }
        }
    }
}

internal fun terminalConfirmationPersistsExactlyOneTurnsJsonlRowCase() {
    runBlocking {
        DebugRuntimeMultiSessionHarness().use { harness ->
            val root = Files.createTempDirectory("sample-confirm-once-").toFile()
            try {
                val store = DebugSessionStore(root)
                val sessionId = "session-confirm"
                store.ensureSession(sessionId)
                harness.coordinator.attachStoreForTest(store)
                val fixture = DebugRuntimeMultiSessionFixture(sessionId, "run-confirm", waitForConfirmation = true)
                harness.startHeld(fixture)
                harness.coordinator.adoptActiveSessionId(sessionId)
                harness.runner.resolveConfirmation("pending-confirm", approve = true)
                assertTrue(fixture.confirmationPosted.await(2, TimeUnit.SECONDS))
                assertTrue(awaitUntil { harness.awaitFinishedSnapshot(sessionId) })
                val turnsFile = File(File(root, encodeSessionId(sessionId)), "turns.jsonl")
                assertTrue(turnsFile.isFile)
                assertEquals(1, turnsFile.readLines().count { it.isNotBlank() })
            } finally {
                root.deleteRecursively()
            }
        }
    }
}

internal fun uncooperativeCancelDoesNotDeadlockAdmissionCase() {
    runBlocking {
        DebugRuntimeMultiSessionHarness().use { harness ->
            val stuck = DebugRuntimeMultiSessionFixture("session-stuck", "run-stuck", ignoreCancel = true)
            harness.startHeld(stuck)
            withTimeout(2_000) { harness.runner.stopRun().join() }
            assertEquals(1, stuck.runtimeCloses.get())
            val other = DebugRuntimeMultiSessionFixture("session-free", "run-free")
            val admission = withTimeout(2_000) { harness.startHeld(other) }
            assertEquals(DebugStartRunAdmission.STARTED, admission)
            assertTrue(other.entered.await(2, TimeUnit.SECONDS))
            assertEquals(1, harness.runner.activeRunCount)
            other.release.complete(Unit)
            stuck.release.complete(Unit)
        }
    }
}
