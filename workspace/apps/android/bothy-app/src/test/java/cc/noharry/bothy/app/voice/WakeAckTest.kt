package cc.noharry.bothy.app.voice
import cc.noharry.bothy.app.voice.tts.MimoTtsVoice
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 小博's spoken answer to a wake: the packaged lines, how one is picked, and the WAV handling behind them. */
class WakeAckTest {
    @Test
    fun everyPresetVoiceShipsLinesThatDecodeShortAndClean() {
        MimoTtsVoice.entries.forEach { voice ->
            val dir = File("src/main/assets/$WAKE_ACK_ASSETS/${voice.name.lowercase()}")
            val clips = WakeAckLine.entries.mapNotNull { line ->
                File(dir, "${line.file}.wav").takeIf(File::isFile)?.let { file ->
                    val (samples, rate) = parseWav(file.readBytes()) ?: error("${file.path} is not 16-bit mono PCM")
                    WakeClip(line, samples, rate)
                }
            }
            assertTrue("${voice.id} answers a wake two ways at least", clips.count { !it.line.afterInterrupt && !it.line.english } >= 2)
            assertTrue("${voice.id} has 「好，你说」 for an interrupting wake", clips.any { it.line.afterInterrupt && !it.line.english })
            assertTrue("${voice.id} answers OK Bothy two ways", clips.count { !it.line.afterInterrupt && it.line.english } >= 2)
            assertTrue("${voice.id} has Go ahead for an interrupting OK Bothy", clips.any { it.line.afterInterrupt && it.line.english })
            clips.forEach { clip ->
                assertEquals(24_000, clip.sampleRate)
                assertTrue("${voice.id} ${clip.line.text} lasts ${clip.millis} ms", clip.millis in 300L..2_000L)
            }
        }
    }

    @Test
    fun anInterruptingWakeIsAnsweredGoOnAndOthersNeverTwiceTheSameInARow() {
        val clips = WakeAckLine.entries.map { WakeClip(it, ShortArray(10), 24_000) }
        assertEquals(WakeAckLine.GO_ON, pickWakeAck(clips, interrupted = true, last = null)?.line)

        val random = Random(7)
        var last: WakeAckLine? = null
        repeat(20) {
            val picked = pickWakeAck(clips, interrupted = false, last = last, random = random)!!.line
            assertFalse(picked.afterInterrupt)
            assertNotEquals(last, picked)
            last = picked
        }

        val onlyOne = clips.filter { it.line == WakeAckLine.YES }
        assertEquals("a voice missing a line still answers", WakeAckLine.YES, pickWakeAck(onlyOne, interrupted = true, last = WakeAckLine.YES)?.line)
        assertNull(pickWakeAck(emptyList(), interrupted = false, last = null))
    }

    @Test
    fun okBothyIsAnsweredInEnglishAndNeverInChinese() {
        val clips = WakeAckLine.entries.map { WakeClip(it, ShortArray(10), 24_000) }
        assertEquals(WakeAckLine.GO_ON_EN, pickWakeAck(clips, interrupted = true, last = null, english = true)?.line)
        repeat(10) { seed ->
            assertTrue(pickWakeAck(clips, interrupted = false, last = null, english = true, random = Random(seed))!!.line.english)
            assertFalse(pickWakeAck(clips, interrupted = false, last = null, random = Random(seed))!!.line.english)
        }
        val chineseOnly = clips.filterNot { it.line.english }
        assertNull("without English lines the chime alone answers OK Bothy", pickWakeAck(chineseOnly, interrupted = false, last = null, english = true))
    }

    @Test
    fun aWavRoundTripsAndOtherAudioIsRefused() {
        val samples = ShortArray(480) { (it * 37 - 9_000).toShort() }
        val wav = wavBytes(samples, 24_000)
        val (decoded, rate) = parseWav(wav)!!
        assertEquals(24_000, rate)
        assertArrayEquals(samples, decoded)

        val stereo = wav.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(22, 2) }
        assertNull(parseWav(stereo))
        assertNull(parseWav("not a wav file at all, but long enough".toByteArray()))
    }

    @Test
    fun aStreamedWavWithoutItsSizesReadsToTheEnd() {
        val samples = ShortArray(100) { it.toShort() }
        val wav = wavBytes(samples, 24_000).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(40, -1) }
        assertArrayEquals(samples, parseWav(wav)!!.first)
    }

    @Test
    fun trimmingKeepsTheSpeechWithALittleAirAndFades() {
        val rate = 24_000
        val speech = ShortArray(rate / 2) { if (it % 2 == 0) 8_000 else -8_000 }
        val padded = ShortArray(rate) + speech + ShortArray(rate)
        val trimmed = trimSilence(padded, rate)
        assertTrue("about half a second plus 2 × 40 ms", trimmed.size in speech.size..speech.size + rate / 10 + rate / 50)
        assertEquals(0, trimmed.first().toInt())
        assertEquals(0, trimmed.last().toInt())
        assertArrayEquals("silence alone is left as it is", ShortArray(100), trimSilence(ShortArray(100), rate))
    }

    @Test
    fun aWakeSaidAgainMustBeAsNearAsTheLastOne() {
        assertFalse(isFarWake(-30.0, -28.0))
        assertTrue("12 dB under the caller: across the car, or a video", isFarWake(-40.0, -28.0))
        assertFalse("no level known: it wakes", isFarWake(null, -28.0))
        assertFalse(isFarWake(-40.0, null))
    }
}
