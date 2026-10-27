package cc.noharry.bothy.app.runtime
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.bothy.app.model.ProviderConfig
import cc.noharry.bothy.app.session.ConfirmationMode
import cc.noharry.bothy.app.session.SessionCoordinator
import cc.noharry.bothy.app.session.SessionTurn
import cc.noharry.bothy.app.session.TranscriptStore
import cc.noharry.bothy.app.skills.loadRuntimeSkills
import cc.noharry.bothy.app.skills.saveRuntimeSkill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RuntimeStartupFailureTest {
    @Test fun constructionCancellationPropagatesWithoutFalseFailureEvidence() {
        val request = AgentRequest(requestId = "cancelled-build", sessionId = "cancel-session", input = "hello")
        val recorders = multiSessionRecorders(request)
        var cancelled = false
        var failed = false
        assertThrows(CancellationException::class.java) {
            runBlocking { constructRuntime(
                RuntimeRunScope(request.requestId, request.sessionId), recorders,
                SessionCoordinator(TranscriptStore()), null,
                onFailure = { failed = true }, onCancelled = { cancelled = true },
            ) { throw CancellationException("cancel build") } }
        }
        assertTrue(cancelled)
        assertFalse(failed)
        assertNull(recorders.runEvidenceRecorder.snapshot().finalStatus)
    }

    @Test fun realConstructionFailureDoesNotCrashOrConsumeAdmissionAndNextRunCompletes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf("debug_runtime_skills", "debug_basic_tools").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        loadRuntimeSkills(context).forEach { saveRuntimeSkill(context, it.id, true) }
        saveBasicTool(context, "read_file", false)
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Unconfined)
        val transcript = TranscriptStore()
        val coordinator = SessionCoordinator(transcript)
        val store = coordinator.ensure(context)
        // Earlier turns fill the session context, so the preloaded skills no longer fit and must be read from the
        // workspace, which read_file being off forbids: construction fails for real.
        repeat(8) { index ->
            store.appendTurn(
                SessionTurn(
                    "turn-$index", "request-$index", "run-$index", coordinator.activeSessionId, "debug",
                    "第 $index 次问路线和天气，" + "顺便聊聊周末去哪里玩".repeat(16), index * 1_000L, index * 1_000L + 500L,
                    "COMPLETED", "已经查好了：" + "明天多云转晴，适合出门走走".repeat(28), emptyList(),
                    completionOutcome = "SUCCEEDED",
                ),
            )
        }
        val statuses = mutableListOf<String>()
        val runner = RuntimeSessionRunner(
            scope, transcript, coordinator, { null }, ProviderConfig::load,
            {}, { statuses += it }, { false }, { true }, { emptyList() }, { emptyList() },
            { "non-stream" }, { "fixture" }, { ConfirmationMode.ASK_EVERY_TIME }, {},
        )
        try {
            repeat(2) {
                runner.start(context, "$MIXED_BROADCAST_TEXT，下周再去成都玩三天，帮我规划一下行程").join()
                assertFalse(runner.isRunning)
                assertEquals(0, runner.activeRunCount)
                assertNull(runner.runStartedAtEpochMillis)
                val failed = runner.activeRunSnapshots().single()
                assertEquals("FAILED", failed.lifecycleStatus)
                assertEquals("UNKNOWN", failed.logHealth)
                assertEquals("UNKNOWN", failed.completionOutcome)
                assertEquals("FAILED", store.readTurns(coordinator.activeSessionId).last().finalStatus)
                assertTrue(store.readTurns(coordinator.activeSessionId).last().finalResponse.contains("Enable read_file"))
            }
            saveBasicTool(context, "read_file", true)
            runner.start(context, "介绍你自己").join()
            withTimeout(30000) {
                while (runner.activeRunCount != 0) delay(10)
            }
            val turn = store.readTurns(coordinator.activeSessionId).last()
            assertEquals("COMPLETED", turn.finalStatus)
            assertTrue(turn.finalResponse.contains("小博"))
            assertTrue(parent.isActive)
        } finally {
            parent.cancel()
        }
    }
}
