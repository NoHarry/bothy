package cc.noharry.agent.appserver.plans

import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.agent.appserver.server.AppServerConfig
import cc.noharry.agent.appserver.protocol.PlanTrigger
import cc.noharry.agent.appserver.protocol.PlanWatch
import cc.noharry.agent.appserver.protocol.WatchOp
import cc.noharry.agent.appserver.runtime.Persona
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class PlanTextTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = at("2026-10-15T09:00")

    @After
    fun generic() {
        AppServerTesting.reset()
    }

    @Test
    fun aChinesePersonaReadsTriggersAsAPersonWouldSayThem() {
        AppServerTesting.install(AppServerConfig(name = "test", persona = Persona("助手", "", language = "zh-CN")))

        assertEquals("今天 16:30", PlanTrigger.Once(at("2026-10-15T16:30")).describe(clock, zone))
        assertEquals("10月20日 周二 08:00", PlanTrigger.Once(at("2026-10-20T08:00")).describe(clock, zone))
        assertEquals("工作日 08:00", PlanTrigger.Repeat(480, setOf(1, 2, 3, 4, 5)).describe(clock, zone))
        assertEquals("每周一、周三 21:30", PlanTrigger.Repeat(1290, setOf(3, 1)).describe(clock, zone))
        assertEquals("最低价低于 800 元 持续 5 秒，一次", fareWatch(hold = 5).describe(clock, zone))
        assertEquals("超过", WatchOp.ABOVE.words)
    }

    @Test
    fun anotherPersonaReadsThemInEnglish() {
        assertEquals("today 16:30", PlanTrigger.Once(at("2026-10-15T16:30")).describe(clock, zone))
        assertEquals("tomorrow 08:00", PlanTrigger.Once(at("2026-10-16T08:00")).describe(clock, zone))
        assertEquals("Tue Oct 20 08:00", PlanTrigger.Once(at("2026-10-20T08:00")).describe(clock, zone))
        assertEquals("weekdays 08:00", PlanTrigger.Repeat(480, setOf(1, 2, 3, 4, 5)).describe(clock, zone))
        assertEquals("every Mon, Wed 21:30", PlanTrigger.Repeat(1290, setOf(3, 1)).describe(clock, zone))
        assertEquals("最低价低于 800 元 for 5 s, once", fareWatch(hold = 5).describe(clock, zone))
        assertEquals("above", WatchOp.ABOVE.words)
    }

    private fun at(local: String): Long = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    private fun fareWatch(hold: Int) = PlanTrigger.Watch(
        PlanWatch("flights", "", "price", WatchOp.BELOW, JsonPrimitive(800), "最低价低于 800 元", tool = "search_flights"),
        holdSeconds = hold,
        repeat = false,
    )
}

class PlanDeliveryWordsTest {
    @Test
    fun anotherPersonaIsRemindedInEnglish() {
        val plan = cc.noharry.agent.appserver.protocol.Plan(
            id = "p1", title = "Parcel", message = "time to pick up the parcel", instruction = null,
            trigger = PlanTrigger.Once(0L), enabled = true, createdAtMillis = 0L,
        )

        val delivery = planDelivery(plan, dueAt = 0L, late = false, reading = null, now = 0L, zone = ZoneId.of("UTC"))

        assertEquals("Plan · Parcel", delivery.display)
        assertEquals("Reminder: time to pick up the parcel", delivery.reply)
        assertEquals("[Plan due] x".isPlanRun(), true)
        assertEquals("【计划到点】x".isPlanRun(), true)
    }
}
