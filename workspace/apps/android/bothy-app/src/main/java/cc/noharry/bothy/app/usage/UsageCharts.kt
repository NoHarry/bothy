package cc.noharry.bothy.app.usage
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.ModelUsage
import cc.noharry.agent.appserver.protocol.PriceBook
import cc.noharry.agent.appserver.protocol.UsageDays
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle as DateTextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Colours of the usage charts. The heat ramps are one indigo hue, light to dark on the light card and dark to
 * light on the dark one; both ramps and the bar colours pass the dataviz palette validator (ordinal ramp and
 * single-series checks) against the card surface of their mode.
 */
@Immutable
private data class UsageChartColors(val empty: Color, val ramp: List<Color>, val bar: Color, val grid: Color, val label: Color)

@Composable
private fun usageChartColors(): UsageChartColors {
    val colors = ConsoleColors
    return if (colors.isDark) {
        UsageChartColors(
            empty = Color(0xFF29322D),
            ramp = listOf(Color(0xFF2E5A45), Color(0xFF3F7D5E), Color(0xFF62A383), Color(0xFFA8D8BC)),
            bar = Color(0xFF6FB08E),
            grid = colors.border,
            label = colors.tertiary,
        )
    } else {
        UsageChartColors(
            empty = Color(0xFFEDE7DC),
            ramp = listOf(Color(0xFFB9D9C6), Color(0xFF86BA9C), Color(0xFF4E8D6B), Color(0xFF2F6B4F)),
            bar = colors.accent,
            grid = colors.border,
            label = colors.tertiary,
        )
    }
}

/** Token 活动: one square per day, Monday on top, as many weeks (up to a year) as fit the width. Tap picks a day. */
@Composable
internal fun UsageHeatmap(daily: Map<LocalDate, Long>, today: LocalDate, mode: HeatMode, picked: LocalDate?, onPick: (LocalDate) -> Unit) {
    val chart = usageChartColors()
    val ring = ConsoleColors.text
    val pick by rememberUpdatedState(onPick)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val weeks = (maxWidth / HEAT_MIN_PITCH).toInt().coerceIn(HEAT_MIN_WEEKS, HEAT_MAX_WEEKS)
        val pitch = maxWidth / weeks
        val columns = remember(daily, today, mode, weeks) { heatmapColumns(daily, today, mode, weeks) }
        val start = heatmapStart(today, weeks)
        val max = columns.maxOf { week -> week.maxOf { it ?: 0L } }
        val description = pluralStringResource(R.plurals.usage_heatmap_description, weeks, weeks)
        Column {
            Canvas(
                Modifier.fillMaxWidth().height(pitch * DAYS_PER_WEEK)
                    .pointerInput(start, weeks, today) {
                        detectTapGestures { offset ->
                            val step = size.width.toFloat() / weeks
                            val day = (offset.y / step).toLong().coerceIn(0, DAYS_PER_WEEK - 1L)
                            val date = start.plusWeeks((offset.x / step).toLong().coerceIn(0, weeks - 1L)).plusDays(day)
                            if (!date.isAfter(today)) pick(date)
                        }
                    }
                    .semantics { contentDescription = description },
            ) { drawHeatCells(columns, max, chart, start, picked, ring) }
            HeatMonthLabels(today, weeks, pitch)
        }
    }
}

private fun DrawScope.drawHeatCells(columns: List<List<Long?>>, max: Long, chart: UsageChartColors, start: LocalDate, picked: LocalDate?, ring: Color) {
    val pitch = size.width / columns.size
    val cell = Size(pitch - HEAT_GAP.toPx(), pitch - HEAT_GAP.toPx())
    val corner = CornerRadius(cell.width * HEAT_CORNER_SHARE)
    columns.forEachIndexed { week, values ->
        values.forEachIndexed { day, value ->
            if (value == null) return@forEachIndexed
            val level = heatLevel(value, max)
            val topLeft = Offset(week * pitch, day * pitch)
            drawRoundRect(if (level == 0) chart.empty else chart.ramp[level - 1], topLeft, cell, corner)
            if (start.plusWeeks(week.toLong()).plusDays(day.toLong()) == picked) {
                drawRoundRect(ring, topLeft, cell, corner, style = Stroke(PICK_RING.toPx()))
            }
        }
    }
}

