package cc.noharry.bothy.app.session
import android.app.Application
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.ConfirmationStatus
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.bothy.app.console.ConversationRenderItem
import cc.noharry.bothy.app.console.businessToolLineLabel
import cc.noharry.bothy.app.console.contextLookupLabel
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.console.summaryLabel
import cc.noharry.bothy.app.console.toRenderItems
import cc.noharry.bothy.app.overlay.toCompact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs under Robolectric for the console's labels; the default (English) resources give the strings asserted here. */

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TranscriptUiTest {
    private val resources: Resources = ApplicationProvider.getApplicationContext<Application>().resources

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

    @Test
    fun turnHeaderProjectionFlattensToCompactEntry() {
        val header = ConversationItem.TurnHeader(
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
        val running = ConversationItem.ToolCallItem(
            id = 2,
            toolName = "command_ac_OpenAc",
            argumentsJson = "{}",
            resultJson = null,
            status = ToolCallStatus.RUNNING,
            startedAt = 0L,
        ).toCompact()

        assertTrue(running.title.contains("⏳"))
        assertTrue(running.title.contains("command_ac_OpenAc"))

        val done = ConversationItem.ToolCallItem(
            id = 3,
            toolName = "command_ac_TemperatureAdjust",
            argumentsJson = "{}",
            resultJson = """{"ok":true}""",
            status = ToolCallStatus.DONE,
            startedAt = 0L,
            finishedAt = 10L,
        ).toCompact()

        assertTrue(done.title.contains("✓"))
        assertEquals("""{"ok":true}""", done.body)

        val unknown = ConversationItem.ToolCallItem(
            id = 6,
            toolName = "command_navi_SearchAlongRoute",
            argumentsJson = "{}",
            resultJson = null,
            status = ToolCallStatus.UNKNOWN,
            startedAt = 0L,
            finishedAt = 10L,
        ).toCompact()

        assertTrue(unknown.title.contains("?"))
        assertEquals("{}", unknown.body)
    }

    @Test
    fun messageCompactReflectsErrorRole() {
        val error = ConversationItem.MessageItem(
            id = 4,
            role = TranscriptRole.ERROR,
            content = "boom",
        ).toCompact()

        assertTrue(error.isError)
        assertEquals("boom", error.body)
    }

    @Test
    fun deniedConfirmationCompactProjectionIsError() {
        val compact = ConversationItem.ConfirmationItem(
            id = 7,
            pendingId = "pending-1",
            domain = "example.com",
            mode = ConfirmationMode.DENY_ALL,
            status = ConfirmationStatus.AUTO_DENIED,
            createdAt = 1L,
            resolvedAt = 2L,
        ).toCompact()

        assertTrue(compact.isError)
        assertEquals("example.com", compact.body)
    }

    @Test
    fun searchActivitySurfacesWebSearchQueryOnToolCard() {
        // What the transcript records for a search activity (TranscriptTest checks the recording).
        val tool = ConversationItem.ToolCallItem(
            id = 2,
            toolName = "web_search",
            argumentsJson = """{"query":"广州 天气"}""",
            resultJson = """{"provider":"Anthropic","resultCount":3}""",
            status = ToolCallStatus.DONE,
            startedAt = 2,
            finishedAt = 2,
        )

        assertEquals("web_search · 广州 天气", tool.businessToolLineLabel())
        assertEquals("web_search · 深圳北站 停车场", tool.copy(argumentsJson = """{"query":"深圳北站 停车场"}""").businessToolLineLabel())
    }

    @Test
    fun consecutiveHotTurnsRemainDistinctInTranscript() {
        // What the transcript holds after two hot-weather turns (TranscriptTest checks the recording).
        val turns = listOf<ConversationItem>(
            ConversationItem.TurnHeader(id = 1, label = "Turn 1", preview = "好热", fullText = "好热"),
            ConversationItem.TurnHeader(id = 2, label = "Turn 2", preview = "还是好热", fullText = "还是好热"),
        )

        val renderKeys = turns.toRenderItems().map { it.key }

        assertEquals(renderKeys.distinct(), renderKeys)
    }

    @Test
    fun renderProjectionGroupsConsecutiveContextLookupTools() {
        val read = contextTool(1, "read_file", """{"path":"/workspace/harness.yaml"}""")
        val grep = contextTool(2, "grep", """{"pattern":"debug","path":"/workspace/trace"}""")
        val business = contextTool(3, "command_navi_CalRoute", """{"destination":"home"}""")

        val renderItems = listOf<ConversationItem>(read, grep, business).toRenderItems()

        assertEquals(2, renderItems.size)
        val group = renderItems.first() as ConversationRenderItem.ContextToolGroup
        assertEquals("Read 1 file and searched code", group.summaryLabel(resources))
        assertEquals("Read harness.yaml", group.tools[0].contextLookupLabel(resources))
        assertEquals("Searched for debug in trace", group.tools[1].contextLookupLabel(resources))
        // Single business tool stays ungrouped.
        assertTrue(renderItems.last() is ConversationRenderItem.Single)
    }

    @Test
    fun renderProjectionSplitsContextLookupGroupsAroundBusinessTools() {
        val firstRead = contextTool(1, "read_file", """{"path":"/workspace/one.md"}""")
        val business = contextTool(2, "command_media_PlaySong", """{"artist":"Jay"}""")
        val secondRead = contextTool(3, "read_file", """{"path":"/workspace/two.md"}""")

        val groups = listOf<ConversationItem>(firstRead, business, secondRead)
            .toRenderItems()
            .filterIsInstance<ConversationRenderItem.ContextToolGroup>()

        assertEquals(2, groups.size)
        assertEquals("Read one.md", groups[0].tools.single().contextLookupLabel(resources))
        assertEquals("Read two.md", groups[1].tools.single().contextLookupLabel(resources))
    }

    @Test
    fun renderProjectionGroupsConsecutiveBusinessTools() {
        val a = contextTool(1, "command_media_PlaySong", """{"artist":"A"}""")
        val b = contextTool(2, "command_navi_CalRoute", """{"destination":"home"}""")
        val group = listOf<ConversationItem>(a, b)
            .toRenderItems()
            .single() as ConversationRenderItem.BusinessToolGroup
        assertEquals(2, group.tools.size)
        assertEquals("Used 2 tools", group.summaryLabel(resources))
    }
}
