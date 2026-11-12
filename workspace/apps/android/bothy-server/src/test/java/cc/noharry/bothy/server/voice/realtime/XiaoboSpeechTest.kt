package cc.noharry.bothy.server.voice.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 小博's late results in the end-to-end conversation, on a clock of our own: its words heard back are its own, a while after too. */
class XiaoboSpeechTest {
    private var now = 100_000L
    private var announcing = false
    private val xiaobo = XiaoboSpeech(announcing = { announcing }, clock = { now })

    @Test
    fun itsLastWordsHeardJustAfterItStoppedAreItsOwn() {
        sayAndFinish("导航已经设好，大约二十分钟到家。")

        now += 300
        assertTrue(xiaobo.echoes("大约二十分钟到家", heardAt = now))
    }

    @Test
    fun theLateResultBeforeTheOneNowSaidIsStillItsOwn() {
        announcing = true
        xiaobo.says("导航已经设好，大约二十分钟到家。")
        now += 3_000
        xiaobo.says("明天深圳多云，最高二十八度。")
        now += 300

        assertTrue(xiaobo.speaking())
        assertTrue(xiaobo.echoes("大约二十分钟到家", heardAt = now))
    }

    @Test
    fun aDifferentSentenceJustAfterIsTheSpeakers() {
        sayAndFinish("导航已经设好，大约二十分钟到家。")

        now += 300
        assertFalse(xiaobo.echoes("帮我放首歌", heardAt = now))
    }

    @Test
    fun pastTheTailItsWordsAreTheSpeakers() {
        sayAndFinish("导航已经设好，大约二十分钟到家。")

        now += 1_500
        assertFalse(xiaobo.echoes("大约二十分钟到家", heardAt = now))
    }

    @Test
    fun itSpeaksFromTheResultUntilItsAnnouncerIsDoneAndAtLeastWhileItStarts() {
        xiaobo.says("导航已经设好。")
        now += LATE_SPEECH_START_MILLIS - 1
        assertTrue("the announcer has not started yet", xiaobo.speaking())
        announcing = true
        now += 5_000
        assertTrue(xiaobo.speaking())
        announcing = false
        assertFalse(xiaobo.speaking())
        assertEquals(0L, xiaobo.since)
    }

    /** 小博 says [text] and its announcer is done at [now], when the end is noticed. */
    private fun sayAndFinish(text: String) {
        announcing = true
        xiaobo.says(text)
        now += LATE_SPEECH_START_MILLIS + 1_000
        announcing = false
        assertFalse(xiaobo.speaking())
    }
}
