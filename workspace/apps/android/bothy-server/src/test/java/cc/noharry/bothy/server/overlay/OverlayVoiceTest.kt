package cc.noharry.bothy.server.overlay
import cc.noharry.bothy.server.voice.chimeSamples
import cc.noharry.bothy.server.voice.tts.levelOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wake chime and the loudness the overlay's aura is fed (OverlayVoiceUiTest has the overlay's side). */
class OverlayVoiceTest {
    @Test
    fun theWakeChimeIsShortQuietAndStartsAndEndsSilent() {
        val chime = chimeSamples(listOf(Triple(1318.51, 0.0, 0.16), Triple(1760.0, 0.09, 0.32)), sampleRate = 44_100)
        assertEquals("0.41 s", (0.41 * 44_100).toInt(), chime.size)
        val peak = chime.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue("well below full scale: $peak", peak in 9_000..11_000)
        assertTrue("no click at the start", kotlin.math.abs(chime.first().toInt()) < 200)
        assertTrue("faded out at the end", kotlin.math.abs(chime.last().toInt()) < 200)
    }

    @Test
    fun theLoudnessIsMeasuredAgainstFullScale() {
        assertEquals(0f, levelOf(ShortArray(480)), 0f)
        assertEquals("full scale", 1f, levelOf(ShortArray(480) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }), 0.01f)
    }
}
