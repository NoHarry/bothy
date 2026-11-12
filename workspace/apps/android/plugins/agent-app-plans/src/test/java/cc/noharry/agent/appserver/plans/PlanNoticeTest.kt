package cc.noharry.agent.appserver.plans

import cc.noharry.agent.appserver.protocol.Plan
import cc.noharry.agent.appserver.protocol.PlanAction
import cc.noharry.agent.appserver.protocol.PlanKind
import cc.noharry.agent.appserver.protocol.PlanNoticeEntry
import cc.noharry.agent.appserver.protocol.PlanTrigger
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a plan coming due shows in the presenter's popup, by its kind. */
class PlanNoticeTest {
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
