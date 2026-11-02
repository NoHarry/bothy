package cc.noharry.bothy.app.usage
import android.content.res.Resources
import androidx.annotation.StringRes
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.model.summary
import java.time.LocalDate
import java.util.Locale

/**
 * Settings → 用量与费用: tokens per day and model for every model call (agent rounds, voice acknowledgements, Jev),
 * priced from models.dev (USD per million tokens) or by hand, shown in yuan at the set rate. 用量统计 charts the
 * ledger; 模型价格 holds the rate, the sync and the per-model prices. Kept across Clear runtime data; nothing here
 * clears it.
 */
@Composable
internal fun UsagePane() {
    val usage by UsageStore.usage.collectAsState()
    val book by UsageStore.prices.collectAsState()
    var tab by rememberSaveable { mutableStateOf(UsageTab.STATS.name) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Dimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceLg)) {
            Text(
                stringResource(R.string.usage_title),
                style = ConsoleType.hero.copy(fontSize = TITLE_SIZE),
                color = ConsoleColors.text,
            )
            UsageSegmented(UsageTab.entries, UsageTab.valueOf(tab), { stringResource(it.label) }, framed = false) {
                tab = it.name
            }
        }
        when (UsageTab.valueOf(tab)) {
            UsageTab.STATS -> UsageStatsTab(usage, book)
            UsageTab.PRICES -> UsagePricesTab(usage, book)
        }
    }
}

private enum class UsageTab(@StringRes val label: Int) {
    STATS(R.string.usage_tab_stats),
    PRICES(R.string.usage_tab_prices),
}

@Composable
private fun UsageStatsTab(usage: UsageDays, book: PriceBook) {
    val today = LocalDate.now()
    val daily = remember(usage) { dailyTokens(usage) }
    var rangeName by rememberSaveable { mutableStateOf(UsageRange.WEEK.name) }
    val range = UsageRange.valueOf(rangeName)
    UsageSummaryCard(usageSummary(usage, book, today))
    UsageActivityCard(usage, book, daily, today)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.usage_time_range),
            Modifier.weight(1f),
            style = ConsoleType.panelTitle,
            color = ConsoleColors.text,
        )
        UsageSegmented(UsageRange.entries, range, { stringResource(it.label) }) { rangeName = it.name }
    }
    UsageTrendCard(usage, book, daily, today, range)
    UsageModelsCard(usage, book, today.minusDays(range.days - 1L))
    Text(
        stringResource(R.string.usage_tokens_note),
        style = ConsoleType.bodySmall,
        color = ConsoleColors.tertiary,
    )
}