@Composable
private fun HeatMonthLabels(today: LocalDate, weeks: Int, pitch: Dp) {
    val locale = screenLocale()
    val months = remember(today, weeks, locale) { heatmapMonths(today, weeks, locale) }
    Box(Modifier.fillMaxWidth().height(HEAT_LABEL_HEIGHT)) {
        months.forEach { (week, label) ->
            Text(label, Modifier.offset(x = pitch * week).padding(top = Dimens.spaceXsMd), style = ConsoleType.meta, color = ConsoleColors.tertiary, maxLines = 1, softWrap = false)
        }
    }
}

/** 少 ▢▢▢▢▢ 多: the heat ramp's key. */
@Composable
internal fun UsageHeatLegend() {
    val chart = usageChartColors()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXxs + 1.dp)) {
        Text(stringResource(R.string.usage_less), style = ConsoleType.meta, color = ConsoleColors.tertiary)
        (listOf(chart.empty) + chart.ramp).forEach { Box(Modifier.size(LEGEND_SWATCH).background(it, RoundedCornerShape(LEGEND_CORNER))) }
        Text(stringResource(R.string.usage_more), style = ConsoleType.meta, color = ConsoleColors.tertiary)
    }
}

/**
 * The daily trend: one bar per day of the range on a zero baseline with a round axis top, measured by [metric]. The
 * picked bar, or the peak when nothing is picked, carries its value; the others dim while one is picked. Tap picks a day.
 */
@Composable
internal fun UsageTrendBars(series: List<Pair<LocalDate, Double>>, metric: TrendMetric, picked: LocalDate?, onPick: (LocalDate) -> Unit) {
    val chart = usageChartColors()
    val measurer = rememberTextMeasurer()
    val labelStyle = ConsoleType.meta.copy(color = chart.label)
    val valueStyle = ConsoleType.meta.copy(color = ConsoleColors.text, fontWeight = FontWeight.SemiBold)
    val peak = series.maxOf { it.second }
    val top = niceCeiling(peak)
    val locale = screenLocale()
    val axis = remember(top, metric, labelStyle, locale) {
        trendAxis(top, metric).map { it to measurer.measure(metric.format(it, locale), labelStyle) }
    }
    val gutter = with(LocalDensity.current) { axis.maxOf { it.second.size.width } + AXIS_GAP.toPx() }
    val pick by rememberUpdatedState(onPick)
    val shown = picked ?: series.maxBy { it.second }.first
    val description = pluralStringResource(
        R.plurals.usage_trend_description, series.size,
        stringResource(metric.title), series.size, metric.format(peak, locale),
    )
    Canvas(
        Modifier.fillMaxWidth().height(TREND_HEIGHT)
            .pointerInput(series, gutter) {
                detectTapGestures { offset ->
                    val slot = (size.width - gutter) / series.size
                    pick(series[((offset.x - gutter) / slot).toInt().coerceIn(0, series.lastIndex)].first)
                }
            }
            .semantics { contentDescription = description },
    ) {
        val labelHeight = axis.first().second.size.height
        val plot = Rect(gutter, labelHeight + AXIS_GAP.toPx(), size.width, size.height - labelHeight - AXIS_GAP.toPx())
        drawTrendGrid(plot, top, axis, chart.grid)
        drawTrendBars(series, plot, top, picked, chart.bar)
        drawTrendDates(series, plot, measurer, labelStyle)
        series.indexOfFirst { it.first == shown }.takeIf { it >= 0 && series[it].second > 0 }?.let { index ->
            val label = measurer.measure(metric.format(series[index].second, locale), valueStyle)
            drawBarValue(label, plot, series.size, index, series[index].second, top)
        }
    }
}

/** Gridline values: zero, the middle (for tokens only when it is a whole number), and the top. */
private fun trendAxis(top: Double, metric: TrendMetric): List<Double> =
    listOfNotNull(0.0, (top / 2).takeIf { metric == TrendMetric.COST || top % 2 == 0.0 }, top)

