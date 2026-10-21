package cc.noharry.bothy.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/**
 * Settings → 用量与费用: tokens per day and model for every model call (agent rounds, voice acknowledgements, Jev),
 * priced from models.dev (USD per million tokens) or by hand, shown in yuan at the set rate. 用量统计 charts the
 * ledger; 模型价格 holds the rate, the sync and the per-model prices. Kept across Clear runtime data; nothing here
 * clears it.
 */
@Composable
internal fun DebugUsagePane() {
    val usage by DebugUsageStore.usage.collectAsState()
    val book by DebugUsageStore.prices.collectAsState()
    var tab by rememberSaveable { mutableStateOf(UsageTab.STATS.name) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(DebugDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceLg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceLg)) {
            Text("用量与费用", style = DebugType.hero.copy(fontSize = TITLE_SIZE), color = DebugColors.text)
            UsageSegmented(UsageTab.entries, UsageTab.valueOf(tab), UsageTab::label, framed = false) { tab = it.name }
        }
        when (UsageTab.valueOf(tab)) {
            UsageTab.STATS -> UsageStatsTab(usage, book)
            UsageTab.PRICES -> DebugUsagePricesTab(usage, book)
        }
    }
}

private enum class UsageTab(val label: String) { STATS("用量统计"), PRICES("模型价格") }

@Composable
private fun UsageStatsTab(usage: DebugUsageDays, book: DebugPriceBook) {
    val today = LocalDate.now()
    val daily = remember(usage) { dailyTokens(usage) }
    var rangeName by rememberSaveable { mutableStateOf(DebugUsageRange.WEEK.name) }
    val range = DebugUsageRange.valueOf(rangeName)
    UsageSummaryCard(usageSummary(usage, book, today))
    UsageActivityCard(usage, book, daily, today)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("时间范围", Modifier.weight(1f), style = DebugType.panelTitle, color = DebugColors.text)
        UsageSegmented(DebugUsageRange.entries, range, DebugUsageRange::label) { rangeName = it.name }
    }
    UsageTrendCard(usage, book, daily, today, range)
    UsageModelsCard(usage, book, today.minusDays(range.days - 1L))
    Text(
        "Token 为输入（含缓存命中）+ 输出（含推理）。费用按「模型价格」里的单价和汇率折算，未定价的模型不计费。",
        style = DebugType.bodySmall,
        color = DebugColors.tertiary,
    )
}