@Composable
private fun UsageSummaryCard(summary: UsageSummary) {
    val locale = screenLocale()
    val metrics = listOf(
        tokens(summary.totalTokens, locale) to stringResource(R.string.usage_total_tokens),
        tokens(summary.peakDayTokens, locale) to stringResource(R.string.usage_peak_day_tokens),
        yuan(summary.totalCostCny) to stringResource(R.string.usage_total_cost),
        pluralStringResource(R.plurals.usage_days, summary.currentStreak, summary.currentStreak) to
            stringResource(R.string.usage_current_streak),
        pluralStringResource(R.plurals.usage_days, summary.longestStreak, summary.longestStreak) to
            stringResource(R.string.usage_longest_streak),
    )
    UsageCard(null) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= SUMMARY_ONE_ROW) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    metrics.forEachIndexed { index, (value, label) ->
                        if (index > 0) Box(Modifier.width(1.dp).height(METRIC_DIVIDER).background(ConsoleColors.border))
                        UsageMetric(value, label)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg)) {
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
    Column(Modifier.weight(1f).padding(horizontal = Dimens.spaceXs), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = ConsoleType.hero, color = ConsoleColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = ConsoleType.bodySmall, color = ConsoleColors.muted, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
private fun UsageActivityCard(usage: UsageDays, book: PriceBook, daily: Map<LocalDate, Long>, today: LocalDate) {
    var modeName by rememberSaveable { mutableStateOf(HeatMode.DAILY.name) }
    val mode = HeatMode.valueOf(modeName)
    var picked by remember { mutableStateOf<LocalDate?>(null) }
    val resources = LocalContext.current.resources
    val activity = stringResource(R.string.usage_token_activity)
    val modes: @Composable () -> Unit = {
        UsageSegmented(HeatMode.entries, mode, { stringResource(it.label) }) { modeName = it.name }
    }
    UsageCard(activity, modes) {
        UsageHeatmap(daily, today, mode, picked) { picked = if (picked == it) null else it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                picked?.let { resources.heatDetail(mode, it, usage, book, daily) }
                    ?: stringResource(R.string.usage_heatmap_hint),
                Modifier.weight(1f),
                style = ConsoleType.bodySmall,
                color = if (picked == null) ConsoleColors.tertiary else ConsoleColors.text,
            )
            UsageHeatLegend()
        }
    }
}

/** The picked square in words, for what the mode colours it by. */
private fun Resources.heatDetail(
    mode: HeatMode,
    date: LocalDate,
    usage: UsageDays,
    book: PriceBook,
    daily: Map<LocalDate, Long>,
): String {
    val locale = screenLocale
    return when (mode) {
        HeatMode.DAILY -> dayDetail(date, usage, book)
        HeatMode.WEEKLY -> {
            val monday = date.minusDays(date.dayOfWeek.value - 1L)
            val week = (0L until DAYS_PER_WEEK).map(monday::plusDays)
            getString(
                R.string.usage_week_detail,
                shortDate(monday), shortDate(week.last()), tokens(week.sumOf { daily[it] ?: 0L }, locale),
                yuan(cost(usage.filterKeys { it in week.map(LocalDate::toString) }, book)),
            )
        }
        HeatMode.CUMULATIVE -> {
            val total = tokens(daily.filterKeys { !it.isAfter(date) }.values.sum(), locale)
            getString(R.string.usage_cumulative_detail, date.toString(), total)
        }
    }
}

private fun Resources.dayDetail(date: LocalDate, usage: UsageDays, book: PriceBook): String {
    val models = usage[date.toString()].orEmpty()
    val total = models.values.fold(ModelUsage(), ModelUsage::plus)
    val locale = screenLocale
    return getQuantityString(
        R.plurals.usage_day_detail, total.requests.toInt(),
        date.toString(), weekdayName(date, locale),
        tokens(total.totalTokens, locale), tokens(total.inputTokens, locale), tokens(total.outputTokens, locale),
        yuan(cost(mapOf(date.toString() to models), book)), total.requests,
    )
}

private fun shortDate(date: LocalDate) = "${date.monthValue}/${date.dayOfMonth}"

/** The language the screen shows, which picks the units and names in its numbers and dates. */
private val Resources.screenLocale: Locale get() = configuration.locales[0]

/** [screenLocale] of the resources this composition shows text from. */
@Composable
internal fun screenLocale(): Locale = LocalContext.current.resources.screenLocale

@Composable
private fun UsageTrendCard(usage: UsageDays, book: PriceBook, daily: Map<LocalDate, Long>, today: LocalDate, range: UsageRange) {
    var metricName by rememberSaveable { mutableStateOf(TrendMetric.TOKENS.name) }
    val metric = TrendMetric.valueOf(metricName)
    val series = when (metric) {
        TrendMetric.TOKENS -> rangeSeries(today, range) { (daily[it] ?: 0L).toDouble() }
        TrendMetric.COST -> rangeSeries(today, range) { dayCost(usage, book, it) }
    }
    var picked by remember(range) { mutableStateOf<LocalDate?>(null) }
    val resources = LocalContext.current.resources
    val inRange = daysFrom(usage, series.first().first)
    val requests = inRange.values.sumOf { models -> models.values.sumOf { it.requests } }
    val header: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
            Text(
                pluralStringResource(
                    R.plurals.usage_cost_requests, requests.toInt(), yuan(cost(inRange, book)), requests,
                ),
                style = ConsoleType.bodySmall,
                color = ConsoleColors.muted,
            )
            UsageSegmented(TrendMetric.entries, metric, { stringResource(it.label) }) { metricName = it.name }
        }
    }
    UsageCard(stringResource(metric.title), header) {
        if (series.all { it.second == 0.0 }) {
            val unpriced = metric == TrendMetric.COST && series.any { (daily[it.first] ?: 0L) > 0 }
            UsageEmptyBox(
                stringResource(R.string.usage_empty_title),
                stringResource(if (unpriced) R.string.usage_empty_unpriced else R.string.usage_empty_range),
            )
        } else {
            UsageTrendBars(series, metric, picked) { picked = if (picked == it) null else it }
            Text(
                picked?.let { resources.dayDetail(it, usage, book) } ?: stringResource(R.string.usage_bars_hint),
                style = ConsoleType.bodySmall,
                color = if (picked == null) ConsoleColors.tertiary else ConsoleColors.text,
            )
        }
    }
}

