package cc.noharry.bothy.app.phone

import cc.noharry.bothy.app.session.ConversationItem
import cc.noharry.bothy.app.session.ToolCallStatus
import org.junit.Assert.assertEquals
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
}
