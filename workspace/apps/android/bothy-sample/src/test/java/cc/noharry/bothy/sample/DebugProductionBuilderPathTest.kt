@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.bothy.kernel.api.AgentRequest
import cc.noharry.bothy.kernel.api.AgentRuntime
import cc.noharry.bothy.kernel.api.SessionEventLogDurability
import cc.noharry.bothy.kernel.api.SessionEventPayload
import cc.noharry.bothy.kernel.api.SessionEventQuery
import cc.noharry.bothy.kernel.api.SessionEventType
import cc.noharry.bothy.kernel.core.RUN_CONTEXT_HEADER
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Behavioral production path: exercises [buildDebugRuntime] the same way
 * [DebugRuntimeSessionRunner] does — decision from request text, same bytes as
 * [AgentRequest.input], simulated model finish-only run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DebugProductionBuilderPathTest {

    @Test
    fun ordinaryPromptDoesNotWriteProbeArtifacts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reliability = sampleAgentReliabilityRoot(context)
        reliability.deleteRecursively()

        val text = "finish with done ordinary demo"
        val runtime = productionBuildDebugRuntime(context, text)
        runtime.run(productionRequest(text, "ord-1")).toList()

        assertFalse(
            "ordinary prompt must not create agent-reliability probe runs",
            hasProbeArtifacts(reliability),
        )
    }

    @Test
    fun exactBenchPromptBindsCaseAndPromptHashViaProductionBuilder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reliability = sampleAgentReliabilityRoot(context)
        reliability.deleteRecursively()

        val text = SAMPLE_DEBUG_CASE_PACK_PROMPT
        assertTrue(productionBenchCaseBoundDecision(text))
        val runtime = productionBuildDebugRuntime(context, text)
        val request = productionRequest(text, "bench-1")
        assertTrue(request.input == text)
        assertTrue(request.input == SAMPLE_DEBUG_CASE_PACK_PROMPT)
        runtime.run(request).toList()

        val runDir = findProbeRunDir(reliability)
        val probes = File(runDir, "probes.jsonl").readText()
        val meta = File(runDir, "run-meta.json").readText()
        assertTrue(probes.contains("case.bound"))
        assertTrue(meta.contains("sample.debug.v1"))
        assertTrue(meta.contains(SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256))
        assertTrue(meta.contains("bench_core"))
        // Stored hash must equal hash of actual request input bytes.
        assertTrue(sha256HexUtf8(request.input) == SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256)
    }

    @Test
    fun whitespacePaddedBenchPromptDoesNotBindViaProductionBuilder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reliability = sampleAgentReliabilityRoot(context)
        reliability.deleteRecursively()

        val text = "  $SAMPLE_DEBUG_CASE_PACK_PROMPT  "
        assertFalse(productionBenchCaseBoundDecision(text))
        val runtime = productionBuildDebugRuntime(context, text)
        val request = productionRequest(text, "pad-1")
        assertTrue(request.input == text)
        runtime.run(request).toList()

        assertFalse(
            "padded prompt must not enable case-bound evidence",
            hasProbeArtifacts(reliability),
        )
        assertTrue(sha256HexUtf8(request.input) != SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256)
    }

    @Test
    fun nonExactPromptDoesNotBindEvenIfSomeoneFlipsFlagManually() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            // Fail-closed: builder with benchCaseBound=true still requires exact prompt bytes.
            buildDebugRuntime(
                context = context,
                config = fixtureProviderConfig(),
                requestedProfile = fixtureProfile(),
                streamEnabled = false,
                useSimulatedModel = true,
                workspaceRoot = debugWorkspaceRoot(context),
                sessionEventLogRoot = debugSessionEventLogRoot(context, DEBUG_SESSION_ID),
                sessionContext = "",
                evidenceRecorder = null,
                diagnostics = noopDiagnostics(context),
                mcpProviders = emptyList(),
                                onRawToolTranscript = { _, _ -> },
                userInput = "  $SAMPLE_DEBUG_CASE_PACK_PROMPT  ",
                benchCaseBound = true,
            )
            org.junit.Assert.fail("must refuse non-exact prompt when benchCaseBound=true")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun virtualWorkspaceRunStillWritesDurableEventLogUnderTheConfiguredRoot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionId = "builder-durable-session"
        val root = debugSessionEventLogRoot(context, sessionId)
        root.deleteRecursively()

        val text = "finish with done durable eventlog"
        val runtime = productionBuildDebugRuntime(context, text, sessionId)
        runtime.run(productionRequest(text, "durable-1", sessionId)).toList()

        val health = runtime.getSessionEventLogHealth(sessionId, "durable-1")
        assertTrue(runtime.querySessionEvents(SessionEventQuery(sessionId, "durable-1")).isNotEmpty())
        assertTrue("expected DURABLE backend, got ${health.backendDurability}", health.backendDurability == SessionEventLogDurability.DURABLE)
        assertTrue(
            "expected event files under the configured root",
            root.walkTopDown().any { it.name.endsWith(".jsonl") && it.path.contains("session-events") },
        )
        // Semantic VFS must not shadow the EventLog: business workspace keeps no /sessions stream.
        assertFalse(
            debugWorkspaceRoot(context).walkTopDown().any { it.name == "events.jsonl" },
        )
    }

    @Test
    fun canonicalModelRequestContainsTheProviderVisibleDebugPromptAndSessionContext() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionId = "builder-context-session"
        val runId = "builder-context-run"
        val root = debugSessionEventLogRoot(context, sessionId)
        root.deleteRecursively()
        val sessionContext =
            "- user=还是有点热 | assistant=需要我为您打开空调设置页面吗？ | " +
                "runStatus=COMPLETED | completion=PARTIAL"
        val previous = DebugPreviousExchange(sessionId, "music-offer", "有什么新歌", "要播放新歌吗？", "COMPLETED", "SUCCEEDED")
        val runtime = buildDebugRuntime(
            context = context,
            config = fixtureProviderConfig(),
            requestedProfile = fixtureProfile(),
            streamEnabled = false,
            useSimulatedModel = true,
            workspaceRoot = debugWorkspaceRoot(context),
            sessionEventLogRoot = root,
            sessionContext = sessionContext,
            evidenceRecorder = null,
            diagnostics = noopDiagnostics(context),
            mcpProviders = emptyList(),
            onRawToolTranscript = { _, _ -> },
            userInput = "可以",
            previousExchange = previous,
        )

        runtime.run(productionRequest("可以", runId, sessionId)).toList()

        val payload = runtime.replaySessionEvents(sessionId, runId)
            .single { it.type == SessionEventType.MODEL_REQUEST }
            .payload as SessionEventPayload.ModelRequest
        // The system prompt holds only what is the same every run, so the provider's prompt cache keeps it.
        assertTrue(payload.systemPrompt.contains("You are 小博"))
        assertFalse(payload.systemPrompt.contains("需要我为您打开空调设置页面吗？"))
        assertFalse(payload.systemPrompt.contains(previous.prompt(true)))
        // The input comes first, exactly as sent; the session context and previous exchange follow it as one message.
        val messages = payload.messages as cc.noharry.bothy.kernel.api.SessionJson.Array
        val contents = messages.values.map {
            ((it as cc.noharry.bothy.kernel.api.SessionJson.Object).values["content"] as cc.noharry.bothy.kernel.api.SessionJson.Text).value
        }
        assertEquals(2, contents.size)
        assertEquals("可以", contents[0])
        assertTrue(contents[1].startsWith(RUN_CONTEXT_HEADER))
        assertTrue(contents[1].contains("需要我为您打开空调设置页面吗？"))
        assertTrue(contents[1].contains("completion=PARTIAL"))
        assertTrue(contents[1].beforeThinkingLine().endsWith(previous.prompt(true)))
        assertFalse(contents[1].contains("You are 小博"))
        runtime.close()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DebugClearDataProductionPathTest {
    @Test
    fun productionBuilderThenCoordinatorClearThenProductionBuilderRecreates() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reliability = sampleAgentReliabilityRoot(context)
        val secrets = File(context.filesDir, "provider-secrets.json").also {
            it.writeText("""{"key":"keep-me"}""")
        }
        reliability.deleteRecursively()

        // 1) Production builder write
        runProductionBench(context, "clear-r1")
        assertTrue(hasProbeArtifacts(reliability))

        // 2) Production coordinator entry
        val coordinator = DebugSessionCoordinator(DebugTranscriptStore())
        val result = coordinator.clearAllRuntimeData(context)
        assertTrue(result.ok)
        assertFalse(reliability.exists())
        assertTrue(secrets.isFile)
        assertTrue(secrets.readText().contains("keep-me"))

        // 3) Production builder recreate
        runProductionBench(context, "clear-r2")
        val runDir = findProbeRunDir(reliability)
        assertTrue(File(runDir, "probes.jsonl").isFile)
        assertTrue(File(runDir, "run-meta.json").readText().contains(SAMPLE_DEBUG_CASE_PACK_PROMPT_SHA256))
    }

    private suspend fun runProductionBench(context: Context, requestId: String) {
        val text = SAMPLE_DEBUG_CASE_PACK_PROMPT
        val runtime = productionBuildDebugRuntime(context, text)
        runtime.run(productionRequest(text, requestId)).toList()
    }
}

