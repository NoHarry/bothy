@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugRuntimeSessionRunnerMultiSessionConcurrencyTest {
    @Test
    fun concurrentSameSessionDoubleStartLeavesOneOwnerAndClosesLoser() =
        concurrentSameSessionDoubleStartLeavesOneOwnerAndClosesLoserCase()

    @Test
    fun concurrentNineDistinctStartsCapsAtEight() = concurrentNineDistinctStartsCapsAtEightCase()

    @Test
    fun stopAllRaceAdmitsNoneUntilDrainComplete() = stopAllRaceAdmitsNoneUntilDrainCompleteCase()

    @Test
    fun recordSnapshotConcurrentUpdatesAreSafe() = recordSnapshotConcurrentUpdatesAreSafeCase()
}

internal fun concurrentSameSessionDoubleStartLeavesOneOwnerAndClosesLoserCase() {
    runBlocking {
        DebugRuntimeMultiSessionHarness().use { harness ->
            val first = DebugRuntimeMultiSessionFixture("session-race", "run-1")
            val second = DebugRuntimeMultiSessionFixture("session-race", "run-2")
            val admissions = Collections.synchronizedList(mutableListOf<DebugStartRunAdmission>())
            coroutineScope {
                launch { admissions += harness.startPrepared(first) }
                launch { admissions += harness.startPrepared(second) }
            }
            assertEquals(1, harness.runner.activeRunCount)
            assertEquals(1, harness.runner.registryForTest().records().count { it.sessionId == "session-race" })
            val active = harness.runner.activeRunSnapshots().single { it.isActive }
            assertTrue(active.runId == "run-1" || active.runId == "run-2")
            val loser = if (active.runId == "run-1") second else first
            val winner = if (active.runId == "run-1") first else second
            assertTrue(awaitUntil { loser.runtimeCloses.get() == 1 && loser.memoryCloses.get() == 1 })
            assertEquals(1, loser.runtimeCloses.get())
            assertEquals(1, loser.memoryCloses.get())
            assertEquals(0, winner.runtimeCloses.get())
            assertEquals(0, winner.memoryCloses.get())
            assertNotEquals(loser.runId, winner.runId)
            winner.release.complete(Unit)
        }
    }
}

internal fun concurrentNineDistinctStartsCapsAtEightCase() {
    runBlocking {
        DebugRuntimeMultiSessionHarness().use { harness ->
            val fixtures = (1..9).map { DebugRuntimeMultiSessionFixture("session-$it", "run-$it") }
            val admissions = Collections.synchronizedList(mutableListOf<DebugStartRunAdmission>())
            coroutineScope {
                fixtures.forEach { fixture ->
                    launch { admissions += harness.startPrepared(fixture) }
                }
            }
            assertEquals(DEBUG_MAX_ACTIVE_SESSION_RUNS, harness.runner.activeRunCount)
            assertEquals(1, admissions.count { it == DebugStartRunAdmission.BUSY_MAX_ACTIVE_SESSIONS })
            assertEquals(8, admissions.count { it == DebugStartRunAdmission.STARTED })
            assertEquals(1, fixtures.count { it.runtimeCloses.get() == 1 })
            assertEquals(8, fixtures.count { it.runtimeCloses.get() == 0 })
            fixtures.filter { it.runtimeCloses.get() == 0 }.forEach { it.release.complete(Unit) }
        }
    }
}

internal fun stopAllRaceAdmitsNoneUntilDrainCompleteCase() {
    runBlocking {
        DebugRuntimeMultiSessionHarness().use { harness ->
            val held = (1..4).map { DebugRuntimeMultiSessionFixture("session-$it", "run-$it") }
            held.forEach { harness.startHeld(it) }
            val extras = (1..3).map { DebugRuntimeMultiSessionFixture("extra-$it", "extra-$it") }
            coroutineScope {
                val stop = async { harness.runner.stopAllRuns().join() }
                extras.map { extra ->
                    async { harness.startPrepared(extra) }
                }.awaitAll()
                stop.await()
            }
            held.forEach { fixture ->
                assertEquals(1, fixture.runtimeCloses.get())
                assertEquals(1, fixture.memoryCloses.get())
            }
            assertTrue(harness.runner.activeRunCount <= extras.size)
            assertTrue(harness.runner.activeRunCount <= DEBUG_MAX_ACTIVE_SESSION_RUNS)
            extras.forEach { extra ->
                val closed = extra.runtimeCloses.get()
                val active = harness.runner.activeRunSnapshots().any { it.runId == extra.runId && it.isActive }
                assertTrue(closed == 1 || active)
                if (active) extra.release.complete(Unit)
            }
        }
    }
}

internal fun recordSnapshotConcurrentUpdatesAreSafeCase() {
    runBlocking {
        DebugRuntimeMultiSessionHarness().use { harness ->
            val fixtures = (1..4).map { DebugRuntimeMultiSessionFixture("session-$it", "run-$it") }
            fixtures.forEach { harness.startHeld(it) }
            coroutineScope {
                repeat(8) {
                    launch(Dispatchers.Default) { hammerSnapshots(harness, fixtures) }
                }
                fixtures.forEach { fixture ->
                    launch(Dispatchers.Default) { hammerRecord(harness, fixture) }
                }
            }
            assertEquals(4, harness.runner.activeRunCount)
            assertEquals(4, harness.runner.activeRunSnapshots().size)
            fixtures.forEach { it.release.complete(Unit) }
        }
    }
}

internal fun hammerSnapshots(
    harness: DebugRuntimeMultiSessionHarness,
    fixtures: List<DebugRuntimeMultiSessionFixture>,
) {
    repeat(40) {
        harness.runner.activeRunSnapshots()
        harness.runner.decorateSessions(
            fixtures.map { fixture ->
                DebugSessionSummary(fixture.sessionId, fixture.sessionId, 1L, 1, "")
            },
        )
        harness.runner.projectSelectedSessionState()
    }
}

internal fun hammerRecord(
    harness: DebugRuntimeMultiSessionHarness,
    fixture: DebugRuntimeMultiSessionFixture,
) {
    val record = harness.runner.registryForTest().get(fixture.sessionId)
    repeat(40) { index ->
        record?.addTokenUsage(
            DebugTokenUsageDelta(
                inputTokens = 1,
                outputTokens = 1,
                totalTokens = 2,
                cachedInputTokens = 0,
                cacheHitTokens = 0,
                cacheMissTokens = 1,
            ),
        )
        record?.lifecycleStatus = if (index % 2 == 0) {
            RuntimeRunStatus.RUNNING.name
        } else {
            RuntimeRunStatus.WAITING_CONFIRMATION.name
        }
    }
}
