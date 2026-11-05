package cc.noharry.bothy.app.image
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.protocol.view.GENERATED_IMAGES_DIR
import cc.noharry.agent.appserver.protocol.view.OverlayPicture
import cc.noharry.bothy.app.console.ConversationRenderItem
import cc.noharry.bothy.app.console.toRenderItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the console shows a generated picture in the conversation. */
class ImageModelUiTest {
    @Test
    fun consoleShowsThePictureRightAfterItsCollapsedToolGroup() {
        val reference = "$GENERATED_IMAGES_DIR/c2Vzcw/img-mv0rmhd9-jtne.jpg"
        val items = listOf(
            ConversationItem.TurnHeader(id = 1, label = "You", preview = "画一张图", fullText = "画一张图"),
            tool(2, "mcp__image_gen__image_generate", """{"ok":true,"image":{"reference":"$reference"}}"""),
            tool(3, "finish", """{"response":"画好了"}"""),
            ConversationItem.MessageItem(id = 4, role = TranscriptRole.ASSISTANT, content = "画好了"),
        )
        val rendered = items.toRenderItems()
        assertTrue(rendered[1] is ConversationRenderItem.BusinessToolGroup)
        assertEquals(ConversationRenderItem.GeneratedImage(2, OverlayPicture(reference, "雪山湖泊")), rendered[2])
        assertEquals(1, rendered.count { it is ConversationRenderItem.GeneratedImage })
    }

    private fun tool(id: Long, name: String, result: String) = ConversationItem.ToolCallItem(
        id = id,
        toolName = name,
        argumentsJson = """{"prompt":"雪山湖泊"}""",
        resultJson = result,
        status = ToolCallStatus.DONE,
        startedAt = 0L,
    )
}
