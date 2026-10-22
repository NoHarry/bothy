package cc.noharry.bothy.sample

import cc.noharry.agent.voice.DECOY_MARK
import cc.noharry.agent.voice.VoiceJudgeRequest
import cc.noharry.agent.voice.VoiceTurnSnapshot
import cc.noharry.agent.voice.WakeSensitivity
import cc.noharry.agent.voice.jevTurnBody
import cc.noharry.agent.voice.wakeKeyword
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 小博's wake phrases and how the turn judge is told about it, as the voice tests checked before they moved here. */
class XiaoboWakeProfileTest {
    @Test
    fun theWakePhraseIsItsPinyinTokensThenThePhrase() {
        assertEquals("n ǐ h ǎo x iǎo b ó @你好小博", wakeKeyword(XIAOBO_WAKE_TOKENS, XIAOBO_WAKE_PHRASE))
    }

    @Test
    fun everyLevelHearsHiXiaoBoAndItsDecoysAndOnlyTheLowOneTakesBo() {
        val standard = WakeSensitivity.STANDARD.keywords(XIAOBO_WAKE_PROFILE).split("/")
        assertEquals("n ǐ h ǎo x iǎo b ó @你好小博", standard.first())
        assertTrue("h āi x iǎo b ó @嗨小博" in standard && "h ēi x iǎo b ó @嘿小博" in standard)
        assertTrue("n ǐ h ǎo x iǎo w áng @${DECOY_MARK}你好小王" in standard)
        assertEquals(standard, WakeSensitivity.HIGH.keywords(XIAOBO_WAKE_PROFILE).split("/"))
        val low = WakeSensitivity.LOW.keywords(XIAOBO_WAKE_PROFILE).split("/")
        assertTrue(low.containsAll(standard) && "n ǐ h ǎo x iǎo b ō @你好小波" in low)
        // Fewer syllables would wake on 「你好你好」 or on every 「你好小王」「你好小哥」.
        assertFalse(low.any { it.startsWith("h ǎo x iǎo b ó @") || it.startsWith("n ǐ h ǎo x iǎo @") })
    }

    @Test
    fun aWakePhraseInsideATurnIsHeardInEachForm() {
        val heard = XIAOBO_WAKE_PROFILE.heardInTranscript!!
        listOf("你好小博", "你好，小伯", "嗨小博", "Hi，小伯", "hey 小泊", "嘿，小博").forEach { assertTrue(it, heard.containsMatchIn(it)) }
        listOf("你好小宝", "黑小妞", "海小镇", "小博").forEach { assertFalse(it, heard.containsMatchIn(it)) }
    }

    @Test
    fun theTurnJudgeIsToldItsNameAndWakePhrase() {
        val body = jevTurnBody("m", VoiceJudgeRequest(VoiceTurnSnapshot(1, "打开空调", emptyList(), 800, 400, asrFinal = true, called = true), emptyList()), identity = XIAOBO_VOICE_IDENTITY)

        assertTrue(body.contains("an AI assistant called 小博 (Xiaobo) on a tablet"))
        assertTrue(body.contains("said its wake word 「你好小博」 or pressed its mic"))
        assertTrue(body.contains("directed at the assistant 小博, as opposed"))
        assertTrue(body.contains("anything naming 小博 are addressed to it"))
    }
}
