package cc.noharry.bothy.app.console
import cc.noharry.bothy.app.session.ConversationItem
import cc.noharry.bothy.app.session.ToolCallStatus
import cc.noharry.bothy.app.session.TranscriptRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRenderItemsTest {
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

        val group = listOf<ConversationItem>(first, second)
            .toRenderItems()
            .single() as ConversationRenderItem.ContextToolGroup

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

        val items = listOf<ConversationItem>(first, second, third).toRenderItems()

        assertEquals(1, items.size)
        val group = items.single() as ConversationRenderItem.BusinessToolGroup
        assertEquals(3, group.tools.size)
        assertEquals("Used 3 tools", group.summaryLabel())
    }

    @Test
    fun singleBusinessToolStaysSingleRow() {
        val tool = businessTool(1, "web_fetch")
        val items = listOf<ConversationItem>(tool).toRenderItems()
        assertEquals(1, items.size)
        assertTrue(items.single() is ConversationRenderItem.Single)
    }

    @Test
    fun consecutiveRuntimePhasesCollapseIntoRuntimeGroup() {
        val intake = ConversationItem.RuntimePhaseItem(1, "Runtime intake", "req")
        val result = ConversationItem.RuntimePhaseItem(2, "Runtime result COMPLETED", "ok")
        val evidence = ConversationItem.MessageItem(
            id = 3,
            role = TranscriptRole.RUNTIME,
            content = "Runtime MCP evidence\npath=/tmp",
            collapsed = true,
        )

        val items = listOf<ConversationItem>(intake, result, evidence).toRenderItems()
        assertEquals(1, items.size)
        val group = items.single() as ConversationRenderItem.RuntimePhaseGroup
        assertEquals(3, group.phases.size)
        assertTrue(group.summaryLabel().startsWith("Runtime · 3 steps"))
    }

    @Test
    fun statusMessagesAreNotProjectedIntoTimeline() {
        val items = listOf(
            ConversationItem.TurnHeader(1, "You", "hi", "hi"),
            ConversationItem.MessageItem(2, TranscriptRole.STATUS, "Models loaded: 3", collapsed = true),
            ConversationItem.MessageItem(3, TranscriptRole.STATUS, "Worked for 12s", collapsed = true),
            ConversationItem.MessageItem(4, TranscriptRole.ASSISTANT, "done"),
        ).toRenderItems()

        assertEquals(2, items.size)
        assertTrue(items[0] is ConversationRenderItem.Single)
        assertTrue((items[0] as ConversationRenderItem.Single).item is ConversationItem.TurnHeader)
        assertTrue((items[1] as ConversationRenderItem.Single).item is ConversationItem.MessageItem)
        assertFalse(
            items.any {
                it is ConversationRenderItem.Single &&
                    (it.item as? ConversationItem.MessageItem)?.role == TranscriptRole.STATUS
            },
        )
    }

    @Test
    fun turnDividersSeparateUserTurnsAfterFirst() {
        val items = listOf(
            ConversationItem.TurnHeader(1, "You", "first", "first"),
            ConversationItem.MessageItem(2, TranscriptRole.ASSISTANT, "a"),
            ConversationItem.TurnHeader(3, "You", "second", "second"),
            ConversationItem.MessageItem(4, TranscriptRole.ASSISTANT, "b"),
        ).toRenderItems()

        assertEquals(5, items.size)
        assertTrue(items[0] is ConversationRenderItem.Single)
        assertTrue(items[1] is ConversationRenderItem.Single)
        assertTrue(items[2] is ConversationRenderItem.TurnDivider)
        assertEquals(3L, (items[2] as ConversationRenderItem.TurnDivider).beforeTurnId)
        assertTrue(items[3] is ConversationRenderItem.Single)
        assertTrue(items[4] is ConversationRenderItem.Single)
    }

    @Test
    fun thinkingRemainsSingleCollapsedByDefaultInProjection() {
        val thinking = ConversationItem.MessageItem(
            id = 1,
            role = TranscriptRole.THINKING,
            content = "I will call the thermostat tool next",
            collapsed = true,
        )
        val items = listOf<ConversationItem>(thinking).toRenderItems()
        val single = items.single() as ConversationRenderItem.Single
        val msg = single.item as ConversationItem.MessageItem
        assertEquals(TranscriptRole.THINKING, msg.role)
        assertTrue(msg.collapsed)
    }

    @Test
    fun streamingThinkingIdTracksLiveTailUntilAssistantOrTool() {
        val turn = ConversationItem.TurnHeader(1, "You", "hi", "hi")
        val thinking = ConversationItem.MessageItem(
            id = 2,
            role = TranscriptRole.THINKING,
            content = "planning",
            collapsed = true,
        )
        val assistant = ConversationItem.MessageItem(
            id = 3,
            role = TranscriptRole.ASSISTANT,
            content = "done",
        )
        val tool = ConversationItem.ToolCallItem(
            id = 4,
            toolName = "read_file",
            argumentsJson = null,
            resultJson = null,
            status = ToolCallStatus.RUNNING,
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
        val write = ConversationItem.ToolCallItem(
            id = 1,
            toolName = "write_file",
            argumentsJson = """{"path":"/scratch/a.html","content":"<html>a</html>"}""",
            resultJson = """{"ok":true,"path":"/scratch/a.html","bytes":13}""",
            status = ToolCallStatus.DONE,
            startedAt = 1,
            finishedAt = 2,
        )

        val items = listOf<ConversationItem>(write).toRenderItems()

        assertEquals(2, items.size)
        assertTrue(items[0] is ConversationRenderItem.Single)
        val artifact = items[1] as ConversationRenderItem.HtmlArtifact
        assertEquals("/scratch/a.html", artifact.preview.path)
        assertEquals("<html>a</html>", artifact.preview.html)
        assertEquals("html-artifact-1", artifact.key)
    }

    @Test
    fun nonHtmlOrFailedWriteEmitsNoArtifactPreview() {
        val failed = ConversationItem.ToolCallItem(
            id = 1,
            toolName = "write_file",
            argumentsJson = """{"path":"/scratch/a.html","content":"<html>a</html>"}""",
            resultJson = """{"ok":false,"code":"X"}""",
            status = ToolCallStatus.FAILED,
            startedAt = 1,
            finishedAt = 2,
        )
        val text = ConversationItem.ToolCallItem(
            id = 2,
            toolName = "write_file",
            argumentsJson = """{"path":"/scratch/a.txt","content":"plain"}""",
            resultJson = """{"ok":true,"path":"/scratch/a.txt","bytes":5}""",
            status = ToolCallStatus.DONE,
            startedAt = 2,
            finishedAt = 3,
        )

        val items = listOf<ConversationItem>(failed, text).toRenderItems()

        assertTrue(items.none { it is ConversationRenderItem.HtmlArtifact })
    }

    private fun contextTool(
        id: Long,
        toolName: String,
        arguments: String,
        result: String,
    ): ConversationItem.ToolCallItem =
        ConversationItem.ToolCallItem(
            id = id,
            toolName = toolName,
            argumentsJson = "tool=$toolName\narguments=$arguments",
            resultJson = "tool=$toolName\nok=true\nresult=$result",
            status = ToolCallStatus.DONE,
            startedAt = id,
            finishedAt = id + 1,
        )

    private fun businessTool(id: Long, toolName: String): ConversationItem.ToolCallItem =
        ConversationItem.ToolCallItem(
            id = id,
            toolName = toolName,
            argumentsJson = "tool=$toolName\narguments={}",
            resultJson = "tool=$toolName\nok=true\nresult={}",
            status = ToolCallStatus.DONE,
            startedAt = id,
            finishedAt = id + 1,
        )
}
