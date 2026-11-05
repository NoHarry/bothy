package cc.noharry.bothy.server.plans
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.plans.planNoticeEntry
import cc.noharry.agent.appserver.protocol.Plan
import cc.noharry.agent.appserver.protocol.PlanAction
import cc.noharry.agent.appserver.protocol.PlanKind
import cc.noharry.agent.appserver.protocol.PlanNoticeEntry
import cc.noharry.agent.appserver.protocol.PlanTrigger
import cc.noharry.bothy.server.voice.PLAN_CHIME_NOTES
import cc.noharry.bothy.server.voice.PLAN_CHIME_PEAK
import cc.noharry.bothy.server.voice.PLAN_CHIME_TIMBRE
import cc.noharry.bothy.server.voice.SoftNote
import cc.noharry.bothy.server.voice.SoftTimbre
import cc.noharry.bothy.server.voice.softChimeSamples
import cc.noharry.bothy.server.voice.softNotesOf
import cc.noharry.bothy.server.voice.softTimbreOf
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh", application = Application::class)
class PlanNoticeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun eachKindOfPlanSaysWhatItDoes() {
        val reminder = plan("p1", "拿快递", message = "下楼拿快递")
        assertEquals(PlanNoticeEntry("p1", PlanKind.REMINDER, "拿快递", said = "提醒你：下楼拿快递"), planNoticeEntry(reminder, "提醒你：下楼拿快递"))

        val task = plan("p2", "查路况", instruction = "看看去公司的路堵不堵")
        assertEquals(
            PlanNoticeEntry("p2", PlanKind.TASK, "查路况", handed = "看看去公司的路堵不堵"),
            planNoticeEntry(task, "到点了，我来办：看看去公司的路堵不堵"),
        )

        val dim = PlanAction("device", "system_screen_brightness_set", JsonObject(emptyMap()), "屏幕调暗")
        val open = PlanAction("device", "home_window_position_set", JsonObject(emptyMap()), "窗户全开")
        val rule = plan("p3", "到终点开窗", actions = listOf(open, dim))
        assertEquals(PlanNoticeEntry("p3", PlanKind.AUTO, "到终点开窗", actions = listOf("窗户全开", "屏幕调暗")), planNoticeEntry(rule, null))
        // A rule that also says something shows both.
        assertEquals("到了", planNoticeEntry(rule.copy(message = "到了"), "到了").said)
        // Nothing to say shows no empty line.
        assertNull(planNoticeEntry(plan("p", "空", message = ""), "").said)
    }

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

    private fun entry(id: String) = PlanNoticeEntry(id, PlanKind.REMINDER, "计划 $id", said = "提醒你：$id")

    private fun plan(
        id: String,
        title: String,
        message: String = "",
        instruction: String? = null,
        actions: List<PlanAction> = emptyList(),
    ) = Plan(
        id = id, title = title, message = message, instruction = instruction,
        trigger = PlanTrigger.Once(0L), enabled = true, createdAtMillis = 0L, actions = actions,
    )
}
