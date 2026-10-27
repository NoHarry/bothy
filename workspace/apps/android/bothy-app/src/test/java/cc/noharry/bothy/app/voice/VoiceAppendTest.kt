package cc.noharry.bothy.app.voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAppendTest {
    @Test
    fun anAppendAsksTheAgentToAcknowledgeBrieflyBeforeGoingOn() {
        val input = voiceAppendInput("顺便把空调打开")

        assertTrue(input.startsWith("（语音追加）顺便把空调打开"))
        assertTrue("one short sentence first", input.contains("先用一句不超过15个字的话回应"))
        assertTrue("the task goes on", input.contains("再继续当前任务"))
    }

    @Test
    fun aSpokenAcknowledgementIsNotRepeatedByTheAgent() {
        val input = voiceAppendInput("顺便把空调打开", ack = "好，空调也一起打开")

        assertTrue(input.contains("已经口头回应过用户「好，空调也一起打开」"))
        assertTrue(input.contains("不要再重复确认"))
    }

    @Test
    fun theAcknowledgementIsOneShortLine() {
        assertEquals("好，空调也一起打开", voiceAckLine("「好，空调也一起打开」\n其他说明"))
        assertEquals(null, voiceAckLine("  \n "))
        assertTrue(voiceAckLine("好".repeat(80))!!.length <= 30)
        assertTrue(voiceAckPrompt("导航去广州塔", "顺便把空调打开").contains("不超过15个字"))
    }
}
