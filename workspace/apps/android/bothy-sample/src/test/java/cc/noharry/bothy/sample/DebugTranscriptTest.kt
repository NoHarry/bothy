package cc.noharry.bothy.sample

import cc.noharry.bothy.kernel.api.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugTranscriptTest {
    @Test
    fun trimForDisplayBoundsLargeTranscriptBodies() {
        val output = "x".repeat(LONG_BODY_CHARS).trimForDisplay()

        assertEquals(MAX_EXPECTED_UI_BODY_CHARS, output.length)
        assertTrue(output.endsWith("[truncated in debug UI]"))
    }

    @Test
    fun turnHeaderProjectionFlattensToCompactEntry() {
        val header = DebugConversationItem.TurnHeader(
            id = 1,
            label = "You",
            preview = "hello",
            fullText = "hello world",
        )

        val compact = header.toCompact()

        assertEquals("You", compact.title)
        assertEquals("hello", compact.body)
    }

    @Test
    fun toolCallCompactShowsStatusMarkerAndToolName() {
        val running = DebugConversationItem.ToolCallItem(
            id = 2,
            toolName = "command_ac_OpenAc",
            argumentsJson = "{}",
            resultJson = null,
            status = DebugToolCallStatus.RUNNING,
            startedAt = 0L,
        ).toCompact()

        assertTrue(running.title.contains("⏳"))
        assertTrue(running.title.contains("command_ac_OpenAc"))

        val done = DebugConversationItem.ToolCallItem(
            id = 3,
            toolName = "command_ac_TemperatureAdjust",
            argumentsJson = "{}",
            resultJson = """{"ok":true}""",
            status = DebugToolCallStatus.DONE,
            startedAt = 0L,
            finishedAt = 10L,
        ).toCompact()

        assertTrue(done.title.contains("✓"))
        assertEquals("""{"ok":true}""", done.body)

        val unknown = DebugConversationItem.ToolCallItem(
            id = 6,
            toolName = "command_navi_SearchAlongRoute",
            argumentsJson = "{}",
            resultJson = null,
            status = DebugToolCallStatus.UNKNOWN,
            startedAt = 0L,
            finishedAt = 10L,
        ).toCompact()

        assertTrue(unknown.title.contains("?"))
        assertEquals("{}", unknown.body)
    }

    @Test
    fun messageCompactReflectsErrorRole() {
        val error = DebugConversationItem.MessageItem(
            id = 4,
            role = DebugTranscriptRole.ERROR,
            content = "boom",
        ).toCompact()

        assertTrue(error.isError)
        assertEquals("boom", error.body)
    }

    @Test
    fun confirmationCardUpdatesInPlaceAfterDecision() {
        val store = DebugTranscriptStore()
        store.openTurn("fetch example")

        store.appendConfirmationRequired(
            pendingId = "pending-1",
            domain = "example.com",
            mode = DebugConfirmationMode.ALLOW_CONFIRMED_DOMAINS,
        )
        store.updateConfirmation("pending-1", DebugConfirmationStatus.APPROVED)

        val confirmations = store.snapshot().filterIsInstance<DebugConversationItem.ConfirmationItem>()
        assertEquals(1, confirmations.size)
        assertEquals("example.com", confirmations.single().domain)
        assertEquals(DebugConfirmationStatus.APPROVED, confirmations.single().status)
        assertTrue(confirmations.single().resolvedAt != null)
    }

    @Test
    fun deniedConfirmationCompactProjectionIsError() {
        val compact = DebugConversationItem.ConfirmationItem(
            id = 7,
            pendingId = "pending-1",
            domain = "example.com",
            mode = DebugConfirmationMode.DENY_ALL,
            status = DebugConfirmationStatus.AUTO_DENIED,
            createdAt = 1L,
            resolvedAt = 2L,
        ).toCompact()

        assertTrue(compact.isError)
        assertEquals("example.com", compact.body)
    }

    @Test
    fun thinkingDefaultsToCollapsed() {
        val thinking = DebugConversationItem.MessageItem(
            id = 5,
            role = DebugTranscriptRole.THINKING,
            content = "reasoning",
        )
        assertTrue(thinking.collapsed)
    }

    @Test
    fun parsesToolResultStatusFromOkLine() {
        assertEquals(DebugToolCallStatus.DONE, parseDebugToolResultStatus("tool=x\nok=true\nresult={}"))
        assertEquals(DebugToolCallStatus.FAILED, parseDebugToolResultStatus("tool=x\nok=false\nresult={}"))
        assertEquals(DebugToolCallStatus.UNKNOWN, parseDebugToolResultStatus("tool=x\nresult={}"))
    }

    @Test
    fun runtimeResultBodyWarnsWhenToolsHaveIssues() {
        val decorated = decorateRuntimeResultBody(
            body = "final\\nanswer",
            issues = DebugToolIssueSummary(failed = 1, unknown = 2),
        )

        assertTrue(decorated.contains("Tool execution did not fully pass."))
        assertTrue(decorated.contains("Failed tool calls: 1"))
        assertTrue(decorated.contains("Tool calls without captured raw result: 2"))
        assertTrue(decorated.endsWith("final\nanswer"))
    }

    @Test
    fun searchActivitySurfacesWebSearchQueryOnToolCard() {
        val store = DebugTranscriptStore()
        store.openTurn("search weather")

        store.recordSearchActivity(
            provider = "Anthropic",
            queryPreview = "广州 天气",
            resultCount = 3,
        )

        val tool = store.snapshot().filterIsInstance<DebugConversationItem.ToolCallItem>().single()
        assertEquals("web_search", tool.toolName)
        assertTrue(tool.argumentsJson.orEmpty().contains("广州 天气"))
        assertEquals(DebugToolCallStatus.DONE, tool.status)
        assertEquals("web_search · 广州 天气", tool.businessToolLineLabel())
    }

    @Test
    fun searchActivityOverwritesRedactedPublicWebSearchArgs() {
        val store = DebugTranscriptStore()
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

        val tool = store.snapshot().filterIsInstance<DebugConversationItem.ToolCallItem>().single()
        assertTrue(tool.argumentsJson.orEmpty().contains("深圳北站 停车场"))
        assertEquals("web_search · 深圳北站 停车场", tool.businessToolLineLabel())
    }

    @Test
    fun publicToolFinishPreventsFalseUnknownWhenRawPostIsMissing() {
        val store = DebugTranscriptStore()
        store.openTurn("go")
        store.recordPublicToolCallStarted("command_navi_SearchParking")
        store.appendRawToolEvent("Raw tool arguments", "tool=command_navi_SearchParking\narguments={}")
        store.recordPublicToolCallFinished("command_navi_SearchParking")

        store.markPendingToolCallsUnknown()

        val tool = store.snapshot().filterIsInstance<DebugConversationItem.ToolCallItem>().single()
        assertEquals(DebugToolCallStatus.DONE, tool.status)
        assertTrue(tool.resultJson.orEmpty().contains("NativeToolCallFinished"))
    }

    @Test
    fun rawToolResultOverridesPublicFinishFallback() {
        val store = DebugTranscriptStore()
        store.openTurn("go")
        store.recordPublicToolCallStarted("command_ac_OpenAc")
        store.appendRawToolEvent("Raw tool arguments", "tool=command_ac_OpenAc\narguments={}")
        store.recordPublicToolCallFinished("command_ac_OpenAc")
        store.appendRawToolEvent("Raw tool result", "tool=command_ac_OpenAc\nok=false\nresult={}")

        val tool = store.snapshot().filterIsInstance<DebugConversationItem.ToolCallItem>().single()
        assertEquals(DebugToolCallStatus.FAILED, tool.status)
        assertEquals("tool=command_ac_OpenAc\nok=false\nresult={}", tool.resultJson)
    }

    @Test
    fun publicFinishWithToolCallIdStillAllowsLegacyRawResultFallback() {
        val store = DebugTranscriptStore()
        store.openTurn("go")
        store.recordPublicToolCallStarted("command_ac_OpenAc", "call-ac-1")
        store.appendRawToolEvent("Raw tool arguments", "tool=command_ac_OpenAc\narguments={}")
        store.recordPublicToolCallFinished("command_ac_OpenAc", "call-ac-1")
        store.appendRawToolEvent("Raw tool result", "tool=command_ac_OpenAc\nok=false\nresult={}")

        val tool = store.snapshot().filterIsInstance<DebugConversationItem.ToolCallItem>().single()
        assertEquals("call-ac-1", tool.toolCallId)
        assertEquals(DebugToolCallStatus.FAILED, tool.status)
        assertEquals("tool=command_ac_OpenAc\nok=false\nresult={}", tool.resultJson)
    }

    @Test
    fun rawToolArgumentsBeforePublicStartDoNotCreateDuplicateToolRows() {
        val store = DebugTranscriptStore()
        store.openTurn("go")
        store.appendRawToolEvent("Raw tool arguments", "tool=command_navi_CalRoute\narguments={}")
        store.recordPublicToolCallStarted("command_navi_CalRoute")

        val afterStart = store.snapshot().filterIsInstance<DebugConversationItem.ToolCallItem>()
        assertEquals(1, afterStart.size)
        assertEquals(DebugToolCallStatus.RUNNING, afterStart.single().status)
        assertEquals("tool=command_navi_CalRoute\narguments={}", afterStart.single().argumentsJson)

        store.recordPublicToolCallFinished("command_navi_CalRoute")
        val afterPublicFinish = store.snapshot().filterIsInstance<DebugConversationItem.ToolCallItem>().single()
        assertEquals(DebugToolCallStatus.DONE, afterPublicFinish.status)
        assertTrue(afterPublicFinish.resultJson.orEmpty().contains("NativeToolCallFinished"))

        store.appendRawToolEvent("Raw tool result", "tool=command_navi_CalRoute\nok=true\nresult={}")

        val tool = store.snapshot().filterIsInstance<DebugConversationItem.ToolCallItem>().single()
        assertEquals(DebugToolCallStatus.DONE, tool.status)
        assertEquals("tool=command_navi_CalRoute\nok=true\nresult={}", tool.resultJson)
    }

    @Test
    fun toolCallIdPairsSameNameToolRowsOutOfOrder() {
        val store = DebugTranscriptStore()
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

        val tools = store.snapshot().filterIsInstance<DebugConversationItem.ToolCallItem>()
        assertEquals(2, tools.size)
        assertEquals("{\"keyword\":\"home\"}", tools.single { it.toolCallId == "call-1" }.argumentsJson?.substringAfter("arguments="))
        assertTrue(tools.single { it.toolCallId == "call-1" }.resultJson.orEmpty().contains("NativeToolCallFinished"))
        assertEquals(DebugToolCallStatus.DONE, tools.single { it.toolCallId == "call-2" }.status)
        assertTrue(tools.single { it.toolCallId == "call-2" }.resultJson.orEmpty().contains("\"items\""))
    }

    @Test
    fun runtimeResultIssueSummaryIgnoresInternalToolFailures() {
        val store = DebugTranscriptStore()
        store.openTurn("go")
        store.appendRawToolEvent("Raw tool arguments", "tool=read_file\narguments={}")
        store.appendRawToolEvent("Raw tool result", "tool=read_file\nok=false\nresult={}")

        store.appendRuntimeResult("COMPLETED", "done")

        val result = store.snapshot().filterIsInstance<DebugConversationItem.RuntimePhaseItem>().single()
        assertEquals("done", result.body)
    }

    @Test
    fun runtimeResultIssueSummaryKeepsBusinessToolFailures() {
        val store = DebugTranscriptStore()
        store.openTurn("go")
        store.appendRawToolEvent("Raw tool arguments", "tool=command_navi_CalRoute\narguments={}")
        store.appendRawToolEvent("Raw tool result", "tool=command_navi_CalRoute\nok=false\nresult={}")

        store.appendRuntimeResult("COMPLETED", "done")

        val result = store.snapshot().filterIsInstance<DebugConversationItem.RuntimePhaseItem>().single()
        assertTrue(result.body.contains("Failed tool calls: 1"))
    }

    @Test
    fun finalResponseIsVisibleAsAssistantMessage() {
        val store = DebugTranscriptStore()
        store.openTurn("go")

        store.appendFinalResponse("done")

        val messages = store.snapshot().filterIsInstance<DebugConversationItem.MessageItem>()
        assertEquals(DebugTranscriptRole.ASSISTANT, messages.single().role)
        assertEquals("done", messages.single().content)
    }

    @Test
    fun finalResponseDoesNotDuplicateStreamedAssistantBody() {
        val store = DebugTranscriptStore()
        store.openTurn("go")
        store.appendAssistantDelta("hello world")

        store.appendFinalResponse("hello world")

        val messages = store.snapshot().filterIsInstance<DebugConversationItem.MessageItem>()
            .filter { it.role == DebugTranscriptRole.ASSISTANT }
        assertEquals(1, messages.size)
        assertEquals("hello world", messages.single().content)
    }

    @Test
    fun finishedEventOnlyUpdatesStatusAfterResultAvailable() {
        val runtimeRows = mutableListOf<String>()

        val status = handleDebugAgentEvent(
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

        val status = handleDebugAgentEvent(
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
    fun consecutiveHotTurnsRemainDistinctInTranscript() {
        val store = DebugTranscriptStore()
        store.openTurn("好热")
        store.openTurn("还是好热")

        val turns = store.snapshot().filterIsInstance<DebugConversationItem.TurnHeader>()
        val renderKeys = store.snapshot().toRenderItems().map { it.key }

        assertEquals(2, turns.size)
        assertEquals(listOf("好热", "还是好热"), turns.map { it.fullText })
        assertTrue(turns[0].id < turns[1].id)
        assertEquals(renderKeys.distinct(), renderKeys)
    }

    @Test
    fun renderProjectionGroupsConsecutiveContextLookupTools() {
        val read = contextTool(1, "read_file", """{"path":"/workspace/harness.yaml"}""")
        val grep = contextTool(2, "grep", """{"pattern":"debug","path":"/workspace/trace"}""")
        val business = contextTool(3, "command_navi_CalRoute", """{"destination":"home"}""")

        val renderItems = listOf<DebugConversationItem>(read, grep, business).toRenderItems()

        assertEquals(2, renderItems.size)
        val group = renderItems.first() as DebugConversationRenderItem.ContextToolGroup
        assertEquals("Read 1 file and searched code", group.summaryLabel())
        assertEquals("Read harness.yaml", group.tools[0].contextLookupLabel())
        assertEquals("Searched for debug in trace", group.tools[1].contextLookupLabel())
        // Single business tool stays ungrouped.
        assertTrue(renderItems.last() is DebugConversationRenderItem.Single)
    }

    @Test
    fun renderProjectionSplitsContextLookupGroupsAroundBusinessTools() {
        val firstRead = contextTool(1, "read_file", """{"path":"/workspace/one.md"}""")
        val business = contextTool(2, "command_media_PlaySong", """{"artist":"Jay"}""")
        val secondRead = contextTool(3, "read_file", """{"path":"/workspace/two.md"}""")

        val groups = listOf<DebugConversationItem>(firstRead, business, secondRead)
            .toRenderItems()
            .filterIsInstance<DebugConversationRenderItem.ContextToolGroup>()

        assertEquals(2, groups.size)
        assertEquals("Read one.md", groups[0].tools.single().contextLookupLabel())
        assertEquals("Read two.md", groups[1].tools.single().contextLookupLabel())
    }

    @Test
    fun renderProjectionGroupsConsecutiveBusinessTools() {
        val a = contextTool(1, "command_media_PlaySong", """{"artist":"A"}""")
        val b = contextTool(2, "command_navi_CalRoute", """{"destination":"home"}""")
        val group = listOf<DebugConversationItem>(a, b)
            .toRenderItems()
            .single() as DebugConversationRenderItem.BusinessToolGroup
        assertEquals(2, group.tools.size)
        assertEquals("Used 2 tools", group.summaryLabel())
    }

    private fun contextTool(id: Long, toolName: String, arguments: String): DebugConversationItem.ToolCallItem =
        DebugConversationItem.ToolCallItem(
            id = id,
            toolName = toolName,
            argumentsJson = "tool=$toolName\narguments=$arguments",
            resultJson = "tool=$toolName\nok=true\nresult={}",
            status = DebugToolCallStatus.DONE,
            startedAt = id,
            finishedAt = id + 1,
        )

    private fun pendingConfirmation(): cc.noharry.bothy.kernel.api.PendingConfirmation =
        cc.noharry.bothy.kernel.api.PendingConfirmation(
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
