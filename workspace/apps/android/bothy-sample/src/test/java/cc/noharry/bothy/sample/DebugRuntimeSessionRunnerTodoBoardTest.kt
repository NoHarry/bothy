@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The todo board is exclusively event-sourced from public `update_todo` payloads: a session
 * shows tasks only while a run that published them owns it, and no on-disk todo file can
 * repopulate the card.
 */
class DebugRuntimeSessionRunnerTodoBoardTest {
    @Test
    fun todoFinishedEventUpdatesSelectedSessionBoard() = runBlocking {
        DebugRuntimeMultiSessionHarness().use { harness ->
            val fixture = DebugRuntimeMultiSessionFixture("session-todo", "run-todo", emitTodo = true)

            harness.startHeld(fixture)

            assertTrue(awaitUntil { harness.runner.contextPanelState.todo.isNotEmpty() })
            assertEquals(
                listOf(
                    "导航到加油站" to "in_progress",
                    "播报到达" to "pending",
                ),
                harness.runner.contextPanelState.todo.map { it.content to it.status },
            )
            fixture.release.complete(Unit)
            assertTrue(fixture.finished.await(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun boardIsEmptyBeforeAnyRunOwnsTheSession() {
        DebugRuntimeMultiSessionHarness().use { harness ->
            // Cold start / process restart: no live record and no finished snapshot exist yet.
            assertEquals(emptyList<DebugTodoEntry>(), harness.runner.eventTodoBoard("session-cold"))
            harness.coordinator.adoptActiveSessionId("session-cold")
            harness.runner.projectSelectedSessionState()
            assertTrue(harness.runner.contextPanelState.todo.isEmpty())
        }
    }

    @Test
    fun backgroundSessionTodoIsStashedOnRecordWithoutTouchingSelectedBoard() = runBlocking {
        DebugRuntimeMultiSessionHarness().use { harness ->
            val selected = DebugRuntimeMultiSessionFixture("session-selected", "run-selected")
            val background = DebugRuntimeMultiSessionFixture("session-bg", "run-bg", emitTodo = true)

            harness.startHeld(selected)
            harness.startPrepared(background)

            assertTrue(
                awaitUntil {
                    harness.runner.registryForTest().get("session-bg")?.todoBoard?.isNotEmpty() == true
                },
            )
            assertEquals(
                listOf("导航到加油站", "播报到达"),
                harness.runner.registryForTest().get("session-bg")?.todoBoard?.map { it.content },
            )
            assertTrue(harness.runner.contextPanelState.todo.isEmpty())
            listOf(selected, background).forEach { fixture ->
                fixture.release.complete(Unit)
                assertTrue(fixture.finished.await(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun switchingBackToFinishedSessionRestoresTodoBoardIntoPanel() = runBlocking {
        DebugRuntimeMultiSessionHarness().use { harness ->
            val selected = DebugRuntimeMultiSessionFixture("session-front", "run-front")
            val background = DebugRuntimeMultiSessionFixture("session-done", "run-done", emitTodo = true)

            harness.startHeld(selected)
            harness.startPrepared(background)
            assertTrue(awaitUntil { harness.runner.eventTodoBoard("session-done").isNotEmpty() })
            background.release.complete(Unit)
            assertTrue(background.finished.await(2, TimeUnit.SECONDS))
            assertTrue(awaitUntil { harness.awaitFinishedSnapshot("session-done") })
            // The selected session never emitted todo, so its board stays empty.
            assertTrue(harness.runner.contextPanelState.todo.isEmpty())

            harness.coordinator.adoptActiveSessionId("session-done")
            harness.runner.projectSelectedSessionState()

            // The live record is gone, yet the finished snapshot restores the event board
            // into the panel that the UI renders.
            assertEquals(
                listOf("导航到加油站", "播报到达"),
                harness.runner.contextPanelState.todo.map { it.content },
            )
            selected.release.complete(Unit)
            assertTrue(selected.finished.await(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun newRunWithoutTodoEventDoesNotShowPreviousRunBoard() = runBlocking {
        DebugRuntimeMultiSessionHarness().use { harness ->
            val first = DebugRuntimeMultiSessionFixture("session-reused", "run-1", emitTodo = true)
            val second = DebugRuntimeMultiSessionFixture("session-reused", "run-2")

            harness.startHeld(first)
            assertTrue(awaitUntil { harness.runner.contextPanelState.todo.isNotEmpty() })
            first.release.complete(Unit)
            assertTrue(first.finished.await(2, TimeUnit.SECONDS))
            assertTrue(awaitUntil { harness.awaitFinishedSnapshot("session-reused") })

            harness.startPrepared(second)
            // Switch away and back so the board comes from the projection rather than from
            // the new run's own UI reset.
            harness.coordinator.adoptActiveSessionId("session-other")
            harness.runner.projectSelectedSessionState()
            harness.coordinator.adoptActiveSessionId("session-reused")
            harness.runner.projectSelectedSessionState()

            // The new run owns the session and published no update_todo, so the previous run's
            // board must not leak back.
            assertEquals(emptyList<DebugTodoEntry>(), harness.runner.eventTodoBoard("session-reused"))
            assertTrue(harness.runner.contextPanelState.todo.isEmpty())
            second.release.complete(Unit)
            assertTrue(second.finished.await(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun explicitEmptyTodoEventClearsBoard() {
        assertEquals(
            emptyList<DebugTodoEntry>(),
            decodeTodoToolResult("""{"ok":true,"todoCount":0,"todos":[]}"""),
        )
    }

    @Test
    fun failureTodoPayloadKeepsLastBoardState() {
        assertEquals(null, decodeTodoToolResult("""{"ok":false,"code":"TODO_STORE_WRITE_FAILED","message":"x"}"""))
    }
}