@Composable
private fun UsageModelsCard(usage: UsageDays, book: PriceBook, from: LocalDate) {
    val locale = screenLocale()
    val models = modelTotals(usage, from).entries.sortedWith(
        compareByDescending<Map.Entry<String, ModelUsage>> { (model, use) -> book.prices[model]?.let(use::costUsd) ?: 0.0 }
            .thenByDescending { it.value.totalTokens },
    )
    UsageCard(stringResource(R.string.usage_model_usage)) {
        if (models.isEmpty()) {
            UsageEmptyBox(stringResource(R.string.usage_no_calls_title), stringResource(R.string.usage_no_calls_body))
        } else {
            ModelRow(
                stringResource(R.string.usage_model), stringResource(R.string.usage_requests),
                stringResource(R.string.usage_input_with_cache_hits), stringResource(R.string.usage_output),
                stringResource(R.string.usage_cost), header = true,
            )
        }
        models.forEach { (model, use) ->
            HorizontalDivider(color = ConsoleColors.borderSubtle)
            val price = book.prices[model]
            ModelRow(
                model,
                "${use.requests}",
                stringResource(
                    R.string.usage_input_and_cache,
                    tokens(use.inputTokens, locale), tokens(use.cacheReadTokens, locale),
                ),
                tokens(use.outputTokens, locale),
                price?.let { yuan(use.costUsd(it) * book.usdToCny) } ?: stringResource(R.string.usage_unpriced),
                source = price?.let { priceSource(it) } ?: stringResource(R.string.usage_unpriced_hint),
                unpriced = price == null,
            )
        }
    }
}

@Composable
private fun ModelRow(model: String, requests: String, input: String, output: String, fee: String, header: Boolean = false, source: String? = null, unpriced: Boolean = false) {
    val tone = if (header) ConsoleColors.tertiary else ConsoleColors.text
    val style = if (header) ConsoleType.meta else ConsoleType.bodySmall
    Row(Modifier.fillMaxWidth().padding(vertical = Dimens.spaceXs), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(2.4f)) {
            Text(model, style = style, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis)
            source?.let { Text(it, style = ConsoleType.meta, color = if (unpriced) ConsoleColors.warning else ConsoleColors.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Text(requests, Modifier.weight(0.7f), style = style, color = tone, textAlign = TextAlign.End)
        Text(input, Modifier.weight(1.6f), style = style, color = tone, textAlign = TextAlign.End)
        Text(output, Modifier.weight(1f), style = style, color = tone, textAlign = TextAlign.End)
        Text(fee, Modifier.weight(1f), style = style, color = if (unpriced) ConsoleColors.warning else tone, textAlign = TextAlign.End)
    }
}

/** A rounded card with an optional title row; [action] sits at the title's end. */
@Composable
internal fun UsageCard(title: String?, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Dimens.radiusLg)
    Column(
        Modifier.fillMaxWidth().background(ConsoleColors.elevated, shape).border(1.dp, ConsoleColors.borderSubtle, shape).padding(Dimens.spaceLg),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = ConsoleType.panelTitle, color = ConsoleColors.text)
                action?.invoke()
            }
        }
        content()
    }
}

/** Pill tabs in the console's segmented style; [framed] puts them in a track, as inside cards. */
@Composable
internal fun <T> UsageSegmented(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    framed: Boolean = true,
    onSelect: (T) -> Unit,
) {
    val colors = ConsoleColors
    val shape = RoundedCornerShape(Dimens.radiusFull)
    val track = if (framed) Modifier.background(colors.bg, shape).border(1.dp, colors.borderSubtle, shape).padding(SEGMENT_INSET) else Modifier
    Row(track, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXxs)) {
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
                    Modifier.padding(horizontal = Dimens.spaceMd + Dimens.spaceXxs, vertical = Dimens.spaceXsMd + 1.dp),
                    style = ConsoleType.bodySmall.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                )
            }
        }
    }
}

/** Yuan for [days] at the book's rate; models without a price add nothing. */
internal fun cost(days: UsageDays, book: PriceBook): Double =
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
