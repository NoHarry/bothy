@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.a2a
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.a2a.server.A2aOutput
import cc.noharry.agent.kernel.api.SessionEventQuery
import cc.noharry.bothy.app.console.fixtureProfile
import cc.noharry.bothy.app.console.fixtureProviderConfig
import cc.noharry.bothy.app.console.noopDiagnostics
import cc.noharry.bothy.app.console.productionRequest
import cc.noharry.bothy.app.runtime.ExecutionEvents
import cc.noharry.bothy.app.runtime.ExecutionToolHook
import cc.noharry.bothy.app.runtime.RuntimeRunScope
import cc.noharry.bothy.app.runtime.buildRuntime
import cc.noharry.bothy.app.runtime.appWorkspaceRoot
import cc.noharry.bothy.app.trajectory.appSessionEventLogRoot
import cc.noharry.bothy.app.voice.tts.TtsAnnouncer
import cc.noharry.bothy.app.voice.tts.TtsConfig
import java.util.UUID
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs the production builder + Kernel with a fixed model fixture, without network or device tools. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class A2aRuntimeTest {
    @Test fun productionBuilderInstallsStatusHookAndKeepsCanonicalLogWithTextOnlySpeech() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionId = "a2a-${UUID.randomUUID()}"
        val request = productionRequest("你是谁", "a2a-test", sessionId)
        val outputs = mutableListOf<A2aOutput>()
        val subscription = ExecutionEvents.subscribe(request.requestId)
        val runtime = buildRuntime(
            context, fixtureProviderConfig(), fixtureProfile(), streamEnabled = true, useSimulatedModel = true,
            workspaceRoot = appWorkspaceRoot(context), sessionEventLogRoot = appSessionEventLogRoot(context, sessionId),
            sessionContext = "", evidenceRecorder = null, diagnostics = noopDiagnostics(context), mcpProviders = emptyList(),
            onRawToolTranscript = { _, _ -> }, userInput = request.input,
            hostHook = ExecutionToolHook(),
        )
        val speech = TtsAnnouncer(this, textOutput = { text, final -> outputs += A2aOutput.Speech(text, final) })
        try {
            runtime.run(request).collect { event ->
                speech.observe(RuntimeRunScope(request.requestId, sessionId), event, TtsConfig()) { true }
                a2aRuntimeOutput(event)?.let { outputs += it }
            }
            while (true) {
                val message = subscription.events.tryReceive().getOrNull() ?: break
                a2aExecutionOutput(message)?.let { outputs += it }
            }
            val statuses = outputs.filterIsInstance<A2aOutput.Status>().filter { it.toolName == "finish" }
            assertEquals(listOf("started", "completed"), statuses.map { it.phase })
            assertEquals(A2aOutput.State.COMPLETED, outputs.filterIsInstance<A2aOutput.Result>().single().state)
            assertTrue(outputs.filterIsInstance<A2aOutput.Speech>().single().final)
            assertTrue(runtime.querySessionEvents(SessionEventQuery(sessionId, request.requestId)).isNotEmpty())
        } finally {
            subscription.close()
            speech.stop()
            runtime.close()
        }
    }
}
