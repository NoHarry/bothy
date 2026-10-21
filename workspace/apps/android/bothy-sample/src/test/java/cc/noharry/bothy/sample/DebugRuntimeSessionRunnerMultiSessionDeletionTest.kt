@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import cc.noharry.bothy.kernel.api.reliability.RuntimeRunStatus
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugRuntimeSessionRunnerMultiSessionDeletionTest {
    @Test
    fun deleteActiveSessionDoesNotResurrectDirectory() =
        deleteActiveSessionDoesNotResurrectDirectoryCase()

    @Test
    fun deleteThenExplicitRecreateRevivesTombstone() =
        deleteThenExplicitRecreateRevivesTombstoneCase()

    @Test
    fun deleteRemovesFinishedSnapshotWithoutResurrecting() =
        deleteRemovesFinishedSnapshotWithoutResurrectingCase()
}

internal fun deleteActiveSessionDoesNotResurrectDirectoryCase() {
    runBlocking {
        DebugRuntimeMultiSessionHarness().use { harness ->
            val root = Files.createTempDirectory("sample-delete-active-").toFile()
            try {
                val store = DebugSessionStore(root)
                val sessionId = "session-delete"
                store.ensureSession(sessionId)
                harness.coordinator.attachStoreForTest(store)
                val fixture = DebugRuntimeMultiSessionFixture(sessionId, "run-delete")
                harness.startHeld(fixture)
                val dir = File(root, encodeSessionId(sessionId))
                assertTrue(dir.isDirectory)
                harness.runner.deleteActiveSession(sessionId)
                harness.coordinator.deletePersistedSession(sessionId)
                assertTrue(awaitUntil { fixture.runtimeCloses.get() == 1 })
                harness.coordinator.appendTurn(lateDeleteTurn(sessionId))
                assertFalse(dir.exists())
                assertFalse(File(dir, "turns.jsonl").exists())
                assertTrue(store.listSessions().none { it.sessionId == sessionId })
            } finally {
                root.deleteRecursively()
            }
        }
    }
}

internal fun lateDeleteTurn(sessionId: String): DebugSessionTurn =
    DebugSessionTurn(
        turnId = "late",
        requestId = "late",
        runId = "run-delete",
        sessionId = sessionId,
        scene = "debug-console",
        userText = "should not persist",
        startedAtEpochMillis = 1L,
        completedAtEpochMillis = 2L,
        finalStatus = RuntimeRunStatus.COMPLETED.name,
        finalResponse = "late",
        tools = emptyList(),
    )

internal fun deleteThenExplicitRecreateRevivesTombstoneCase() {
    runBlocking {
        DebugRuntimeMultiSessionHarness().use { harness ->
            val root = Files.createTempDirectory("sample-delete-recreate-").toFile()
            try {
                val store = DebugSessionStore(root)
                val sessionId = "session-recreate"
                store.ensureSession(sessionId)
                harness.coordinator.attachStoreForTest(store)
                val fixture = DebugRuntimeMultiSessionFixture(sessionId, "run-recreate")
                harness.startHeld(fixture)
                harness.runner.deleteActiveSession(sessionId)
                harness.coordinator.deletePersistedSession(sessionId)
                assertTrue(awaitUntil { fixture.runtimeCloses.get() == 1 })
                assertTombstoneThenRevive(harness, store, sessionId)
            } finally {
                root.deleteRecursively()
            }
        }
    }
}

internal suspend fun assertTombstoneThenRevive(
    harness: DebugRuntimeMultiSessionHarness,
    store: DebugSessionStore,
    sessionId: String,
) {
    harness.coordinator.appendTurn(debugMultiSessionTurn(sessionId, "late", "should stay suppressed"))
    assertTrue(store.listSessions().none { it.sessionId == sessionId })
    val implicit = DebugRuntimeMultiSessionFixture(sessionId, "run-implicit")
    harness.startHeld(implicit)
    implicit.release.complete(Unit)
    assertTrue(awaitUntil { implicit.runtimeCloses.get() == 1 })
    harness.coordinator.appendTurn(debugMultiSessionTurn(sessionId, "implicit", "still suppressed"))
    assertTrue(store.listSessions().none { it.sessionId == sessionId })
    harness.coordinator.reviveDeletedSession(sessionId)
    store.ensureSession(sessionId)
    harness.coordinator.appendTurn(debugMultiSessionTurn(sessionId, "revived", "host recreate"))
    assertTrue(store.listSessions().any { it.sessionId == sessionId })
    assertEquals(1, store.readTurns(sessionId).size)
    assertEquals("host recreate", store.readTurns(sessionId).single().userText)
}

internal fun deleteRemovesFinishedSnapshotWithoutResurrectingCase() {
    runBlocking {
        DebugRuntimeMultiSessionHarness().use { harness ->
            val fixture = DebugRuntimeMultiSessionFixture("session-finished", "run-finished")
            harness.startHeld(fixture)
            fixture.release.complete(Unit)
            assertTrue(
                awaitUntil {
                    harness.runner.activeRunSnapshots()
                        .any { it.sessionId == "session-finished" && !it.isActive }
                },
            )
            harness.runner.deleteActiveSession("session-finished")
            assertTrue(harness.runner.activeRunSnapshots().none { it.sessionId == "session-finished" })
            harness.runner.discardSession("session-finished")
            assertTrue(harness.runner.activeRunSnapshots().none { it.sessionId == "session-finished" })
            assertTrue(harness.coordinator.isSessionDeleted("session-finished"))
        }
    }
}