/** Same decision + builder shape as [DebugRuntimeSessionRunner] production intake. */
internal fun productionBuildDebugRuntime(
    context: Context,
    userInput: String,
    sessionId: String = DEBUG_SESSION_ID,
): AgentRuntime {
    val benchCaseBound = productionBenchCaseBoundDecision(userInput)
    return buildDebugRuntime(
        context = context,
        config = fixtureProviderConfig(),
        requestedProfile = fixtureProfile(),
        streamEnabled = false,
        useSimulatedModel = true,
        workspaceRoot = debugWorkspaceRoot(context),
        sessionEventLogRoot = debugSessionEventLogRoot(context, sessionId),
        sessionContext = "",
        evidenceRecorder = null,
        diagnostics = noopDiagnostics(context),
        mcpProviders = emptyList(),
                onRawToolTranscript = { _, _ -> },
        userInput = userInput,
        benchCaseBound = benchCaseBound,
    )
}

internal fun productionRequest(
    userInput: String,
    requestId: String,
    sessionId: String = DEBUG_SESSION_ID,
): AgentRequest =
    debugAgentRequest(
        requestId = requestId,
        sessionId = sessionId,
        input = userInput,
        config = fixtureProviderConfig(),
        profile = fixtureProfile(),
    )

internal fun fixtureProfile(): DebugProviderProfile =
    DebugProviderProfile(
        id = "fixture-sim",
        label = "fixture",
        provider = DebugProvider.OPENAI,
        apiKey = "sk-test",
        model = "fixture",
        baseUrl = "https://example.test/v1",
    )

