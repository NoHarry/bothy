package cc.noharry.bothy.app.usage
import cc.noharry.bothy.app.a2a.reduce
import cc.noharry.bothy.app.runtime.reduce
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/** What each Token 活动 square shows: that day, its whole week, or everything up to that day. */
internal enum class HeatMode(val label: String) { DAILY("每日"), WEEKLY("每周"), CUMULATIVE("累计") }

/** What the trend chart's bars measure. */
internal enum class TrendMetric(val label: String, val title: String) {
    TOKENS("Token", "每日 Token 趋势图"),
    COST("费用", "每日费用趋势图"),
    ;

    fun format(value: Double): String = if (this == TOKENS) tokens(value.roundToLong()) else yuanShort(value)
}

/** 时间范围 of the trend chart and the model table: the last [days] days, today included. */
internal enum class UsageRange(val label: String, val days: Int) { WEEK("近 7 日", 7), MONTH("近 30 日", 30) }

/** The five numbers at the top of 用量统计. */
internal data class UsageSummary(
    val totalTokens: Long,
    val peakDayTokens: Long,
    val totalCostCny: Double,
    val currentStreak: Int,
    val longestStreak: Int,
)

/** Tokens as the page counts them: input (cache hits included) plus output (reasoning included). */
internal val ModelUsage.totalTokens: Long get() = inputTokens + outputTokens

/** Each day's tokens over all models, for days that used any; ledger keys that are not dates are skipped. */
internal fun dailyTokens(days: UsageDays): Map<LocalDate, Long> =
    days.mapNotNull { (day, models) ->
        val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return@mapNotNull null
        date to models.values.sumOf { it.totalTokens }
    }.filter { it.second > 0 }.toMap()

/** A streak still running yesterday counts as current until today ends. */
internal fun usageSummary(days: UsageDays, book: PriceBook, today: LocalDate): UsageSummary {
    val daily = dailyTokens(days)
    var current = 0
    var day = if (today in daily) today else today.minusDays(1)
    while (day in daily) {
        current++
        day = day.minusDays(1)
    }
    var longest = 0
    var run = 0
    var previous: LocalDate? = null
    daily.keys.sorted().forEach { date ->
        run = if (previous?.plusDays(1) == date) run + 1 else 1
        longest = maxOf(longest, run)
        previous = date
    }
    return UsageSummary(daily.values.sum(), daily.values.maxOrNull() ?: 0, cost(days, book), current, longest)
}

/** Monday of the first of [weeks] columns ending with today's week. */
internal fun heatmapStart(today: LocalDate, weeks: Int): LocalDate =
    today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(weeks - 1L)

/** Token 活动 as Monday-first week columns, oldest first; null for the days after [today]. */
internal fun heatmapColumns(daily: Map<LocalDate, Long>, today: LocalDate, mode: HeatMode, weeks: Int): List<List<Long?>> {
    val start = heatmapStart(today, weeks)
    var running = daily.filterKeys { it.isBefore(start) }.values.sum()
    return (0 until weeks).map { week ->
        val dates = (0 until DAYS_PER_WEEK).map { start.plusWeeks(week.toLong()).plusDays(it.toLong()) }
        val weekTotal = dates.filterNot { it.isAfter(today) }.sumOf { daily[it] ?: 0L }
        dates.map { date ->
            when {
                date.isAfter(today) -> null
                mode == HeatMode.DAILY -> daily[date] ?: 0L
                mode == HeatMode.WEEKLY -> weekTotal
                else -> (running + (daily[date] ?: 0L)).also { running = it }
            }
        }
    }
}

/** Month labels as (column, "10月") at the column holding each month's 1st. */
internal fun heatmapMonths(today: LocalDate, weeks: Int): List<Pair<Int, String>> {
    val start = heatmapStart(today, weeks)
    return (0 until weeks).mapNotNull { week ->
        val first = (0 until DAYS_PER_WEEK).map { start.plusWeeks(week.toLong()).plusDays(it.toLong()) }
            .firstOrNull { it.dayOfMonth == 1 } ?: return@mapNotNull null
        week to "${first.monthValue}月"
    }
}

/** 0 for nothing, else 1–4 by quarters of [max]. */
internal fun heatLevel(value: Long, max: Long): Int =
    if (value <= 0 || max <= 0) 0 else (1 + (value * HEAT_LEVELS - 1) / max).toInt().coerceIn(1, HEAT_LEVELS)

/** One entry per day of [range], oldest first, valued by [value]. */
internal fun rangeSeries(today: LocalDate, range: UsageRange, value: (LocalDate) -> Double): List<Pair<LocalDate, Double>> =
    (range.days - 1 downTo 0).map { back -> today.minusDays(back.toLong()).let { it to value(it) } }

/** Yuan spent on [date]; models without a price add nothing. */
internal fun dayCost(days: UsageDays, book: PriceBook, date: LocalDate): Double =
    days[date.toString()]?.let { cost(mapOf(date.toString() to it), book) } ?: 0.0

/** Each model's usage from [from] on. */
internal fun modelTotals(days: UsageDays, from: LocalDate): Map<String, ModelUsage> =
    daysFrom(days, from).values.flatMap { it.entries }
        .groupBy({ it.key }, { it.value })
        .mapValues { (_, usages) -> usages.reduce(ModelUsage::plus) }

/** The ledger rows from [from] on, for costing a range. */
internal fun daysFrom(days: UsageDays, from: LocalDate): UsageDays =
    days.filterKeys { day -> runCatching { !LocalDate.parse(day).isBefore(from) }.getOrDefault(false) }

/** A round axis top at or above [max]: 1, 2 or 5 times a power of ten. */
internal fun niceCeiling(max: Double): Double {
    if (max <= 0) return 1.0
    val step = 10.0.pow(floor(log10(max)))
    return listOf(1.0, 2.0, 5.0, 10.0).map { it * step }.first { it >= max - step * ROUNDING_SLACK }
}

/** Token counts in Chinese units: 8532, 1.5 万, 3.3 亿. */
internal fun tokens(count: Long): String = when {
    count >= YI -> "${oneDecimal(count / YI.toDouble())} 亿"
    count >= WAN -> "${oneDecimal(count / WAN.toDouble())} 万"
    else -> count.toString()
}

/** Yuan for an axis or a bar: as few decimals as the amount needs, at most four. */
internal fun yuanShort(amount: Double): String =
    if (amount == 0.0) "¥0" else "¥" + BigDecimal(amount).setScale(YUAN_DECIMALS, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

internal fun weekdayName(date: LocalDate): String = "周" + "一二三四五六日"[date.dayOfWeek.value - 1]

private fun oneDecimal(value: Double): String = "%.1f".format(java.util.Locale.ROOT, value).removeSuffix(".0")

internal const val DAYS_PER_WEEK = 7
internal const val HEAT_LEVELS = 4
private const val YUAN_DECIMALS = 4
private const val ROUNDING_SLACK = 1e-9
private const val WAN = 10_000L
private const val YI = 100_000_000L
