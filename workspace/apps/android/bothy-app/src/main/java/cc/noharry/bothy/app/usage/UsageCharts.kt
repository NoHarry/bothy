package cc.noharry.bothy.app.usage
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
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color
import java.time.LocalDate
import kotlin.math.min

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
                    .semantics { contentDescription = "Token 活动，近 $weeks 周" },
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
    val months = remember(today, weeks) { heatmapMonths(today, weeks) }
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
        Text("少", style = ConsoleType.meta, color = ConsoleColors.tertiary)
        (listOf(chart.empty) + chart.ramp).forEach { Box(Modifier.size(LEGEND_SWATCH).background(it, RoundedCornerShape(LEGEND_CORNER))) }
        Text("多", style = ConsoleType.meta, color = ConsoleColors.tertiary)
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
    val axis = remember(top, metric, labelStyle) { trendAxis(top, metric).map { it to measurer.measure(metric.format(it), labelStyle) } }
    val gutter = with(LocalDensity.current) { axis.maxOf { it.second.size.width } + AXIS_GAP.toPx() }
    val pick by rememberUpdatedState(onPick)
    val shown = picked ?: series.maxBy { it.second }.first
    Canvas(
        Modifier.fillMaxWidth().height(TREND_HEIGHT)
            .pointerInput(series, gutter) {
                detectTapGestures { offset ->
                    val slot = (size.width - gutter) / series.size
                    pick(series[((offset.x - gutter) / slot).toInt().coerceIn(0, series.lastIndex)].first)
                }
            }
            .semantics { contentDescription = "${metric.title}，${series.size} 天，峰值 ${metric.format(peak)}" },
    ) {
        val labelHeight = axis.first().second.size.height
        val plot = Rect(gutter, labelHeight + AXIS_GAP.toPx(), size.width, size.height - labelHeight - AXIS_GAP.toPx())
        drawTrendGrid(plot, top, axis, chart.grid)
        drawTrendBars(series, plot, top, picked, chart.bar)
        drawTrendDates(series, plot, measurer, labelStyle)
        series.indexOfFirst { it.first == shown }.takeIf { it >= 0 && series[it].second > 0 }?.let { index ->
            drawBarValue(measurer.measure(metric.format(series[index].second), valueStyle), plot, series.size, index, series[index].second, top)
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