@Composable
private fun UsageSummaryCard(summary: DebugUsageSummary) {
    val metrics = listOf(
        tokens(summary.totalTokens) to "累计 Token 数",
        tokens(summary.peakDayTokens) to "单日峰值 Token 数",
        yuan(summary.totalCostCny) to "累计费用",
        "${summary.currentStreak} 天" to "当前连续天数",
        "${summary.longestStreak} 天" to "最长连续天数",
    )
    UsageCard(null) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= SUMMARY_ONE_ROW) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    metrics.forEachIndexed { index, (value, label) ->
                        if (index > 0) Box(Modifier.width(1.dp).height(METRIC_DIVIDER).background(DebugColors.border))
                        UsageMetric(value, label)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceLg)) {
                    metrics.chunked(METRICS_PER_ROW).forEach { row ->
                        Row { row.forEach { (value, label) -> UsageMetric(value, label) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.UsageMetric(value: String, label: String) {
    Column(Modifier.weight(1f).padding(horizontal = DebugDimens.spaceXs), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = DebugType.hero, color = DebugColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = DebugType.bodySmall, color = DebugColors.muted, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
private fun UsageActivityCard(usage: DebugUsageDays, book: DebugPriceBook, daily: Map<LocalDate, Long>, today: LocalDate) {
    var modeName by rememberSaveable { mutableStateOf(DebugHeatMode.DAILY.name) }
    val mode = DebugHeatMode.valueOf(modeName)
    var picked by remember { mutableStateOf<LocalDate?>(null) }
    UsageCard("Token 活动", { UsageSegmented(DebugHeatMode.entries, mode, DebugHeatMode::label) { modeName = it.name } }) {
        UsageHeatmap(daily, today, mode, picked) { picked = if (picked == it) null else it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                picked?.let { heatDetail(mode, it, usage, book, daily) } ?: "点格子查看当天用量",
                Modifier.weight(1f),
                style = DebugType.bodySmall,
                color = if (picked == null) DebugColors.tertiary else DebugColors.text,
            )
            UsageHeatLegend()
        }
    }
}

/** The picked square in words, for what the mode colours it by. */
private fun heatDetail(mode: DebugHeatMode, date: LocalDate, usage: DebugUsageDays, book: DebugPriceBook, daily: Map<LocalDate, Long>): String =
    when (mode) {
        DebugHeatMode.DAILY -> dayDetail(date, usage, book)
        DebugHeatMode.WEEKLY -> {
            val monday = date.minusDays(date.dayOfWeek.value - 1L)
            val week = (0L until DAYS_PER_WEEK).map(monday::plusDays)
            "${shortDate(monday)}–${shortDate(week.last())} 这周 · ${tokens(week.sumOf { daily[it] ?: 0L })} Token · " +
                yuan(cost(usage.filterKeys { it in week.map(LocalDate::toString) }, book))
        }
        DebugHeatMode.CUMULATIVE -> "截至 $date 累计 ${tokens(daily.filterKeys { !it.isAfter(date) }.values.sum())} Token"
    }

private fun dayDetail(date: LocalDate, usage: DebugUsageDays, book: DebugPriceBook): String {
    val models = usage[date.toString()].orEmpty()
    val total = models.values.fold(DebugModelUsage(), DebugModelUsage::plus)
    return "$date ${weekdayName(date)} · ${tokens(total.totalTokens)} Token（输入 ${tokens(total.inputTokens)} / 输出 ${tokens(total.outputTokens)}）" +
        " · ${yuan(cost(mapOf(date.toString() to models), book))} · ${total.requests} 次"
}

private fun shortDate(date: LocalDate) = "${date.monthValue}/${date.dayOfMonth}"

@Composable
private fun UsageTrendCard(usage: DebugUsageDays, book: DebugPriceBook, daily: Map<LocalDate, Long>, today: LocalDate, range: DebugUsageRange) {
    var metricName by rememberSaveable { mutableStateOf(DebugTrendMetric.TOKENS.name) }
    val metric = DebugTrendMetric.valueOf(metricName)
    val series = when (metric) {
        DebugTrendMetric.TOKENS -> rangeSeries(today, range) { (daily[it] ?: 0L).toDouble() }
        DebugTrendMetric.COST -> rangeSeries(today, range) { dayCost(usage, book, it) }
    }
    var picked by remember(range) { mutableStateOf<LocalDate?>(null) }
    val inRange = daysFrom(usage, series.first().first)
    val requests = inRange.values.sumOf { models -> models.values.sumOf { it.requests } }
    val header: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceMd)) {
            Text("${yuan(cost(inRange, book))} · $requests 次请求", style = DebugType.bodySmall, color = DebugColors.muted)
            UsageSegmented(DebugTrendMetric.entries, metric, DebugTrendMetric::label) { metricName = it.name }
        }
    }
    UsageCard(metric.title, header) {
        if (series.all { it.second == 0.0 }) {
            val unpriced = metric == DebugTrendMetric.COST && series.any { (daily[it.first] ?: 0L) > 0 }
            UsageEmptyBox("还没有可展示的数据", if (unpriced) "当前区间用到的模型还没有价格，到「模型价格」同步或手填。" else "当前区间暂无可展示的用量数据。")
        } else {
            UsageTrendBars(series, metric, picked) { picked = if (picked == it) null else it }
            Text(
                picked?.let { dayDetail(it, usage, book) } ?: "点柱子查看当天用量；标注的是区间峰值",
                style = DebugType.bodySmall,
                color = if (picked == null) DebugColors.tertiary else DebugColors.text,
            )
        }
    }
}

@Composable
private fun UsageModelsCard(usage: DebugUsageDays, book: DebugPriceBook, from: LocalDate) {
    val models = modelTotals(usage, from).entries.sortedWith(
        compareByDescending<Map.Entry<String, DebugModelUsage>> { (model, use) -> book.prices[model]?.let(use::costUsd) ?: 0.0 }
            .thenByDescending { it.value.totalTokens },
    )
    UsageCard("模型用量") {
        if (models.isEmpty()) UsageEmptyBox("还没有模型调用", "当前区间没有记录到模型用量。")
        else ModelRow("模型", "请求", "输入（缓存命中）", "输出", "费用", header = true)
        models.forEach { (model, use) ->
            HorizontalDivider(color = DebugColors.borderSubtle)
            val price = book.prices[model]
            ModelRow(
                model,
                "${use.requests}",
                "${tokens(use.inputTokens)}（${tokens(use.cacheReadTokens)}）",
                tokens(use.outputTokens),
                price?.let { yuan(use.costUsd(it) * book.usdToCny) } ?: "未定价",
                source = price?.source ?: "到「模型价格」同步或手填",
                unpriced = price == null,
            )
        }
    }
}

@Composable
private fun ModelRow(model: String, requests: String, input: String, output: String, fee: String, header: Boolean = false, source: String? = null, unpriced: Boolean = false) {
    val tone = if (header) DebugColors.tertiary else DebugColors.text
    val style = if (header) DebugType.meta else DebugType.bodySmall
    Row(Modifier.fillMaxWidth().padding(vertical = DebugDimens.spaceXs), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(2.4f)) {
            Text(model, style = style, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis)
            source?.let { Text(it, style = DebugType.meta, color = if (unpriced) DebugColors.warning else DebugColors.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Text(requests, Modifier.weight(0.7f), style = style, color = tone, textAlign = TextAlign.End)
        Text(input, Modifier.weight(1.6f), style = style, color = tone, textAlign = TextAlign.End)
        Text(output, Modifier.weight(1f), style = style, color = tone, textAlign = TextAlign.End)
        Text(fee, Modifier.weight(1f), style = style, color = if (unpriced) DebugColors.warning else tone, textAlign = TextAlign.End)
    }
}

/** A rounded card with an optional title row; [action] sits at the title's end. */
@Composable
internal fun UsageCard(title: String?, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(DebugDimens.radiusLg)
    Column(
        Modifier.fillMaxWidth().background(DebugColors.elevated, shape).border(1.dp, DebugColors.borderSubtle, shape).padding(DebugDimens.spaceLg),
        verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceMd),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = DebugType.panelTitle, color = DebugColors.text)
                action?.invoke()
            }
        }
        content()
    }
}

