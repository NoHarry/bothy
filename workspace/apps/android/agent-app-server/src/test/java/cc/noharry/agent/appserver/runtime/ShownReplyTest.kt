package cc.noharry.agent.appserver.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A reply as screens, the transcript and other agents get it, with and without a host that marks progress lines. */
class ShownReplyTest {
    @After
    fun reset() = AppHost.install(runs = RunAddOns.NONE)

    @Test
    fun withoutAHostEveryLineIsTheReply() {
        assertEquals("TTS: checking the weather.\n\nIt is sunny.", " TTS: checking the weather.\n\n\n\nIt is sunny. ".shownReply())
        assertFalse("TTS: checking the weather.".isProgressLine())
    }

    @Test
    fun theHostsProgressLinesAreLeftOutOrOnlyTheirWordsKept() {
        AppHost.install(runs = object : RunAddOns {
            override fun progressWords(line: String): String? = line.removePrefix(">> ").takeIf { line.startsWith(">> ") }
        })
        assertTrue(">> checking the weather.".isProgressLine())
        assertEquals("It is sunny.\n\nTake a hat.", ">> checking the weather.\nIt is sunny.\n\n\n\nTake a hat.".shownReply())
        assertEquals("checking the weather.\nstill checking.", ">> checking the weather.\n>> still checking.".shownReply())
    }
}
