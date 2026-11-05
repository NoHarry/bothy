package cc.noharry.bothy.app.phone

import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneStepsTest {
    @Test
    fun aStepNamesWhatItActedOn() {
        assertEquals("咖啡", tool("""{"query":"咖啡","limit":5}""").stepObject())
        assertEquals("账单-10月.csv", tool("""{"path":"/sdcard/Download/账单-10月.csv"}""").stepObject())
        assertEquals("世界之窗", tool("""arguments={"destination":"世界之窗"}""").stepObject())
    }

    @Test
    fun aStepGivenNothingToNameSaysOnlyItsVerb() {
        assertEquals("", tool(null).stepObject())
        assertEquals("", tool("""{"temperature":24}""").stepObject())
        assertEquals("", tool("not json").stepObject())
    }

    @Test
    fun aLongObjectIsCut() {
        val shown = tool("""{"text":"${"很长".repeat(40)}"}""").stepObject()
        assertEquals(40, shown.length)
        assertEquals('…', shown.last())
    }

    private fun tool(args: String?) = ConversationItem.ToolCallItem(1, toolName = "t", argumentsJson = args, resultJson = null, status = ToolCallStatus.DONE, startedAt = 0L)

    @Test
    fun aPlanCallNamesThePlanItMade() {
        val made = ConversationItem.ToolCallItem(
            1, toolName = "mcp__plans__plan_manage", argumentsJson = """{"action":"create","title":"机场咖啡"}""",
            resultJson = """{"ok":true,"id":"p1","title":"机场咖啡"}""", status = ToolCallStatus.DONE, startedAt = 0L,
        )
        assertEquals("p1", made.madePlan())
        assertEquals("p1", made.copy(argumentsJson = """arguments={"action":"update","id":"p1"}""").madePlan())
        assertNull(made.copy(argumentsJson = """{"action":"list"}""").madePlan())
        assertNull(made.copy(status = ToolCallStatus.FAILED).madePlan())
        assertNull(made.copy(toolName = "read_file").madePlan())
    }
}
