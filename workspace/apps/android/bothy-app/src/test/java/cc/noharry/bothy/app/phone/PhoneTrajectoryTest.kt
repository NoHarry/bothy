package cc.noharry.bothy.app.phone

import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.TranscriptRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneTrajectoryTest {
    @Test
    fun aCallsFieldsReadAsRowsWithWhatIsNestedKeptShort() {
        val rows = keyValues("""{"origin":"深圳北站","minutes":42,"busy":false,"stops":["留仙大道","北环"]}""")
        assertEquals(
            listOf("origin" to "深圳北站", "minutes" to "42", "busy" to "false", "stops" to """["留仙大道","北环"]"""),
            rows,
        )
        assertEquals(listOf("path" to "a.txt"), keyValues("""arguments={"path":"a.txt"}"""))
        assertNull(keyValues("plain text"))
        assertNull(keyValues(null))
    }

    @Test
    fun aTurnsStepsAreItsThoughtsAndToolsUpToTheNextTurn() {
        val items = listOf(
            ConversationItem.TurnHeader(1, "You", "去世界之窗", "去世界之窗"),
            ConversationItem.MessageItem(2, TranscriptRole.THINKING, "先查路线"),
            ConversationItem.ToolCallItem(3, toolName = "navigation_route", status = ToolCallStatus.DONE, startedAt = 0L),
            ConversationItem.MessageItem(4, TranscriptRole.ASSISTANT, "42 分钟", final = true),
            ConversationItem.TurnHeader(5, "You", "开空调", "开空调"),
            ConversationItem.ToolCallItem(6, toolName = "car_climate", status = ToolCallStatus.DONE, startedAt = 0L),
        )
        assertEquals(listOf(2L, 3L), turnSteps(items, turn = 1).map { it.id })
        assertEquals(listOf(6L), turnSteps(items, turn = null).map { it.id })
    }
}
