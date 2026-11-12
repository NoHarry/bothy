package cc.noharry.bothy.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The assistant's own words, while it speaks and in the tail after, told from the speaker's, on a clock of our own. */
class OwnSpeechTest {
    private val said = OwnSpeech()

    @Test
    fun theLastLinesTailWrittenOutJustAfterTheSpeechEndedIsItsOwn() {
        said.saying("好的，音量现在是40%。", now = 0)
        said.ended(now = 1_000)

        assertTrue(said.echoes("量现在是40%", heardAt = 1_300))
        // A stray word of it, as the recognizer closed it: 「是。」.
        assertTrue(said.echoes("是。", heardAt = 1_300))
    }

    @Test
    fun theLineBeforeTheOneNowPlayingCountsAndSoAfterTheSpeechEnded() {
        said.saying("好的，音量现在是40%。", now = 0)
        said.saying("还要再调大一点吗？", now = 1_000)

        assertTrue(said.echoes("量现在是40%", heardAt = 1_300))
        said.ended(now = 2_000)
        assertTrue(said.echoes("量现在是40%", heardAt = 2_400))
    }

    @Test
    fun aDifferentSentenceInTheTailIsTheSpeakers() {
        said.saying("好的，音量现在是40%。", now = 0)
        said.ended(now = 1_000)

        assertFalse(said.echoes("帮我把空调打开", heardAt = 1_300))
    }

    @Test
    fun pastTheTailTheSameWordsAreTheSpeakers() {
        said.saying("好的，音量现在是40%。", now = 0)
        said.ended(now = 1_000)

        assertTrue(said.echoes("量现在是40%", heardAt = 1_000 + OWN_SPEECH_TAIL_MILLIS))
        assertFalse(said.echoes("量现在是40%", heardAt = 1_001 + OWN_SPEECH_TAIL_MILLIS))
    }

    @Test
    fun aNewStretchAfterTheTailForgetsTheLastAndOnlyTheLastFewLinesAreKept() {
        said.saying("好的，音量现在是40%。", now = 0)
        said.ended(now = 1_000)
        said.saying("明天深圳多云。", now = 9_000)
        assertFalse(said.echoes("量现在是40%", heardAt = 9_100))

        listOf("一路畅通。", "前方左转。", "还有两公里。").forEachIndexed { i, line -> said.saying(line, now = 9_200L + i) }
        assertFalse("three lines later the first is gone", said.echoes("明天深圳多云", heardAt = 9_300))
        assertTrue(said.echoes("前方左转", heardAt = 9_300))
    }

    @Test
    fun nothingSaidIsNothingToMatch() {
        assertFalse(said.echoes("好", heardAt = 0))
        said.ended(now = 0)
        assertFalse(said.echoes("好", heardAt = 10))
    }
}
