package cc.noharry.bothy.server.plans
import android.app.Application
import cc.noharry.bothy.server.voice.PLAN_CHIME_NOTES
import cc.noharry.bothy.server.voice.PLAN_CHIME_PEAK
import cc.noharry.bothy.server.voice.PLAN_CHIME_TIMBRE
import cc.noharry.bothy.server.voice.SoftNote
import cc.noharry.bothy.server.voice.SoftTimbre
import cc.noharry.bothy.server.voice.softChimeSamples
import cc.noharry.bothy.server.voice.softNotesOf
import cc.noharry.bothy.server.voice.softTimbreOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chime Bothy's voice plays when a plan comes due. What the plan's popup says is the plans plugin's
 * (`PlanNoticeTest` in agent-app-plans).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh", application = Application::class)
class PlanNoticeTest {
    @Test
    fun thePlanChimeIsTheQuickMuffledFourthPickedInTheCar() {
        assertEquals(softNotesOf("783.99,0,14;1046.5,0.09,7"), PLAN_CHIME_NOTES)
        assertEquals(softTimbreOf("body=1,octave=0,attack=0.012,room=0,lowpass=1500,poles=2", SoftTimbre()), PLAN_CHIME_TIMBRE)
        assertEquals(0.51, PLAN_CHIME_PEAK, 0.0)
        val rate = 8_000
        val samples = softChimeSamples(PLAN_CHIME_NOTES, sampleRate = rate)
        // C6 falls 50 dB by 0.09 + 0.82 s; the room's 0.45 s stays silent.
        assertTrue(samples.size in 10_850..10_950)
        val loudest = samples.maxOf { kotlin.math.abs(it.toInt()) }
        assertEquals((PLAN_CHIME_PEAK * Short.MAX_VALUE).toInt().toDouble(), loudest.toDouble(), 2.0)
        // A rounded start (no click in the first 2 ms) and a silent end.
        assertTrue(samples.take(rate / 500).maxOf { kotlin.math.abs(it.toInt()) } < loudest / 10)
        assertEquals(0, samples.last().toInt())
    }

    @Test
    fun chimesToTryAreReadFromAdbText() {
        assertEquals(listOf(SoftNote(329.63, 0.0, 8.0), SoftNote(493.88, 0.13, 5.0)), softNotesOf("329.63,0,8; 493.88,0.13,5"))
        assertEquals(SoftNote(587.33, 0.0, 4.0, 0.667), softNotesOf("587.33,0,4,0.667").single())
        listOf("", "440,0", "440,0,8,1,2", "20000,0,8", "440,9,8", "440,0,0.1", "a,b,c").forEach { text ->
            assertTrue(text, runCatching { softNotesOf(text) }.isFailure)
        }
    }

    @Test
    fun timbresToTryChangeOnlyWhatTheyName() {
        val tried = softTimbreOf("octave=0.35, twelfth=0.1,detune=1.0015,attack=0.005,room=0.25")
        assertEquals(PLAN_CHIME_TIMBRE.copy(octave = 0.35, twelfth = 0.1, detune = 1.0015, attackSeconds = 0.005, room = 0.25), tried)
        assertEquals(PLAN_CHIME_TIMBRE, softTimbreOf(""))
        assertEquals(
            PLAN_CHIME_TIMBRE.copy(body = 0.35, octave = 1.0, twelfth = 0.8, fifteenth = 0.5, room = 0.0),
            softTimbreOf("body=0.35,octave=1,twelfth=0.8,fifteenth=0.5,room=0"),
        )
        val muffled = softTimbreOf("octave=0,room=0,lowpass=1000,poles=2")
        assertEquals(SoftTimbre(octave = 0.0, room = 0.0, lowpassHz = 1_000.0, poles = 2), muffled)
        // Two poles at 1 kHz take more of a note's octave away than one pole at 3 kHz: the wave changes more slowly.
        val note = listOf(SoftNote(587.33, 0.0, 8.0))
        val dull = softChimeSamples(note, muffled.copy(octave = 0.5), sampleRate = 16_000)
        val bright = softChimeSamples(note, SoftTimbre(octave = 0.5, room = 0.0), sampleRate = 16_000)
        assertTrue(brightness(dull) < brightness(bright) * 0.9)
        listOf("octave", "octave=2", "detune=1.5", "loud=1", "room=x", "lowpass=50", "poles=9", "body=3").forEach { text ->
            assertTrue(text, runCatching { softTimbreOf(text) }.isFailure)
        }
        // The plan chime has no drive: tanh made it sound distorted in the device.
        assertEquals(0.0, PLAN_CHIME_TIMBRE.drive, 0.0)
        val driven = softChimeSamples(PLAN_CHIME_NOTES, PLAN_CHIME_TIMBRE.copy(drive = 1.5), sampleRate = 8_000)
        assertEquals(softChimeSamples(PLAN_CHIME_NOTES, sampleRate = 8_000).size, driven.size)
    }

    /** How fast the wave moves for its size: more for more high partials. */
    private fun brightness(samples: ShortArray): Double =
        samples.toList().zipWithNext { a, b -> kotlin.math.abs(b - a).toDouble() }.sum() / samples.sumOf { kotlin.math.abs(it.toInt()).toDouble() }
}
