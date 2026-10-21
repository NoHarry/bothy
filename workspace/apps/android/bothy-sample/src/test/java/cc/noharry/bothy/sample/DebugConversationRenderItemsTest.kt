package cc.noharry.bothy.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugConversationRenderItemsTest {
    @Test
    fun contextToolGroupRetainsPerToolDetailsForNestedDisclosure() {
        val first = contextTool(
            id = 1,
            toolName = "read_file",
            arguments = """{"path":"/workspace/index.json"}""",
            result = """{"content":"one"}""",
        )
        val second = contextTool(
            id = 2,
            toolName = "read_file",
            arguments = """{"path":"/workspace/tools.json"}""",
            result = """{"content":"two"}""",
        )

        val group = listOf<DebugConversationItem>(first, second)
            .toRenderItems()
            .single() as DebugConversationRenderItem.ContextToolGroup

        assertEquals("Read 2 files", group.summaryLabel())
        assertEquals("Read index.json", group.tools[0].contextLookupLabel())
        assertEquals("Read tools.json", group.tools[1].contextLookupLabel())
        assertTrue(group.tools[0].argumentsJson.orEmpty().contains("index.json"))
        assertTrue(group.tools[0].resultJson.orEmpty().contains("one"))
        assertTrue(group.tools[1].argumentsJson.orEmpty().contains("tools.json"))
        assertTrue(group.tools[1].resultJson.orEmpty().contains("two"))
    }

    @Test
    fun consecutiveBusinessToolsCollapseIntoBusinessGroup() {
        val first = businessTool(1, "web_fetch")
        val second = businessTool(2, "command_navi_CalRoute")
        val third = businessTool(3, "web_fetch")

        val items = listOf<DebugConversationItem>(first, second, third).toRenderItems()

        assertEquals(1, items.size)
        val group = items.single() as DebugConversationRenderItem.BusinessToolGroup
        assertEquals(3, group.tools.size)
        assertEquals("Used 3 tools", group.summaryLabel())
    }

    @Test
    fun singleBusinessToolStaysSingleRow() {
        val tool = businessTool(1, "web_fetch")
        val items = listOf<DebugConversationItem>(tool).toRenderItems()
        assertEquals(1, items.size)
        assertTrue(items.single() is DebugConversationRenderItem.Single)
    }

    @Test
    fun consecutiveRuntimePhasesCollapseIntoRuntimeGroup() {
        val intake = DebugConversationItem.RuntimePhaseItem(1, "Runtime intake", "req")
        val result = DebugConversationItem.RuntimePhaseItem(2, "Runtime result COMPLETED", "ok")
        val evidence = DebugConversationItem.MessageItem(
            id = 3,
            role = DebugTranscriptRole.RUNTIME,
            content = "Runtime MCP evidence\npath=/tmp",
            collapsed = true,
        )

        val items = listOf<DebugConversationItem>(intake, result, evidence).toRenderItems()
        assertEquals(1, items.size)
        val group = items.single() as DebugConversationRenderItem.RuntimePhaseGroup
        assertEquals(3, group.phases.size)
        assertTrue(group.summaryLabel().startsWith("Runtime · 3 steps"))
    }

    @Test
    fun statusMessagesAreNotProjectedIntoTimeline() {
        val items = listOf(
            DebugConversationItem.TurnHeader(1, "You", "hi", "hi"),
            DebugConversationItem.MessageItem(2, DebugTranscriptRole.STATUS, "Models loaded: 3", collapsed = true),
            DebugConversationItem.MessageItem(3, DebugTranscriptRole.STATUS, "Worked for 12s", collapsed = true),
            DebugConversationItem.MessageItem(4, DebugTranscriptRole.ASSISTANT, "done"),
        ).toRenderItems()

        assertEquals(2, items.size)
        assertTrue(items[0] is DebugConversationRenderItem.Single)
        assertTrue((items[0] as DebugConversationRenderItem.Single).item is DebugConversationItem.TurnHeader)
        assertTrue((items[1] as DebugConversationRenderItem.Single).item is DebugConversationItem.MessageItem)
        assertFalse(
            items.any {
                it is DebugConversationRenderItem.Single &&
                    (it.item as? DebugConversationItem.MessageItem)?.role == DebugTranscriptRole.STATUS
            },
        )
    }

    @Test
    fun turnDividersSeparateUserTurnsAfterFirst() {
        val items = listOf(
            DebugConversationItem.TurnHeader(1, "You", "first", "first"),
            DebugConversationItem.MessageItem(2, DebugTranscriptRole.ASSISTANT, "a"),
            DebugConversationItem.TurnHeader(3, "You", "second", "second"),
            DebugConversationItem.MessageItem(4, DebugTranscriptRole.ASSISTANT, "b"),
        ).toRenderItems()

        assertEquals(5, items.size)
        assertTrue(items[0] is DebugConversationRenderItem.Single)
        assertTrue(items[1] is DebugConversationRenderItem.Single)
        assertTrue(items[2] is DebugConversationRenderItem.TurnDivider)
        assertEquals(3L, (items[2] as DebugConversationRenderItem.TurnDivider).beforeTurnId)
        assertTrue(items[3] is DebugConversationRenderItem.Single)
        assertTrue(items[4] is DebugConversationRenderItem.Single)
    }

    @Test
    fun thinkingRemainsSingleCollapsedByDefaultInProjection() {
        val thinking = DebugConversationItem.MessageItem(
            id = 1,
            role = DebugTranscriptRole.THINKING,
            content = "I will call the thermostat tool next",
            collapsed = true,
        )
        val items = listOf<DebugConversationItem>(thinking).toRenderItems()
        val single = items.single() as DebugConversationRenderItem.Single
        val msg = single.item as DebugConversationItem.MessageItem
        assertEquals(DebugTranscriptRole.THINKING, msg.role)
        assertTrue(msg.collapsed)
    }

    @Test
    fun streamingThinkingIdTracksLiveTailUntilAssistantOrTool() {
        val turn = DebugConversationItem.TurnHeader(1, "You", "hi", "hi")
        val thinking = DebugConversationItem.MessageItem(
            id = 2,
            role = DebugTranscriptRole.THINKING,
            content = "planning",
            collapsed = true,
        )
        val assistant = DebugConversationItem.MessageItem(
            id = 3,
            role = DebugTranscriptRole.ASSISTANT,
            content = "done",
        )
        val tool = DebugConversationItem.ToolCallItem(
            id = 4,
            toolName = "read_file",
            argumentsJson = null,
            resultJson = null,
            status = DebugToolCallStatus.RUNNING,
            startedAt = 1L,
        )

        assertEquals(2L, streamingThinkingItemId(listOf(turn, thinking), isRunning = true))
        assertNull(streamingThinkingItemId(listOf(turn, thinking), isRunning = false))
        assertNull(streamingThinkingItemId(listOf(turn, thinking, assistant), isRunning = true))
        assertNull(streamingThinkingItemId(listOf(turn, thinking, tool), isRunning = true))
    }

    @Test
    fun thinkingDisplayLineUsesTailWhileStreaming() {
        val long = "a".repeat(20) + "TAIL_MARKER"
        val streaming = thinkingDisplayLine(long, streaming = true, collapsed = true, maxChars = 12)
        assertTrue(streaming.startsWith("Thinking · "))
        assertTrue(streaming.endsWith("TAIL_MARKER") || streaming.contains("TAIL_MARKER"))
        assertTrue(streaming.contains("…") || long.length <= 12)

        val done = thinkingDisplayLine(long, streaming = false, collapsed = true, maxChars = 12)
        assertTrue(done.startsWith("Thinking · "))
        assertTrue(done.endsWith("…"))
        assertFalse(done.contains("TAIL_MARKER"))
    }

    @Test
    fun htmlWriteEmitsInlineArtifactPreviewAfterToolRow() {
        val write = DebugConversationItem.ToolCallItem(
            id = 1,
            toolName = "write_file",
            argumentsJson = """{"path":"/scratch/a.html","content":"<html>a</html>"}""",
            resultJson = """{"ok":true,"path":"/scratch/a.html","bytes":13}""",
            status = DebugToolCallStatus.DONE,
            startedAt = 1,
            finishedAt = 2,
        )

        val items = listOf<DebugConversationItem>(write).toRenderItems()

        assertEquals(2, items.size)
        assertTrue(items[0] is DebugConversationRenderItem.Single)
        val artifact = items[1] as DebugConversationRenderItem.HtmlArtifact
        assertEquals("/scratch/a.html", artifact.preview.path)
        assertEquals("<html>a</html>", artifact.preview.html)
        assertEquals("html-artifact-1", artifact.key)
    }

    @Test
    fun nonHtmlOrFailedWriteEmitsNoArtifactPreview() {
        val failed = DebugConversationItem.ToolCallItem(
            id = 1,
            toolName = "write_file",
            argumentsJson = """{"path":"/scratch/a.html","content":"<html>a</html>"}""",
            resultJson = """{"ok":false,"code":"X"}""",
            status = DebugToolCallStatus.FAILED,
            startedAt = 1,
            finishedAt = 2,
        )
        val text = DebugConversationItem.ToolCallItem(
            id = 2,
            toolName = "write_file",
            argumentsJson = """{"path":"/scratch/a.txt","content":"plain"}""",
            resultJson = """{"ok":true,"path":"/scratch/a.txt","bytes":5}""",
            status = DebugToolCallStatus.DONE,
            startedAt = 2,
            finishedAt = 3,
        )

        val items = listOf<DebugConversationItem>(failed, text).toRenderItems()

        assertTrue(items.none { it is DebugConversationRenderItem.HtmlArtifact })
    }

    private fun contextTool(
        id: Long,
        toolName: String,
        arguments: String,
        result: String,
    ): DebugConversationItem.ToolCallItem =
        DebugConversationItem.ToolCallItem(
            id = id,
            toolName = toolName,
            argumentsJson = "tool=$toolName\narguments=$arguments",
            resultJson = "tool=$toolName\nok=true\nresult=$result",
            status = DebugToolCallStatus.DONE,
            startedAt = id,
            finishedAt = id + 1,
        )

    private fun businessTool(id: Long, toolName: String): DebugConversationItem.ToolCallItem =
        DebugConversationItem.ToolCallItem(
            id = id,
            toolName = toolName,
            argumentsJson = "tool=$toolName\narguments={}",
            resultJson = "tool=$toolName\nok=true\nresult={}",
            status = DebugToolCallStatus.DONE,
            startedAt = id,
            finishedAt = id + 1,
        )
}