internal fun fixtureProviderConfig(): DebugProviderConfig {
    val profile = fixtureProfile()
    return DebugProviderConfig(
        values = emptyMap(),
        sourcePath = "test",
        expectedPaths = emptyList(),
        profiles = listOf(profile),
        defaultProfileId = profile.id,
        activeProfileId = profile.id,
        manualProfileEnabled = false,
    )
}

internal fun noopDiagnostics(context: Context): DebugDiagnostics =
    debugDiagnostics(
        context = context,
        requestId = "test-diag",
        onEntry = {},
        onTokenUsage = {},
    )

internal fun hasProbeArtifacts(reliabilityRoot: File): Boolean {
    val runs = File(reliabilityRoot, "runs")
    if (!runs.isDirectory) return false
    return runs.listFiles()?.any { File(it, "probes.jsonl").isFile } == true
}

internal fun findProbeRunDir(reliabilityRoot: File): File {
    val runs = File(reliabilityRoot, "runs")
    assertTrue("expected runs under $reliabilityRoot", runs.isDirectory)
    val withProbes = runs.listFiles()?.firstOrNull { File(it, "probes.jsonl").isFile }
    assertTrue("expected probes under $reliabilityRoot", withProbes != null)
    return withProbes!!
}

private fun assertTrue(message: String, condition: Boolean) {
    org.junit.Assert.assertTrue(message, condition)
}
