package cc.noharry.bothy.app.usage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class UsageStatsTest {
    private fun day(date: String, tokens: Long) = date to mapOf("m" to ModelUsage(1, tokens))

    private val ledger: UsageDays = mapOf(
        day("2026-10-01", 100), day("2026-10-02", 300), day("2026-10-03", 50),
        day("2026-10-05", 10),
        day("2026-10-09", 20), day("2026-10-10", 40), day("2026-10-11", 900),
        "2026-10-07" to mapOf("m" to ModelUsage(requests = 1)),
        "not-a-date" to mapOf("m" to ModelUsage(1, 5)),
    )

    @Test
    fun streaksCountConsecutiveDaysWithTokensAndForgiveAnUnfinishedToday() {
        val book = PriceBook()
        val onTheDay = usageSummary(ledger, book, LocalDate.parse("2026-10-11"))

        assertEquals(UsageSummary(1_420, 900, 0.0, 3, 3), onTheDay)
        assertEquals("nothing yet today", 3, usageSummary(ledger, book, LocalDate.parse("2026-10-12")).currentStreak)
        assertEquals(0, usageSummary(ledger, book, LocalDate.parse("2026-10-13")).currentStreak)
    }

    @Test
    fun heatmapColumnsAreMondayFirstWeeksEndingThisWeek() {
        val daily = dailyTokens(ledger)
        // 2026-10-08 is a Thursday: the last column runs Monday 10-05 to Sunday 10-11, the days after today unset.
        val today = LocalDate.parse("2026-10-08")

        val days = heatmapColumns(daily, today, HeatMode.DAILY, weeks = 2)
        assertEquals(LocalDate.parse("2026-09-28"), heatmapStart(today, 2))
        assertEquals(listOf<Long?>(0, 0, 0, 100, 300, 50, 0), days[0])
        assertEquals(listOf<Long?>(10, 0, 0, 0, null, null, null), days[1])

        assertEquals(listOf<Long?>(10, 10, 10, 10, null, null, null), heatmapColumns(daily, today, HeatMode.WEEKLY, 2)[1])
        val running = heatmapColumns(daily, LocalDate.parse("2026-10-11"), HeatMode.CUMULATIVE, weeks = 1)[0]
        assertEquals("counts days before the window", listOf<Long?>(460, 460, 460, 460, 480, 520, 1_420), running)
    }

    @Test
    fun monthLabelsSitOnTheColumnHoldingTheFirst() {
        assertEquals(listOf(3 to "10月"), heatmapMonths(LocalDate.parse("2026-10-11"), weeks = 5))
    }

    @Test
    fun heatLevelsAreQuartersOfTheMax() {
        assertEquals(listOf(0, 1, 1, 2, 4), listOf(0L, 1L, 25L, 26L, 100L).map { heatLevel(it, 100) })
        assertEquals(0, heatLevel(5, 0))
    }

    @Test
    fun rangesFillMissingDaysAndModelTotalsSumTheRange() {
        val today = LocalDate.parse("2026-10-11")
        val daily = dailyTokens(ledger)
        val series = rangeSeries(today, UsageRange.WEEK) { (daily[it] ?: 0L).toDouble() }

        assertEquals(7, series.size)
        assertEquals(LocalDate.parse("2026-10-05") to 10.0, series.first())
        assertEquals(listOf(10.0, 0.0, 0.0, 0.0, 20.0, 40.0, 900.0), series.map { it.second })
        val withJev = ledger + ("2026-10-11" to ledger.getValue("2026-10-11") + ("jev" to ModelUsage(1, 5)))
        val totals = modelTotals(withJev, LocalDate.parse("2026-10-09"))
        assertEquals(mapOf("m" to ModelUsage(3, 960), "jev" to ModelUsage(1, 5)), totals)
    }

    @Test
    fun axisTopsAndTokenCountsReadRound() {
        assertEquals(listOf(1.0, 5.0, 100.0, 1_000.0, 20_000.0, 500_000.0), listOf(0.0, 5.0, 100.0, 1_000.0, 12_345.0, 300_001.0).map(::niceCeiling))
        assertEquals(0.5, niceCeiling(0.2423), 1e-12)
        assertEquals(0.002, niceCeiling(0.0013), 1e-12)
        assertEquals(listOf("8532", "1.5 万", "3 万", "3.3 亿"), listOf(8_532L, 15_000L, 30_000L, 330_000_000L).map(::tokens))
        assertEquals("周日", weekdayName(LocalDate.parse("2026-10-11")))
    }

    @Test
    fun theCostTrendPricesEachDayAndReadsInShortYuan() {
        val book = PriceBook(mapOf("m" to ModelPrice(input = 1.0, output = 1.0, source = "t")), usdToCny = 7.0)
        val today = LocalDate.parse("2026-10-11")

        val costs = rangeSeries(today, UsageRange.WEEK) { dayCost(ledger, book, it) }
        assertEquals(900 * 7.0 / 1e6, costs.last().second, 1e-12)
        assertEquals("a day with requests but no tokens costs nothing", 0.0, dayCost(ledger, book, LocalDate.parse("2026-10-07")), 0.0)
        assertEquals(listOf("¥0", "¥0.25", "¥0.5", "¥20", "¥0.0063"), listOf(0.0, 0.25, 0.5, 20.0, 0.0063).map(::yuanShort))
        assertEquals(listOf("¥0.25", "2.5 万"), listOf(TrendMetric.COST.format(0.25), TrendMetric.TOKENS.format(25_000.0)))
    }
}
