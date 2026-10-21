package cc.noharry.bothy.sample

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugPlanNoticeTest {
    @Test
    fun eachKindOfPlanSaysWhatItDoes() {
        val reminder = plan("p1", "拿快递", message = "下楼拿快递")
        assertEquals(DebugPlanNoticeEntry("p1", "提醒", "拿快递", "提醒你：下楼拿快递", null), planNoticeEntry(reminder, "提醒你：下楼拿快递"))

        val task = plan("p2", "查路况", instruction = "看看去公司的路堵不堵")
        assertEquals(DebugPlanNoticeEntry("p2", "去办", "查路况", "交给小博：看看去公司的路堵不堵", null), planNoticeEntry(task, "到点了，我来办：看看去公司的路堵不堵"))

        val dim = DebugPlanAction("device", "system_screen_brightness_set", JsonObject(emptyMap()), "屏幕调暗")
        val open = DebugPlanAction("device", "home_window_position_set", JsonObject(emptyMap()), "窗户全开")
        val rule = plan("p3", "到终点开窗", actions = listOf(open, dim))
        assertEquals(DebugPlanNoticeEntry("p3", "自动", "到终点开窗", null, "执行：窗户全开、屏幕调暗"), planNoticeEntry(rule, null))
        // A rule that also says something shows both.
        assertEquals("到了", planNoticeEntry(rule.copy(message = "到了"), "到了").said)
        // Nothing to say shows no empty line.
        assertNull(planNoticeEntry(plan("p", "空", message = ""), "").said)
    }

    @Test
    fun plansComingDueTogetherShareThePopupNewestFirst() {
        val first = entry("a")
        val second = entry("b")
        assertEquals(listOf(second, first), noticeEntries(noticeEntries(emptyList(), first), second))
        // The same plan again replaces its entry and comes to the top.
        val again = entry("a").copy(said = "又到了")
        assertEquals(listOf(again, second), noticeEntries(listOf(second, first), again))
        val many = ('a'..'e').fold(emptyList<DebugPlanNoticeEntry>()) { shown, id -> noticeEntries(shown, entry("$id")) }
        assertEquals(listOf("e", "d", "c"), many.map { it.planId })
        assertEquals(MAX_NOTICE_ENTRIES, many.size)
    }

    @Test
    fun theCountdownShowsWholeSecondsFromFiveToOne() {
        assertEquals(5, noticeSecondsLeft(PLAN_NOTICE_MS))
        assertEquals(5, noticeSecondsLeft(4_001))
        assertEquals(4, noticeSecondsLeft(4_000))
        assertEquals(1, noticeSecondsLeft(1))
        assertEquals(0, noticeSecondsLeft(0))
        assertEquals(0, noticeSecondsLeft(-20))
    }

    @Test
    fun thePlanChimeIsTheQuickMuffledFourthPickedInTheCar() {
        assertEquals(softNotesOf("783.99,0,14;1046.5,0.09,7"), PLAN_CHIME_NOTES)
        assertEquals(softTimbreOf("body=1,octave=0,attack=0.012,room=0,lowpass=1500,poles=2", DebugSoftTimbre()), PLAN_CHIME_TIMBRE)
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
        assertEquals(listOf(DebugSoftNote(329.63, 0.0, 8.0), DebugSoftNote(493.88, 0.13, 5.0)), softNotesOf("329.63,0,8; 493.88,0.13,5"))
        assertEquals(DebugSoftNote(587.33, 0.0, 4.0, 0.667), softNotesOf("587.33,0,4,0.667").single())
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
        assertEquals(DebugSoftTimbre(octave = 0.0, room = 0.0, lowpassHz = 1_000.0, poles = 2), muffled)
        // Two poles at 1 kHz take more of a note's octave away than one pole at 3 kHz: the wave changes more slowly.
        val note = listOf(DebugSoftNote(587.33, 0.0, 8.0))
        val dull = softChimeSamples(note, muffled.copy(octave = 0.5), sampleRate = 16_000)
        val bright = softChimeSamples(note, DebugSoftTimbre(octave = 0.5, room = 0.0), sampleRate = 16_000)
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

    private fun entry(id: String) = DebugPlanNoticeEntry(id, "提醒", "计划 $id", "提醒你：$id", null)

    private fun plan(
        id: String,
        title: String,
        message: String = "",
        instruction: String? = null,
        actions: List<DebugPlanAction> = emptyList(),
    ) = DebugPlan(
        id = id, title = title, message = message, instruction = instruction,
        trigger = DebugPlanTrigger.Once(0L), enabled = true, createdAtMillis = 0L, actions = actions,
    )
}
