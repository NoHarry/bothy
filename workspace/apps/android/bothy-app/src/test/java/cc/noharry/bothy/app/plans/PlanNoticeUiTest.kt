package cc.noharry.bothy.app.plans
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.PlanKind
import cc.noharry.agent.appserver.protocol.PlanNoticeEntry
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.bothy.app.overlay.MAX_NOTICE_ENTRIES
import cc.noharry.bothy.app.overlay.PLAN_NOTICE_MS
import cc.noharry.bothy.app.overlay.labelRes
import cc.noharry.bothy.app.overlay.noticeEntries
import cc.noharry.bothy.app.overlay.noticeSecondsLeft
import cc.noharry.bothy.app.overlay.planNoticeDoing
import cc.noharry.bothy.app.overlay.planNoticeSaid
import cc.noharry.bothy.app.words
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh", application = Application::class)
class PlanNoticeUiTest {
    private fun entry(id: String) = PlanNoticeEntry(id, PlanKind.REMINDER, "计划 $id", said = "提醒你：$id")

    @Test
    fun plansComingDueTogetherShareThePopupNewestFirst() {
        val first = entry("a")
        val second = entry("b")
        assertEquals(listOf(second, first), noticeEntries(noticeEntries(emptyList(), first), second))
        // The same plan again replaces its entry and comes to the top.
        val again = entry("a").copy(said = "又到了")
        assertEquals(listOf(again, second), noticeEntries(listOf(second, first), again))
        val many = ('a'..'e').fold(emptyList<PlanNoticeEntry>()) { shown, id -> noticeEntries(shown, entry("$id")) }
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
    fun thePopupSaysWhatEachKindOfPlanDoes() {
        val resources = ApplicationProvider.getApplicationContext<Application>().resources
        val rule = PlanNoticeEntry("p3", PlanKind.AUTO, "到终点开窗", actions = listOf("窗户全开", "屏幕调暗"))
        assertEquals("自动" to "执行：窗户全开、屏幕调暗", resources.getString(rule.kind.labelRes()) to resources.planNoticeDoing(rule))
        val task = PlanNoticeEntry("p2", PlanKind.TASK, "查路况", handed = "看看去公司的路堵不堵")
        assertEquals("交给小博：看看去公司的路堵不堵", resources.planNoticeSaid(task))
        // Once the controls are done their outcome replaces what they were doing.
        val ran = rule.copy(outcome = finding(FindingCode.PLANS_ACTION_DONE, "窗户全开"))
        assertEquals(resources.words(finding(FindingCode.PLANS_ACTION_DONE, "窗户全开")), resources.planNoticeDoing(ran))
        assertNull(resources.planNoticeDoing(task))
    }

    @Test
    @Config(qualifiers = "en")
    fun thePopupsWordsFollowTheAppLanguage() {
        val resources = ApplicationProvider.getApplicationContext<Application>().resources
        val rule = PlanNoticeEntry("p3", PlanKind.AUTO, "到终点开窗", actions = listOf("窗户全开", "屏幕调暗"))
        assertEquals("Auto" to "Running: 窗户全开, 屏幕调暗", resources.getString(rule.kind.labelRes()) to resources.planNoticeDoing(rule))
        assertEquals("Handed to Bothy: 查路况", resources.planNoticeSaid(PlanNoticeEntry("p2", PlanKind.TASK, "t", handed = "查路况")))
    }
}
