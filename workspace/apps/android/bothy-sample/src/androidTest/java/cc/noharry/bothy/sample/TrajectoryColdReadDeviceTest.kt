@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.noharry.bothy.kernel.api.AgentRequest
import cc.noharry.bothy.kernel.api.SessionEventLogDurability
import cc.noharry.bothy.kernel.api.SessionEventLogHealthStatus
import cc.noharry.bothy.kernel.api.SessionEventQuery
import java.util.UUID
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith

/**
 * Device acceptance for the Trajectory cold read.
 *
 * The failure this guards against only reproduced on a real device: a JVM/Robolectric suite runs
 * against temp directories and cannot exercise the app-private path shape, the file lock or the
 * durable root guards. Every assertion here goes through the production Sample seams —
 * [buildDebugRuntime] to write and [TrajectoryEventLogGateway] to read — with the writer runtime
 * closed in between, which is what an app restart leaves behind.
 */
@RunWith(AndroidJUnit4::class)
class TrajectoryColdReadDeviceTest {

    private val targetContext: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureRoot by lazy {
        File(targetContext.noBackupFilesDir, "trajectory-device-test-${UUID.randomUUID()}").apply { mkdirs() }
    }
    private val context: Context by lazy {
        object : ContextWrapper(targetContext) {
            override fun getNoBackupFilesDir(): File = fixtureRoot
            override fun getFilesDir(): File = File(fixtureRoot, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(fixtureRoot, "cache").apply { mkdirs() }
        }
    }

    @After
    fun removeOnlyOwnedFixture() {
        val fixture = fixtureRoot.canonicalFile
        check(fixture.parentFile == targetContext.noBackupFilesDir.canonicalFile)
        check(fixture.name.startsWith("trajectory-device-test-"))
        fixture.deleteRecursively()
    }

    @Test
    fun freshReaderReplaysARunWrittenByAClosedWriter() = runBlocking {
        val sessionId = "cold-read-${UUID.randomUUID()}"
        val runId = "run-${UUID.randomUUID()}"
        val root = debugSessionEventLogRoot(context, sessionId)
        try {
            val written = writeRun(sessionId, runId)
            assertTrue("writer produced no durable events", written.isNotEmpty())

            val gateway = gateway()
            val replayed = gateway.replay(sessionId, runId)
            val health = gateway.health(sessionId, runId)

            assertEquals(written.map { it.eventId }, replayed.map { it.eventId })
            assertEquals(written.map { it.sequence }, replayed.map { it.sequence })
            assertEquals(SessionEventLogDurability.DURABLE, health.backendDurability)
            assertEquals(SessionEventLogHealthStatus.COMPLETE, health.status)
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * The regression the review caught: a broken cold read must surface as a failed load with a
     * bounded reason, never as a healthy run that merely looks unexpanded.
     */
    @Test
    fun repositoryColdLoadProducesLoadedRecordsNotAnUnloadedGroup() = runBlocking {
        val sessionId = "cold-load-${UUID.randomUUID()}"
        val runId = "run-${UUID.randomUUID()}"
        val root = debugSessionEventLogRoot(context, sessionId)
        try {
            val written = writeRun(sessionId, runId)
            val repository = repository(sessionId, runId)

            repository.loadRun(sessionId, runId)

            val group = repository.snapshot(sessionId).runGroups.single { it.runId == runId }
            assertTrue("cold load did not mark the group as loaded", group.recordsLoaded)
            assertFalse("cold load reported a failure: ${group.health.safeReasonCode}", group.loadFailed)
            assertEquals(SessionEventLogHealthStatus.COMPLETE, group.health.eventLogStatus)
            assertEquals(written.map { it.sequence }, group.records.mapNotNull { it.sequence })
            // Durable rows only: no live-only duplicates survive a cold read.
            assertTrue(group.records.none { it.isLive })
            assertTrue(repository.snapshot(sessionId).loadingRunIds.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun perSessionRootsStayIsolatedOnDeviceStorage() = runBlocking {
        val first = "iso-a-${UUID.randomUUID()}"
        val second = "iso-b-${UUID.randomUUID()}"
        val roots = listOf(first, second).map { debugSessionEventLogRoot(context, it) }
        try {
            writeRun(first, "run-a")
            writeRun(second, "run-b")
            val gateway = gateway()

            assertTrue(gateway.replay(first, "run-a").isNotEmpty())
            assertTrue(gateway.replay(second, "run-b").isNotEmpty())
            // Reading another session's run through this root must surface nothing.
            assertTrue(gateway.replay(first, "run-b").isEmpty())

            assertTrue(gateway.deleteSessionRoot(first))
            assertFalse(debugSessionEventLogRoot(context, first).exists())
            assertTrue(gateway.replay(second, "run-b").isNotEmpty())
        } finally {
            roots.forEach { it.deleteRecursively() }
        }
    }

    /**
     * Deterministic overlap: the reader is held inside the gateway with a latch, so the clear is
     * guaranteed to start while a read slot is taken. Asserting only that "reads did not crash"
     * would also pass if the clear finished first and every read then failed, which proves nothing
     * about `deleteAllRoots` waiting.
     */
    @Test
    fun clearWaitsForAnInFlightReaderBeforeDeletingRoots() = runBlocking {
        val sessionId = "race-${UUID.randomUUID()}"
        val runId = "run-${UUID.randomUUID()}"
        try {
            val written = writeRun(sessionId, runId)
            val readerEntered = CountDownLatch(1)
            val releaseReader = CountDownLatch(1)
            val gateway = TrajectoryEventLogGateway(
                baseRoot = { debugSessionEventLogBaseRoot(context) },
                rootForSession = { debugSessionEventLogRoot(context, it) },
                readerFactory = { root ->
                    readerEntered.countDown()
                    // Blocks inside the gateway while the read slot is held.
                    releaseReader.await(READER_HOLD_TIMEOUT_SEC, TimeUnit.SECONDS)
                    buildTrajectoryEventLogReader(root)
                },
                ioDispatcher = Dispatchers.IO,
            )

            coroutineScope {
                // Both must run off the runBlocking thread: the latch waits below block it, and a
                // reader scheduled on it would never reach the gateway at all.
                val read = async(Dispatchers.IO) { gateway.replay(sessionId, runId) }
                assertTrue(
                    "reader never entered the gateway",
                    readerEntered.await(READER_HOLD_TIMEOUT_SEC, TimeUnit.SECONDS),
                )

                val clear = async(Dispatchers.IO) { gateway.deleteAllRoots() }
                // The clear must not be able to finish while the read slot is held.
                assertNull(
                    "deleteAllRoots completed while a reader was still in flight",
                    withTimeoutOrNull(CLEAR_BLOCKED_PROBE_MS) { clear.await() },
                )
                assertTrue(
                    "roots were deleted before the reader released",
                    debugSessionEventLogRoot(context, sessionId).exists(),
                )

                releaseReader.countDown()
                // The held read still returns real data; it was never cut off mid-flight.
                assertEquals(written.map { it.eventId }, read.await().map { it.eventId })
                assertTrue("clear did not report success", clear.await())
            }

            assertFalse(debugSessionEventLogBaseRoot(context).exists())
        } finally {
            debugSessionEventLogRoot(context, sessionId).deleteRecursively()
        }
    }

    @Test
    fun eventLogRootIsAppPrivateNoBackupAndOutsideTheBusinessWorkspace() {
        val root = debugSessionEventLogRoot(context, "path-shape")
        val canonicalRoot = root.canonicalFile.toPath()

        assertTrue(canonicalRoot.startsWith(context.noBackupFilesDir.canonicalFile.toPath()))
        assertFalse(canonicalRoot.startsWith(debugWorkspaceRoot(context).canonicalFile.toPath()))
        assertEquals(
            debugSessionEventLogBaseRoot(context).canonicalFile,
            root.canonicalFile.parentFile,
        )
    }

    /** Writes one simulated run through the production builder and closes the writer runtime. */
    private suspend fun writeRun(sessionId: String, runId: String) =
        buildDebugRuntime(
            context = context,
            config = deviceProviderConfig(),
            profile = deviceProfile(),
            streamEnabled = false,
            useSimulatedModel = true,
            workspaceRoot = debugWorkspaceRoot(context),
            sessionEventLogRoot = debugSessionEventLogRoot(context, sessionId),
            sessionContext = "",
            evidenceRecorder = null,
            diagnostics = debugDiagnostics(context, runId, onEntry = {}, onTokenUsage = {}),
            mcpProviders = emptyList(),
            onRawToolTranscript = { _, _ -> },
            userInput = COLD_READ_PROMPT,
        ).let { runtime ->
            runtime.run(AgentRequest(requestId = runId, sessionId = sessionId, input = COLD_READ_PROMPT)).toList()
            val events = runtime.replaySessionEvents(sessionId, runId)
            // Closing the writer is what an app restart leaves behind for the reader.
            runtime.close()
            assertTrue(
                "writer did not persist the query path",
                runtime.querySessionEvents(SessionEventQuery(sessionId, runId)).isNotEmpty() || events.isNotEmpty(),
            )
            events
        }

    private fun gateway() = TrajectoryEventLogGateway(
        baseRoot = { debugSessionEventLogBaseRoot(context) },
        rootForSession = { debugSessionEventLogRoot(context, it) },
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun repository(sessionId: String, runId: String) = DefaultTrajectoryRepository(
        gateway = gateway(),
        readTurns = { requested ->
            if (requested == sessionId) listOf(deviceTurn(sessionId, runId)) else emptyList()
        },
        ioScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
    )

    private fun deviceTurn(sessionId: String, runId: String) = DebugSessionTurn(
        turnId = runId,
        requestId = runId,
        runId = runId,
        sessionId = sessionId,
        scene = "debug-console",
        userText = COLD_READ_PROMPT,
        startedAtEpochMillis = 1L,
        completedAtEpochMillis = 2L,
        finalStatus = "COMPLETED",
        finalResponse = "done",
        tools = emptyList(),
    )

    private fun deviceProfile() = DebugProviderProfile(
        id = "device-sim",
        label = "device-sim",
        provider = DebugProvider.OPENAI,
        apiKey = "sk-test",
        model = "fixture",
        baseUrl = "https://example.test/v1",
    )

    private fun deviceProviderConfig(): DebugProviderConfig {
        val profile = deviceProfile()
        return DebugProviderConfig(
            values = emptyMap(),
            sourcePath = "androidTest",
            expectedPaths = emptyList(),
            profiles = listOf(profile),
            defaultProfileId = profile.id,
            activeProfileId = profile.id,
            manualProfileEnabled = false,
        )
    }
}

private const val COLD_READ_PROMPT = "finish with done cold read acceptance"
private const val READER_HOLD_TIMEOUT_SEC = 20L

/**
 * How long the clear is given to prove it is blocked. Only an upper bound on a negative
 * assertion, so a slow device makes the test more conservative rather than flaky.
 */
private const val CLEAR_BLOCKED_PROBE_MS = 1_500L
