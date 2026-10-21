@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import cc.noharry.bothy.kernel.api.AgentRequest
import cc.noharry.bothy.kernel.api.reliability.RuntimeRunStatus
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertTrue

/**
 * Generous bound for run-entry waits. These are liveness checks, not latency assertions, so a
 * loaded machine must not be able to turn them red.
 */
internal const val RUN_ENTRY_TIMEOUT_SEC = 30L

internal fun awaitUntil(timeoutSec: Long = 10, predicate: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
    while (System.nanoTime() < deadline) {
        if (predicate()) return true
        Thread.yield()
    }
    return predicate()
}

internal fun debugMultiSessionRecorders(request: AgentRequest): DebugRuntimeRunRecorders =
    DebugRuntimeRunRecorders(
        evidenceRecorder = null,
        runEvidenceRecorder = DebugRunEvidenceRecorder(
            evidenceDir = Files.createTempDirectory("sample-multi-session-").toFile(),
            request = request,
            providerLabel = "fake",
            streamLabel = "non-stream",
            backendLabel = "fake",
        ),
        turnRecorder = DebugSessionTurnRecorder(request, request.input),
    )


internal fun debugMultiSessionTurn(
    sessionId: String,
    requestId: String,
    userText: String,
): DebugSessionTurn =
    DebugSessionTurn(
        turnId = requestId,
        requestId = requestId,
        runId = requestId,
        sessionId = sessionId,
        scene = "debug-console",
        userText = userText,
        startedAtEpochMillis = 1L,
        completedAtEpochMillis = 2L,
        finalStatus = RuntimeRunStatus.COMPLETED.name,
        finalResponse = userText,
        tools = emptyList(),
    )

internal fun debugMultiSessionRunner(
    scope: CoroutineScope,
    transcript: DebugTranscriptStore,
    coordinator: DebugSessionCoordinator,
    statuses: MutableList<String>,
): DebugRuntimeSessionRunner =
    DebugRuntimeSessionRunner(
        scope = scope,
        transcript = transcript,
        sessionCoordinator = coordinator,
        currentConfig = { null },
        loadConfig = { error("unused") },
        setProvider = {},
        setStatus = { statuses += it },
        streamEnabled = { false },
        useSimulatedModel = { true },
        mcpProviders = { emptyList() },
        a2aBridgeProviders = { emptyList() },
        streamLabel = { "non-stream" },
        backendLabel = { "fake" },
        confirmationMode = { DebugConfirmationMode.ASK_EVERY_TIME },
        notifyChanged = {},
    )

internal class DebugRuntimeMultiSessionHarness : AutoCloseable {
    val transcript = DebugTranscriptStore()
    val coordinator = DebugSessionCoordinator(transcript)
    val statuses = CopyOnWriteArrayList<String>()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    val runner = debugMultiSessionRunner(scope, transcript, coordinator, statuses)

    suspend fun startHeld(fixture: DebugRuntimeMultiSessionFixture): DebugStartRunAdmission {
        coordinator.adoptActiveSessionId(fixture.sessionId)
        return startPrepared(fixture)
    }

    suspend fun startPrepared(fixture: DebugRuntimeMultiSessionFixture): DebugStartRunAdmission {
        val admission = runner.startPreparedRun(
            sessionId = fixture.sessionId,
            request = fixture.request,
            runtime = fixture.runtime,
            resources = fixture.resources(),
            recorders = debugMultiSessionRecorders(fixture.request),
            text = fixture.request.input,
        )
        if (admission != DebugStartRunAdmission.BUSY_MAX_ACTIVE_SESSIONS) {
            // Admission is deterministic; run entry is not, because the flow starts on a shared
            // dispatcher. A tight bound here turned scheduler pressure into red publish gates, so
            // the wait is generous: it only fails when the run genuinely never started.
            assertTrue(
                "run ${fixture.runId} never entered its flow",
                fixture.entered.await(RUN_ENTRY_TIMEOUT_SEC, TimeUnit.SECONDS),
            )
        }
        return admission
    }

    /**
     * True once the run has left the registry and its finished snapshot is published.
     * Waiting on registry removal alone races with the snapshot publication that follows it.
     */
    fun awaitFinishedSnapshot(sessionId: String): Boolean =
        runner.registryForTest().get(sessionId) == null &&
            runner.activeRunSnapshots().any { it.sessionId == sessionId && !it.isActive }

    override fun close() {
        job.cancel()
    }
}