private fun DrawScope.drawTrendGrid(plot: Rect, top: Double, axis: List<Pair<Double, TextLayoutResult>>, grid: Color) {
    axis.forEach { (value, label) ->
        val y = plot.bottom - plot.height * (value / top).toFloat()
        drawLine(grid, Offset(plot.left, y), Offset(plot.right, y), strokeWidth = 1.dp.toPx())
        drawText(label, topLeft = Offset(plot.left - AXIS_GAP.toPx() - label.size.width, y - label.size.height / 2f))
    }
}

private fun DrawScope.drawTrendBars(series: List<Pair<LocalDate, Double>>, plot: Rect, top: Double, picked: LocalDate?, bar: Color) {
    val slot = plot.width / series.size
    val width = min(slot * BAR_FILL, BAR_MAX_WIDTH.toPx())
    series.forEachIndexed { index, (date, value) ->
        if (value <= 0) return@forEachIndexed
        val height = maxOf(plot.height * (value / top).toFloat(), BAR_MIN_HEIGHT.toPx())
        val left = plot.left + slot * index + (slot - width) / 2
        val radius = CornerRadius(min(BAR_RADIUS.toPx(), min(width / 2, height)))
        val path = Path().apply {
            addRoundRect(RoundRect(left, plot.bottom - height, left + width, plot.bottom, topLeftCornerRadius = radius, topRightCornerRadius = radius))
        }
        drawPath(path, if (picked == null || picked == date) bar else bar.copy(alpha = DIMMED_ALPHA))
    }
}

/** Every day's date in a week's range; in longer ranges today and every seventh day back. */
private fun DrawScope.drawTrendDates(series: List<Pair<LocalDate, Double>>, plot: Rect, measurer: TextMeasurer, style: TextStyle) {
    val slot = plot.width / series.size
    val every = if (series.size <= DAYS_PER_WEEK) 1 else DAYS_PER_WEEK
    series.forEachIndexed { index, (date, _) ->
        if ((series.lastIndex - index) % every != 0) return@forEachIndexed
        val label = measurer.measure("${date.monthValue}/${date.dayOfMonth}", style)
        val x = (plot.left + slot * (index + 0.5f) - label.size.width / 2f).coerceIn(plot.left, size.width - label.size.width)
        drawText(label, topLeft = Offset(x, plot.bottom + AXIS_GAP.toPx()))
    }
}

private fun DrawScope.drawBarValue(label: TextLayoutResult, plot: Rect, count: Int, index: Int, value: Double, top: Double) {
    val slot = plot.width / count
    val barTop = plot.bottom - maxOf(plot.height * (value / top).toFloat(), BAR_MIN_HEIGHT.toPx())
    val x = (plot.left + slot * (index + 0.5f) - label.size.width / 2f).coerceIn(plot.left, size.width - label.size.width)
    drawText(label, topLeft = Offset(x, barTop - label.size.height - Dimens.spaceXxs.toPx()))
}

/** The dashed box a chart or table shows when its range has nothing. */
@Composable
internal fun UsageEmptyBox(title: String, body: String) {
    val border = ConsoleColors.border
    Column(
        Modifier.fillMaxWidth().height(EMPTY_HEIGHT).drawBehind {
            drawRoundRect(
                border,
                cornerRadius = CornerRadius(Dimens.radiusMd.toPx()),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH.toPx(), DASH.toPx()))),
            )
        },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = ConsoleType.panelTitle, color = ConsoleColors.text)
        Spacer(Modifier.height(Dimens.spaceXsMd))
        Text(body, style = ConsoleType.bodySmall, color = ConsoleColors.muted)
    }
}

private val HEAT_MIN_PITCH = 13.dp
private const val HEAT_MIN_WEEKS = 13
private const val HEAT_MAX_WEEKS = 53
private val HEAT_GAP = 3.dp
private const val HEAT_CORNER_SHARE = 0.22f
private val HEAT_LABEL_HEIGHT = 24.dp
private val PICK_RING = 1.5.dp
private val LEGEND_SWATCH = 10.dp
private val LEGEND_CORNER = 2.dp
private val TREND_HEIGHT = 200.dp
private val AXIS_GAP = 6.dp
private const val BAR_FILL = 0.62f
private val BAR_MAX_WIDTH = 40.dp
private val BAR_MIN_HEIGHT = 2.dp
private val BAR_RADIUS = 4.dp
private const val DIMMED_ALPHA = 0.45f
private val EMPTY_HEIGHT = 180.dp
private val DASH = 5.dp

