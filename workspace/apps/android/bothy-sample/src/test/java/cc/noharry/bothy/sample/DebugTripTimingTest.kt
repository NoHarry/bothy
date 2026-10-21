package cc.noharry.bothy.sample

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugTripTimingTest {
    @Test
    fun aReturnFlightRightAfterTheLastSightIsRefusedWithWhatToChange() {
        val problem = debugTripTimingProblem(trip(back = "17:30 → 20:20", lastDay = listOf("10:00–12:00" to "武侯祠", "14:00–16:00" to "杜甫草堂")))

        assertTrue(problem!!, problem.startsWith("回程 17:30 出发，D3 10/25 周日最后一项「杜甫草堂」16:00 才结束"))
        assertTrue(problem, "至少 3 小时" in problem && "去机场" in problem)
    }

    @Test
    fun threeHoursBeforeTheFlightFitsAndTheWayToTheAirportIsNoSight() {
        val lastDay = listOf("09:30–13:30" to "杜甫草堂", "14:00" to "去机场", "15:00" to "天府机场 T2 值机")

        assertNull(debugTripTimingProblem(trip(back = "17:30 → 20:20", lastDay = lastDay)))
    }

    @Test
    fun aLoneTimeCountsAsAnHourAndATrainNeedsLess() {
        // 15:00 alone ends at 16:00: 1.5 hours before a G train is enough, before a flight it is not.
        val lastDay = listOf("15:00" to "宽窄巷子")

        assertNull(debugTripTimingProblem(trip(back = "17:30 → 20:20", backTitle = "回程 · G1974 高铁", lastDay = lastDay)))
        assertTrue(debugTripTimingProblem(trip(back = "17:30 → 20:20", lastDay = lastDay))!!.contains("至少 3 小时"))
    }

    @Test
    fun theFirstSightRightAfterLandingIsRefusedButCheckingInIsNot() {
        val firstDay = listOf("15:00" to "入住酒店、放行李", "15:10–17:00" to "人民公园")

        val problem = debugTripTimingProblem(trip(there = "11:55 → 14:50", firstDay = firstDay))

        assertEquals("去程 14:50 到达，D1 10/23 周五第一项「人民公园」15:10 就开始，来不及从机场进城（至少 1 小时）。把第一天的安排往后排，再调用 show_card。", problem)
        assertNull(debugTripTimingProblem(trip(there = "11:55 → 14:50", firstDay = listOf("16:00–18:00" to "人民公园"))))
    }

    @Test
    fun aDriveOnlyNeedsTheWayBackToTheCar() {
        val there = DebugCardItem(null, "去程 · 自驾", "约 110 km · 1.5 小时", "08:00 → 09:40", kind = DebugCardItemKind.TRANSPORT)
        val back = DebugCardItem(null, "回程 · 自驾", "约 110 km", "16:30 出发", kind = DebugCardItemKind.TRANSPORT)
        val day = listOf(DebugCardItem(null, "拙政园", null, "09:40–12:00", group = "D1"), DebugCardItem(null, "平江路", null, "14:00–16:00", group = "D1"))

        assertNull(debugTripTimingProblem(listOf(there, back) + day))
        // Parking and the shuttle up the mountain are getting there, not a sight.
        assertTrue(listOf("先停庾村集散中心换乘", "下山取车返程", "杭州东站 → 湖滨酒店", "机场 -> 市区").all { DebugCardItem(null, it, null, null).isTripTransfer() })
        assertFalse(listOf("断桥 → 白堤", "灵隐寺").any { DebugCardItem(null, it, null, null).isTripTransfer() })
        assertEquals(
            "回程 16:10 出发，D1最后一项「平江路」16:00 才结束，留不出回停车场取车的时间（至少 0.5 小时）。把最后一天的安排提前或删掉，或晚一点出发，再调用 show_card。",
            debugTripTimingProblem(listOf(there, back.copy(value = "16:10 出发")) + day),
        )
    }

    @Test
    fun aTripWithTheWayThereOnlyIsAskedForTheWayBack() {
        val there = DebugCardItem(null, "去程 · 自驾", "约 220 公里", "07:00 → 10:00", kind = DebugCardItemKind.TRANSPORT)
        val day = DebugCardItem(null, "庾村文化市集", null, "12:00–13:00", group = "D1")

        assertTrue(debugTripTimingProblem(listOf(there, day))!!.startsWith("只有去程，没有回程"))
        assertNull(debugTripTimingProblem(listOf(day)))
    }

    @Test
    fun aLegIsAFlightUnlessItSaysTrainOrDrive() {
        val legs = listOf("回程 · 自驾" to null, "去程 · G7311 高铁" to "虹桥站 → 杭州东站", "去程 · G54321 华夏航空" to null, "去程 · MU5401" to "浦东T1 → 地窝堡T3")

        assertEquals(
            listOf(DebugTripMode.DRIVE, DebugTripMode.TRAIN, DebugTripMode.FLIGHT, DebugTripMode.FLIGHT),
            legs.map { (title, detail) -> DebugCardItem(null, title, detail, null, kind = DebugCardItemKind.TRANSPORT).tripMode() },
        )
    }

    @Test
    fun timesItCannotReadPassAndATightCardIsSentBackOncePerRun() {
        assertNull(debugTripTimingProblem(trip(back = "待确认", lastDay = listOf("下午" to "杜甫草堂"))))
        assertNull(debugTripTimingProblem(listOf(DebugCardItem(null, "宽窄巷子", null, "14:00", group = "D1"))))

        val card = parseCard(
            Json.parseToJsonElement(
                """{"title":"上海⇄成都 · 3 日行程","layout":"trip","items":[
                  {"kind":"transport","title":"去程 · 3U6992 川航","value":"08:00 → 10:55"},
                  {"kind":"transport","title":"回程 · 3U8961 川航","value":"17:30 → 20:20"},
                  {"title":"杜甫草堂","value":"14:00–16:00","group":"D3"}]}""",
            ).jsonObject,
        )
        val gate = DebugTripTimingGate()

        assertTrue(gate.problem("run-1", card)!!.startsWith("回程 17:30 出发"))
        // The model's next card in that run shows, whatever the check says; another run is checked again.
        assertNull(gate.problem("run-1", card))
        assertTrue(gate.problem("run-2", card) != null)
        assertNull(gate.problem("run-3", card.copy(layout = DebugCardLayout.LIST)))
    }

    @Test
    fun aReturnTrainWithoutItsNumberIsStillATrainAndTheWayToTheStationIsNoSight() {
        // 2026-10-17: six cards refused, a train taken for a flight and 「去杭州东站」 for the last sight.
        val legs = listOf(
            DebugCardItem(null, "去程 · G205 高铁", "上海虹桥→杭州东 · 二等座约87元（待确认）", "07:00 → 07:45", kind = DebugCardItemKind.TRANSPORT),
            DebugCardItem(null, "回程 · 杭州东→上海虹桥", "具体车次待12306确认，二等座约87元", "约16:00 → 16:50", kind = DebugCardItemKind.TRANSPORT),
        )
        val day = listOf(
            DebugCardItem(null, "中国茶叶博物馆", null, "09:00–11:30", group = "D2"),
            DebugCardItem(null, "湖滨吃点东西", null, "12:30–14:00", group = "D2"),
            DebugCardItem(null, "去杭州东站", null, "15:00–15:40", group = "D2"),
        )

        assertEquals(DebugTripMode.TRAIN, legs[1].tripMode(legs))
        assertEquals(DebugTripMode.TRAIN, DebugCardItem(null, "回程", "二等座", null).tripMode())
        assertNull(debugTripTimingProblem(legs + day))
    }

    private fun trip(
        there: String = "08:00 → 10:55",
        back: String = "20:30 → 23:20",
        backTitle: String = "回程 · 3U8961 川航",
        firstDay: List<Pair<String, String>> = listOf("13:00–16:00" to "大熊猫繁育研究基地"),
        lastDay: List<Pair<String, String>> = listOf("09:00–12:00" to "武侯祠"),
    ): List<DebugCardItem> = listOf(
        DebugCardItem(null, "去程 · 3U6992 川航", "虹桥T2 → 天府T2", there, kind = DebugCardItemKind.TRANSPORT),
        DebugCardItem(null, backTitle, "天府T2 → 虹桥T2", back, kind = DebugCardItemKind.TRANSPORT),
        DebugCardItem(null, "小雨", "降雨 60%", "16–19°", group = "D1 10/23 周五", kind = DebugCardItemKind.WEATHER),
    ) + firstDay.map { (time, place) -> DebugCardItem(null, place, null, time, group = "D1 10/23 周五") } +
        DebugCardItem(null, "人民公园", null, "10:00–12:00", group = "D2 10/24 周六") +
        lastDay.map { (time, place) -> DebugCardItem(null, place, null, time, group = "D3 10/25 周日") } +
        DebugCardItem(null, "春熙路亚朵酒店", null, "约 500 元/晚", kind = DebugCardItemKind.STAY)
}
