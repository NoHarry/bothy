package cc.noharry.agent.appserver.session
import android.app.Application
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.ConfirmationStatus
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.protocol.foldedByDefault
import cc.noharry.agent.appserver.runtime.handleAgentEvent
import cc.noharry.agent.kernel.api.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs under Robolectric for the console's labels; the default (English) resources give the strings asserted here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TranscriptTest {
    private val resources: Resources = ApplicationProvider.getApplicationContext<Application>().resources

    @Test
    fun trimForDisplayBoundsLargeTranscriptBodies() {
        val output = "x".repeat(LONG_BODY_CHARS).trimForDisplay()

        assertEquals(MAX_EXPECTED_UI_BODY_CHARS, output.length)
        assertTrue(output.endsWith("[truncated in debug UI]"))
    }

    @Test
    fun confirmationCardUpdatesInPlaceAfterDecision() {
        val store = TranscriptStore()
        store.openTurn("fetch example")

        store.appendConfirmationRequired(
            pendingId = "pending-1",
            domain = "example.com",
            mode = ConfirmationMode.ALLOW_CONFIRMED_DOMAINS,
        )
        store.updateConfirmation("pending-1", ConfirmationStatus.APPROVED)

        val confirmations = store.snapshot().filterIsInstance<ConversationItem.ConfirmationItem>()
        assertEquals(1, confirmations.size)
        assertEquals("example.com", confirmations.single().domain)
        assertEquals(ConfirmationStatus.APPROVED, confirmations.single().status)
        assertTrue(confirmations.single().resolvedAt != null)
    }

    @Test
    fun thinkingDefaultsToCollapsed() {
        val thinking = ConversationItem.MessageItem(
            id = 5,
            role = TranscriptRole.THINKING,
            content = "reasoning",
        )
        assertTrue(thinking.foldedByDefault)
    }

    @Test
    fun parsesToolResultStatusFromOkLine() {
        assertEquals(ToolCallStatus.DONE, parseToolResultStatus("tool=x\nok=true\nresult={}"))
        assertEquals(ToolCallStatus.FAILED, parseToolResultStatus("tool=x\nok=false\nresult={}"))
        assertEquals(ToolCallStatus.UNKNOWN, parseToolResultStatus("tool=x\nresult={}"))
    }

    @Test
    fun runtimeResultBodyWarnsWhenToolsHaveIssues() {
        val decorated = decorateRuntimeResultBody(
            body = "final\\nanswer",
            issues = ToolIssueSummary(failed = 1, unknown = 2),
        )

        assertTrue(decorated.contains("Tool execution did not fully pass."))
        assertTrue(decorated.contains("Failed tool calls: 1"))
        assertTrue(decorated.contains("Tool calls without captured raw result: 2"))
        assertTrue(decorated.endsWith("final\nanswer"))
    }

    @Test
    fun publicToolFinishPreventsFalseUnknownWhenRawPostIsMissing() {
        val store = TranscriptStore()
        store.openTurn("go")
        store.recordPublicToolCallStarted("command_navi_SearchParking")
        store.appendRawToolEvent("Raw tool arguments", "tool=command_navi_SearchParking\narguments={}")
        store.recordPublicToolCallFinished("command_navi_SearchParking")

        store.markPendingToolCallsUnknown()

        val tool = store.snapshot().filterIsInstance<ConversationItem.ToolCallItem>().single()
        assertEquals(ToolCallStatus.DONE, tool.status)
        assertTrue(tool.resultJson.orEmpty().contains("NativeToolCallFinished"))
    }

    @Test
    fun rawToolResultOverridesPublicFinishFallback() {
        val store = TranscriptStore()
        store.openTurn("go")
        store.recordPublicToolCallStarted("command_ac_OpenAc")
        store.appendRawToolEvent("Raw tool arguments", "tool=command_ac_OpenAc\narguments={}")
        store.recordPublicToolCallFinished("command_ac_OpenAc")
        store.appendRawToolEvent("Raw tool result", "tool=command_ac_OpenAc\nok=false\nresult={}")

        val tool = store.snapshot().filterIsInstance<ConversationItem.ToolCallItem>().single()
        assertEquals(ToolCallStatus.FAILED, tool.status)
        assertEquals("tool=command_ac_OpenAc\nok=false\nresult={}", tool.resultJson)
    }

    @Test
    fun publicFinishWithToolCallIdStillAllowsLegacyRawResultFallback() {
        val store = TranscriptStore()
        store.openTurn("go")
        store.recordPublicToolCallStarted("command_ac_OpenAc", "call-ac-1")
        store.appendRawToolEvent("Raw tool arguments", "tool=command_ac_OpenAc\narguments={}")
        store.recordPublicToolCallFinished("command_ac_OpenAc", "call-ac-1")
        store.appendRawToolEvent("Raw tool result", "tool=command_ac_OpenAc\nok=false\nresult={}")

        val tool = store.snapshot().filterIsInstance<ConversationItem.ToolCallItem>().single()
        assertEquals("call-ac-1", tool.toolCallId)
        assertEquals(ToolCallStatus.FAILED, tool.status)
        assertEquals("tool=command_ac_OpenAc\nok=false\nresult={}", tool.resultJson)
    }

    @Test
    fun rawToolArgumentsBeforePublicStartDoNotCreateDuplicateToolRows() {
        val store = TranscriptStore()
        store.openTurn("go")
        store.appendRawToolEvent("Raw tool arguments", "tool=command_navi_CalRoute\narguments={}")
        store.recordPublicToolCallStarted("command_navi_CalRoute")

        val afterStart = store.snapshot().filterIsInstance<ConversationItem.ToolCallItem>()
        assertEquals(1, afterStart.size)
        assertEquals(ToolCallStatus.RUNNING, afterStart.single().status)
        assertEquals("tool=command_navi_CalRoute\narguments={}", afterStart.single().argumentsJson)

        store.recordPublicToolCallFinished("command_navi_CalRoute")
        val afterPublicFinish = store.snapshot().filterIsInstance<ConversationItem.ToolCallItem>().single()
        assertEquals(ToolCallStatus.DONE, afterPublicFinish.status)
        assertTrue(afterPublicFinish.resultJson.orEmpty().contains("NativeToolCallFinished"))

        store.appendRawToolEvent("Raw tool result", "tool=command_navi_CalRoute\nok=true\nresult={}")

        val tool = store.snapshot().filterIsInstance<ConversationItem.ToolCallItem>().single()
        assertEquals(ToolCallStatus.DONE, tool.status)
        assertEquals("tool=command_navi_CalRoute\nok=true\nresult={}", tool.resultJson)
    }

    @Test
    fun toolCallIdPairsSameNameToolRowsOutOfOrder() {
        val store = TranscriptStore()
        store.openTurn("go")
        store.recordPublicToolCallStarted("command_navi_AroundSearch", "call-1")
        store.recordPublicToolCallStarted("command_navi_AroundSearch", "call-2")
        store.appendRawToolEvent(
            "Raw tool arguments",
            "toolCallId=call-2\ntool=command_navi_AroundSearch\narguments={\"keyword\":\"parking\"}",
        )
        store.appendRawToolEvent(
            "Raw tool arguments",
            "toolCallId=call-1\ntool=command_navi_AroundSearch\narguments={\"keyword\":\"home\"}",
        )

        store.recordPublicToolCallFinished("command_navi_AroundSearch", "call-1")
        store.appendRawToolEvent(
            "Raw tool result",
            "toolCallId=call-2\ntool=command_navi_AroundSearch\nok=true\nresult={\"items\":[]}",
        )

        val tools = store.snapshot().filterIsInstance<ConversationItem.ToolCallItem>()
        assertEquals(2, tools.size)
        assertEquals("{\"keyword\":\"home\"}", tools.single { it.toolCallId == "call-1" }.argumentsJson?.substringAfter("arguments="))
        assertTrue(tools.single { it.toolCallId == "call-1" }.resultJson.orEmpty().contains("NativeToolCallFinished"))
        assertEquals(ToolCallStatus.DONE, tools.single { it.toolCallId == "call-2" }.status)
        assertTrue(tools.single { it.toolCallId == "call-2" }.resultJson.orEmpty().contains("\"items\""))
    }

    @Test
    fun runtimeResultIssueSummaryIgnoresInternalToolFailures() {
        val store = TranscriptStore()
        store.openTurn("go")
        store.appendRawToolEvent("Raw tool arguments", "tool=read_file\narguments={}")
        store.appendRawToolEvent("Raw tool result", "tool=read_file\nok=false\nresult={}")

        store.appendRuntimeResult("COMPLETED", "done")

        val result = store.snapshot().filterIsInstance<ConversationItem.RuntimePhaseItem>().single()
        assertEquals("done", result.body)
    }

    @Test
    fun runtimeResultIssueSummaryKeepsBusinessToolFailures() {
        val store = TranscriptStore()
        store.openTurn("go")
        store.appendRawToolEvent("Raw tool arguments", "tool=command_navi_CalRoute\narguments={}")
        store.appendRawToolEvent("Raw tool result", "tool=command_navi_CalRoute\nok=false\nresult={}")

        store.appendRuntimeResult("COMPLETED", "done")

        val result = store.snapshot().filterIsInstance<ConversationItem.RuntimePhaseItem>().single()
        assertTrue(result.body.contains("Failed tool calls: 1"))
    }

    @Test
    fun finalResponseIsVisibleAsAssistantMessage() {
        val store = TranscriptStore()
        store.openTurn("go")

        store.appendFinalResponse("done")

        val messages = store.snapshot().filterIsInstance<ConversationItem.MessageItem>()
        assertEquals(TranscriptRole.ASSISTANT, messages.single().role)
        assertEquals("done", messages.single().content)
    }

    @Test
    fun finalResponseDoesNotDuplicateStreamedAssistantBody() {
        val store = TranscriptStore()
        store.openTurn("go")
        store.appendAssistantDelta("hello world")

        store.appendFinalResponse("hello world")

        val messages = store.snapshot().filterIsInstance<ConversationItem.MessageItem>()
            .filter { it.role == TranscriptRole.ASSISTANT }
        assertEquals(1, messages.size)
        assertEquals("hello world", messages.single().content)
    }

    @Test
    fun finishedEventOnlyUpdatesStatusAfterResultAvailable() {
        val runtimeRows = mutableListOf<String>()

        val status = handleAgentEvent(
            event = AgentEvent.Finished("run-1"),
            appendThinking = {},
            appendAssistant = {},
            appendRuntime = { title, _ -> runtimeRows += title },
            appendError = { _, _ -> },
            appendConfirmation = {},
        )

        assertEquals("Finished run-1", status)
        assertTrue(runtimeRows.isEmpty())
    }

    @Test
    fun confirmationRequiredMapsToInlineTranscriptCallback() {
        val confirmations = mutableListOf<String>()

        val status = handleAgentEvent(
            event = AgentEvent.ConfirmationRequired(pendingConfirmation()),
            appendThinking = {},
            appendAssistant = {},
            appendRuntime = { _, _ -> error("confirmation should not append a runtime phase") },
            appendError = { _, _ -> },
            appendConfirmation = { confirmations += it.pendingId },
        )

        assertEquals(null, status)
        assertEquals(listOf("confirm-1"), confirmations)
    }

    @Test
    fun searchActivityRecordsWebSearchQueryOnToolCard() {
        val store = TranscriptStore()
        store.openTurn("search weather")

        store.recordSearchActivity(
            provider = "Anthropic",
            queryPreview = "广州 天气",
            resultCount = 3,
        )

        val tool = store.snapshot().filterIsInstance<ConversationItem.ToolCallItem>().single()
        assertEquals("web_search", tool.toolName)
        assertEquals("""{"query":"广州 天气"}""", tool.argumentsJson)
        assertEquals(ToolCallStatus.DONE, tool.status)
    }

    @Test
    fun searchActivityOverwritesRedactedPublicWebSearchArgs() {
        val store = TranscriptStore()
        store.openTurn("search")
        store.recordPublicToolCallStarted(
            toolName = "web_search",
            toolCallId = "dsml-web_search-21",
            arguments = """{"redacted":true}""",
        )
        store.recordSearchActivity(
            provider = "hosted",
            queryPreview = "深圳北站 停车场",
            resultCount = null,
        )

        val tool = store.snapshot().filterIsInstance<ConversationItem.ToolCallItem>().single()
        assertEquals("""{"query":"深圳北站 停车场"}""", tool.argumentsJson)
    }

    @Test
    fun consecutiveHotTurnsRemainDistinctInTranscript() {
        val store = TranscriptStore()
        store.openTurn("好热")
        store.openTurn("还是好热")

        val turns = store.snapshot().filterIsInstance<ConversationItem.TurnHeader>()

        assertEquals(2, turns.size)
        assertEquals(listOf("好热", "还是好热"), turns.map { it.fullText })
        assertTrue(turns[0].id < turns[1].id)
    }

    private fun contextTool(id: Long, toolName: String, arguments: String): ConversationItem.ToolCallItem =
        ConversationItem.ToolCallItem(
            id = id,
            toolName = toolName,
            argumentsJson = "tool=$toolName\narguments=$arguments",
            resultJson = "tool=$toolName\nok=true\nresult={}",
            status = ToolCallStatus.DONE,
            startedAt = id,
            finishedAt = id + 1,
        )

    private fun pendingConfirmation(): cc.noharry.agent.kernel.api.PendingConfirmation =
        cc.noharry.agent.kernel.api.PendingConfirmation(
            pendingId = "confirm-1",
            sessionId = "session-1",
            runId = "run-1",
            expiresAtEpochMillis = 1_000,
            idempotencyKey = "confirm-key",
        )

    private companion object {
        const val LONG_BODY_CHARS = 20000
        const val MAX_EXPECTED_UI_BODY_CHARS = 12024
    }
}