// The page's numbers: pure functions over the usage ledger and prices.

/** What each Token 活动 square shows: that day, its whole week, or everything up to that day. */
internal enum class HeatMode(@StringRes val label: Int) {
    DAILY(R.string.usage_heat_daily),
    WEEKLY(R.string.usage_heat_weekly),
    CUMULATIVE(R.string.usage_heat_cumulative),
}

/** What the trend chart's bars measure. */
internal enum class TrendMetric(@StringRes val label: Int, @StringRes val title: Int) {
    TOKENS(R.string.usage_metric_tokens, R.string.usage_trend_tokens),
    COST(R.string.usage_metric_cost, R.string.usage_trend_cost),
    ;

    /** An axis or bar value, token counts in [locale]'s units. */
    fun format(value: Double, locale: Locale): String =
        if (this == TOKENS) tokens(value.roundToLong(), locale) else yuanShort(value)
}

/** 时间范围 of the trend chart and the model table: the last [days] days, today included. */
internal enum class UsageRange(@StringRes val label: Int, val days: Int) {
    WEEK(R.string.usage_range_week, 7),
    MONTH(R.string.usage_range_month, 30),
}

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

/** Month labels as (column, "10月" or "Oct", by [locale]) at the column holding each month's 1st. */
internal fun heatmapMonths(today: LocalDate, weeks: Int, locale: Locale): List<Pair<Int, String>> {
    val start = heatmapStart(today, weeks)
    return (0 until weeks).mapNotNull { week ->
        val first = (0 until DAYS_PER_WEEK).map { start.plusWeeks(week.toLong()).plusDays(it.toLong()) }
            .firstOrNull { it.dayOfMonth == 1 } ?: return@mapNotNull null
        week to first.month.getDisplayName(DateTextStyle.SHORT, locale)
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

/** Token counts in the screen's units ([locale]): in Chinese 8532, 1.5 万, 3.3 亿; otherwise 8.5K, 15K, 3.4M, 1.2B. */
internal fun tokens(count: Long, locale: Locale): String =
    if (locale.language == CHINESE_LANGUAGE) chineseTokens(count) else englishTokens(count)

private fun chineseTokens(count: Long): String = when {
    count >= YI -> "${oneDecimal(count / YI.toDouble())} 亿"
    count >= WAN -> "${oneDecimal(count / WAN.toDouble())} 万"
    else -> count.toString()
}

/** Thousands, millions and billions; a count that rounds to 1000 of a unit is 1 of the next (1M, not 1000K). */
private fun englishTokens(count: Long): String {
    val (size, suffix) = ENGLISH_UNITS.firstOrNull { (size, _) -> count >= size - size / ROUNDS_UP_SHARE }
        ?: return count.toString()
    return "${oneDecimal(count / size.toDouble())}$suffix"
}

/** Yuan for an axis or a bar: as few decimals as the amount needs, at most four. */
internal fun yuanShort(amount: Double): String =
    if (amount == 0.0) "¥0" else "¥" + BigDecimal(amount).setScale(YUAN_DECIMALS, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

/** 「周日」 or "Sun", by [locale]. */
internal fun weekdayName(date: LocalDate, locale: Locale): String =
    date.dayOfWeek.getDisplayName(DateTextStyle.SHORT, locale)

private fun oneDecimal(value: Double): String = "%.1f".format(java.util.Locale.ROOT, value).removeSuffix(".0")

internal const val DAYS_PER_WEEK = 7
internal const val HEAT_LEVELS = 4
private const val YUAN_DECIMALS = 4
private const val ROUNDING_SLACK = 1e-9
private const val WAN = 10_000L
private const val YI = 100_000_000L
private const val CHINESE_LANGUAGE = "zh"
private val ENGLISH_UNITS = listOf(1_000_000_000L to "B", 1_000_000L to "M", 1_000L to "K")
/** Within 1/20000 of a unit, one decimal rounds up to it: 999,950 is 1M. */
private const val ROUNDS_UP_SHARE = 20_000L
