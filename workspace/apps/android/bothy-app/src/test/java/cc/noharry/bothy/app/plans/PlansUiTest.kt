package cc.noharry.bothy.app.plans
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.Plan
import cc.noharry.agent.appserver.protocol.PlanAction
import cc.noharry.agent.appserver.protocol.PlanTrigger
import cc.noharry.agent.appserver.protocol.PlanWatch
import cc.noharry.agent.appserver.protocol.WatchOp
import cc.noharry.agent.appserver.protocol.describe
import cc.noharry.bothy.app.console.ConsoleView
import cc.noharry.bothy.app.console.consoleViewFor
import cc.noharry.bothy.app.overlay.clientMode
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh", application = Application::class)
class PlansUiTest {
    @Test
    fun triggersReadAsAPersonWouldSayThem() {
        assertEquals("今天 16:30", PlanTrigger.Once(at("2026-10-15T16:30")).describe(clock, zone))
        assertEquals("明天 08:00", PlanTrigger.Once(at("2026-10-16T08:00")).describe(clock, zone))
        assertEquals("10月20日 周二 08:00", PlanTrigger.Once(at("2026-10-20T08:00")).describe(clock, zone))
        assertEquals("工作日 08:00", PlanTrigger.Repeat(480, setOf(1, 2, 3, 4, 5)).describe(clock, zone))
        assertEquals("每天 07:05", PlanTrigger.Repeat(425, (1..7).toSet()).describe(clock, zone))
        assertEquals("每周一、周三 21:30", PlanTrigger.Repeat(1290, setOf(3, 1)).describe(clock, zone))
        assertEquals("深圳气温超过 30 度 持续 5 秒，一次", temperatureWatch(30.0, hold = 5).describe(clock, zone))
        assertEquals("空气质量指数低于 50，每次", airWatch(50.0, repeat = true).describe(clock, zone))
        assertEquals("最低价低于 800 元，一次", fareWatch(800.0).describe(clock, zone))
        assertEquals("1 小时 20 分钟后", untilText(context, 80 * 60_000L))
        assertEquals("3 分钟后", untilText(context, 150_000L))
        assertEquals("2 天后", untilText(context, 2 * 24 * 3_600_000L + 1))
    }

    @Test
    fun thePageShowsTriggersAsTheModelReadsThemInChinese() {
        listOf(
            PlanTrigger.Once(at("2026-10-15T16:30")), PlanTrigger.Once(at("2026-10-16T08:00")),
            PlanTrigger.Once(at("2026-10-17T08:00")), PlanTrigger.Once(at("2026-10-14T08:00")),
            PlanTrigger.Once(at("2026-10-20T08:00")), PlanTrigger.Repeat(480, setOf(1, 2, 3, 4, 5)),
            PlanTrigger.Repeat(425, (1..7).toSet()), PlanTrigger.Repeat(600, setOf(6, 7)), PlanTrigger.Repeat(1290, setOf(3, 1)),
            temperatureWatch(30.0, hold = 5), airWatch(50.0, repeat = true), fareWatch(800.0),
        ).forEach { trigger -> assertEquals(trigger.describe(clock, zone), trigger.describeOnScreen(context, clock, zone)) }
        assertEquals("2 小时后", untilText(context, 2 * 3_600_000L))
        assertEquals("马上", untilText(context, 0L))
    }

    @Test
    @Config(qualifiers = "en")
    fun thePageShowsTriggersInEnglish() {
        assertEquals("Today 16:30", PlanTrigger.Once(at("2026-10-15T16:30")).describeOnScreen(context, clock, zone))
        assertEquals("Tue 10/20 08:00", PlanTrigger.Once(at("2026-10-20T08:00")).describeOnScreen(context, clock, zone))
        assertEquals("Weekdays at 08:00", PlanTrigger.Repeat(480, setOf(1, 2, 3, 4, 5)).describeOnScreen(context, clock, zone))
        assertEquals("Every Mon, Wed at 21:30", PlanTrigger.Repeat(1290, setOf(3, 1)).describeOnScreen(context, clock, zone))
        assertEquals("最低价低于 800 元, once", fareWatch(800.0).describeOnScreen(context, clock, zone))
        assertEquals("in 1 h 20 min", untilText(context, 80 * 60_000L))
        assertEquals("in 1 minute", untilText(context, 30_000L))
        assertEquals("in 2 days", untilText(context, 2 * 24 * 3_600_000L + 1))
    }

    @Test
    fun thePlansTabLeavesTheClientModeAlone() {
        assertEquals(ConsoleView.PLANS, consoleViewFor("PLANS", clientMode = true))
        assertEquals(ConsoleView.PLANS, consoleViewFor("PLANS", clientMode = false))
        assertEquals(ConsoleView.A2A, consoleViewFor("DEBUG", clientMode = true))
        assertEquals(ConsoleView.DEBUG, consoleViewFor("A2A", clientMode = false))
    }

    @Test
    fun aWatchPlanSaysWhatItDoesWhenTriggered() {
        val rain = PlanWatch("weather", "weather://now", "condition", WatchOp.CONTAINS, JsonPrimitive("雨"), "深圳下雨时")
        val action = PlanAction("tools_json", "send_notification", JsonObject(emptyMap()), "发带伞通知")
        val plan = Plan("p", "下雨提醒", "", null, PlanTrigger.Watch(rain, 0, repeat = true), enabled = true, createdAtMillis = 0, actions = listOf(action))
        assertEquals("触发时自动：发带伞通知", planDoes(context, plan))
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = at("2026-10-15T10:00")

    /** Watches as the plan tool makes them; the page and the model only read their words, hold and repeat. */
    private fun watch(field: String, op: WatchOp, value: Double, text: String, hold: Int = 0, repeat: Boolean = false) =
        PlanTrigger.Watch(PlanWatch("weather", "weather://shenzhen/now", field, op, JsonPrimitive(value), text), hold, repeat)

    private fun temperatureWatch(value: Double, hold: Int = 0) = watch("temperature", WatchOp.ABOVE, value, "深圳气温超过 ${value.toInt()} 度", hold)

    private fun airWatch(value: Double, repeat: Boolean) = watch("aqi", WatchOp.BELOW, value, "空气质量指数低于 ${value.toInt()}", repeat = repeat)

    private fun fareWatch(value: Double) = watch("price", WatchOp.BELOW, value, "最低价低于 ${value.toInt()} 元")

    private fun at(local: String): Long = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()
}
