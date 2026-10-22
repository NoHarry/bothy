package cc.noharry.agent.voice

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {
    @Test
    fun theWakePhraseIsItsPinyinTokensThenThePhrase() {
        assertEquals("n ǐ h ǎo x iǎo b ó @你好小博", wakeKeyword(DEFAULT_WAKE_TOKENS, DEFAULT_WAKE_PHRASE))
        assertEquals("x iǎo b ó @小博", wakeKeyword("  x  iǎo b ó ", "小博"))
    }

    @Test
    fun everyLevelHearsHiXiaoBoAndItsDecoysAndOnlyTheLowOneTakesBo() {
        val standard = WakeSensitivity.STANDARD.keywords().split("/")
        assertEquals("n ǐ h ǎo x iǎo b ó @你好小博", standard.first())
        assertTrue("h āi x iǎo b ó @嗨小博" in standard && "h ēi x iǎo b ó @嘿小博" in standard)
        assertTrue("n ǐ h ǎo x iǎo w áng @${DECOY_MARK}你好小王" in standard)
        assertEquals(standard, WakeSensitivity.HIGH.keywords().split("/"))
        val low = WakeSensitivity.LOW.keywords().split("/")
        assertTrue(low.containsAll(standard) && "n ǐ h ǎo x iǎo b ō @你好小波" in low)
        // Fewer syllables would wake on 「你好你好」 or on every 「你好小王」「你好小哥」.
        assertFalse(low.any { it.startsWith("h ǎo x iǎo b ó @") || it.startsWith("n ǐ h ǎo x iǎo @") })
        assertTrue(WakeSensitivity.HIGH.paths < WakeSensitivity.STANDARD.paths)
        assertTrue(WakeSensitivity.entries.none { level -> level.phrases.any { it.phrase.startsWith(DECOY_MARK) } })
        assertEquals(WakeSensitivity.LOW, WakeSensitivity.of("low"))
    }

    @Test
    fun aWakePhraseInsideATurnIsHeardInEachForm() {
        listOf("你好小博", "你好，小伯", "嗨小博", "Hi，小伯", "hey 小泊", "嘿，小博").forEach { assertTrue(it, DEFAULT_WAKE_PHRASE_HEARD.containsMatchIn(it)) }
        listOf("你好小宝", "黑小妞", "海小镇", "小博").forEach { assertFalse(it, DEFAULT_WAKE_PHRASE_HEARD.containsMatchIn(it)) }
    }

    @Test
    fun theNextMicrophoneUserOpensOnlyAfterThePreviousClosedItsRecording() = runTest {
        val lease = Mutex()
        val events = mutableListOf<String>()
        fun source(name: String) = VoiceAudioSource {
            flow {
                events += "$name open"
                try {
                    while (true) {
                        emit(ShortArray(VOICE_FRAME_SAMPLES))
                        delay(100)
                    }
                } finally {
                    events += "$name closed"
                }
            }
        }
        val wake = launch { LeasedAudioSource(lease, source("wake")).frames().collect {} }
        advanceTimeBy(250)
        val session = launch { LeasedAudioSource(lease, source("session")).frames().take(1).collect {} }
        runCurrent()
        assertEquals("the session waits while the wake word records", listOf("wake open"), events)

        wake.cancel()
        session.join()
        assertEquals(listOf("wake open", "wake closed", "session open", "session closed"), events)
    }

    @Test
    fun aTeeHandsTheSpotterACopyOfEachFrameTheSessionRecords() = runTest {
        val tee = VoiceAudioTee(VoiceAudioSource { flow { for (i in 1..3) { delay(100); emit(ShortArray(4) { i.toShort() }) } } })
        val copied = mutableListOf<Short>()
        val spotter = launch { tee.copies.frames().collect { copied += it[0] } }
        runCurrent()
        val recorded = mutableListOf<ShortArray>()
        tee.frames().collect { frame -> recorded += frame; frame.fill(0) }
        runCurrent()
        spotter.cancel()

        assertEquals(3, recorded.size)
        assertEquals("every frame, untouched by what the session did to its own", listOf<Short>(1, 2, 3), copied)
    }

    @Test
    fun aTeeWithoutCopiesHandsTheSpotterOnlyWhatTheRecordingOffers() = runTest {
        lateinit var tee: VoiceAudioTee
        // The session hears the caller's seat (1, 2, 3); every seat (-1, -2, -3) goes to the spotter.
        val source = VoiceAudioSource {
            flow { for (i in 1..3) { delay(100); tee.offer(ShortArray(4) { (-i).toShort() }); emit(ShortArray(4) { i.toShort() }) } }
        }
        tee = VoiceAudioTee(source, copySource = false)
        val copied = mutableListOf<Short>()
        val spotter = launch { tee.copies.frames().collect { copied += it[0] } }
        runCurrent()
        val recorded = mutableListOf<Short>()
        tee.frames().collect { frame -> recorded += frame[0] }
        runCurrent()
        spotter.cancel()

        assertEquals(listOf<Short>(1, 2, 3), recorded)
        assertEquals(listOf<Short>(-1, -2, -3), copied)
    }
}