/** Pill tabs in the console's segmented style; [framed] puts them in a track, as inside cards. */
@Composable
internal fun <T> UsageSegmented(options: List<T>, selected: T, label: (T) -> String, framed: Boolean = true, onSelect: (T) -> Unit) {
    val colors = DebugColors
    val shape = RoundedCornerShape(DebugDimens.radiusFull)
    val track = if (framed) Modifier.background(colors.bg, shape).border(1.dp, colors.borderSubtle, shape).padding(SEGMENT_INSET) else Modifier
    Row(track, horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceXxs)) {
        options.forEach { option ->
            val on = option == selected
            Surface(
                onClick = { onSelect(option) },
                modifier = Modifier.semantics {
                    role = Role.Tab
                    this.selected = on
                },
                shape = shape,
                color = if (on) colors.ink else Color.Transparent,
                contentColor = if (!on) colors.muted else if (colors.isDark) colors.bg else Color.White,
            ) {
                Text(
                    label(option),
                    Modifier.padding(horizontal = DebugDimens.spaceMd + DebugDimens.spaceXxs, vertical = DebugDimens.spaceXsMd + 1.dp),
                    style = DebugType.bodySmall.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                )
            }
        }
    }
}

/** Yuan for [days] at the book's rate; models without a price add nothing. */
internal fun cost(days: DebugUsageDays, book: DebugPriceBook): Double =
    days.values.sumOf { models -> models.entries.sumOf { (model, usage) -> book.prices[model]?.let(usage::costUsd) ?: 0.0 } } * book.usdToCny

internal fun yuan(amount: Double): String = when {
    amount == 0.0 -> "¥0"
    amount < 1 -> "¥%.4f".format(amount)
    else -> "¥%.2f".format(amount)
}

private val TITLE_SIZE = 24.sp
private val SUMMARY_ONE_ROW = 600.dp
private const val METRICS_PER_ROW = 3
private val METRIC_DIVIDER = 32.dp
private val SEGMENT_INSET = 3.dp
