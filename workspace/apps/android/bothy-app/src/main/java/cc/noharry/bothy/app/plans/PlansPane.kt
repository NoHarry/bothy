package cc.noharry.bothy.app.plans
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.a2a.describe
import cc.noharry.bothy.app.console.BothyAlertDialog
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.switchColors
import cc.noharry.bothy.app.model.describe
import cc.noharry.bothy.app.model.summary
import cc.noharry.bothy.app.usage.UsageCard
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The console's 计划 tab, right of A2A (owner 2026-10-15: 「这个模块要有可见性，最好在首页顶部的A2A那一栏右边再加一个可切换
 * 的tab，然后也可以让用户点击是否启用其中某一条的开启，关闭，和删除」): every plan with when it comes due next, a switch
 * to pause or resume it, deletion, and what came due lately. Plans are made by talking to 小博 (`plan_manage`).
 */
@Composable
internal fun PlansLayout(tabs: (@Composable () -> Unit)?, modifier: Modifier) {
    val context = LocalContext.current
    val plans by PlanRunner.plans.collectAsState()
    val history by PlanRunner.history.collectAsState()
    val feeds by PlanWatcher.states.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var deleting by remember { mutableStateOf<Plan?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { PlanRunner.book(context) }
        while (true) {
            now = System.currentTimeMillis()
            delay(CLOCK_TICK_MS)
        }
    }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(Dimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
    ) {
        tabs?.invoke()
        PlansHeader(plans, now)
        if (plans.isEmpty()) PlansEmpty()
        val (ended, rest) = plans.partition { it.end != null }
        val (going, paused) = rest.partition { it.enabled }
        PlanSection(stringResource(R.string.plans_active), going.sortedBy { it.nextDueMillis(now, PlanRunner.zone()) ?: Long.MAX_VALUE }, now, feeds, onDelete = { deleting = it })
        PlanSection(stringResource(R.string.plans_paused), paused, now, feeds, onDelete = { deleting = it })
        PlanSection(stringResource(R.string.plans_ended), ended.sortedByDescending { it.lastFiredAtMillis ?: it.lastDueAtMillis ?: it.createdAtMillis }, now, feeds, onDelete = { deleting = it })
        PlanHistory(history)
        Text(stringResource(R.string.plans_footer), style = ConsoleType.bodySmall, color = ConsoleColors.tertiary)
    }
    deleting?.let { plan ->
        BothyAlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.plans_delete_title)) },
            text = {
                val time = plan.trigger.describeOnScreen(context, now, PlanRunner.zone())
                Text(stringResource(R.string.plans_delete_text, plan.title, time))
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onPlans(context) { PlanRunner.delete(it, plan.id) }
                }) { Text(stringResource(R.string.common_delete), color = ConsoleColors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun PlansHeader(plans: List<Plan>, now: Long) {
    val context = LocalContext.current
    val going = plans.filter { it.active }
    val next = going.mapNotNull { plan -> plan.nextDueMillis(now, PlanRunner.zone())?.let { plan to it } }.minByOrNull { it.second }
    val watching = going.count { it.trigger is PlanTrigger.Watch }
    val summary = listOfNotNull(
        if (going.isEmpty()) {
            stringResource(R.string.plans_none_active)
        } else {
            pluralStringResource(R.plurals.plans_active_count, going.size, going.size)
        },
        next?.let { (plan, at) ->
            val time = describeMomentOnScreen(context, at, now, PlanRunner.zone())
            stringResource(R.string.plans_next_plan, time, plan.title)
        },
        watching.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.plans_watching_count, it, it) },
    ).joinToString(" · ")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceLg)) {
        Text(stringResource(R.string.plans_title), style = ConsoleType.hero.copy(fontSize = PLANS_TITLE_SIZE), color = ConsoleColors.text)
        Text(summary, style = ConsoleType.bodySmall, color = ConsoleColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PlansEmpty() {
    UsageCard(stringResource(R.string.plans_empty_title)) {
        Text(stringResource(R.string.plans_empty_hint), style = ConsoleType.bodySmall, color = ConsoleColors.muted)
        PLAN_EXAMPLES.forEach { Text(stringResource(it), style = ConsoleType.body, color = ConsoleColors.text) }
    }
}

/** A titled group of plan cards, two to a row when there is room; nothing when empty. */
@Composable
private fun PlanSection(title: String, plans: List<Plan>, now: Long, feeds: Map<String, SubscriptionState>, onDelete: (Plan) -> Unit) {
    if (plans.isEmpty()) return
    Text("$title · ${plans.size}", style = ConsoleType.panelTitle, color = ConsoleColors.text)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= PLAN_TWO_COLUMNS) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
            plans.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
                    row.forEach { plan -> Box(Modifier.weight(1f)) { PlanCard(plan, now, feeds[plan.id], onDelete) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** One plan: its kind and name, when, what it says or does, where it stands; the switch and delete at the side. */
@Composable
private fun PlanCard(plan: Plan, now: Long, feed: SubscriptionState?, onDelete: (Plan) -> Unit) {
    val context = LocalContext.current
    val zone = PlanRunner.zone()
    UsageCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindPill(plan)
            Spacer(Modifier.width(Dimens.spaceSm))
            Column(Modifier.weight(1f)) {
                Text(plan.title, style = ConsoleType.panelTitle, color = if (plan.active) ConsoleColors.text else ConsoleColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(plan.trigger.describeOnScreen(context, now, zone), style = ConsoleType.bodySmall, color = ConsoleColors.muted)
            }
            Switch(
                checked = plan.active,
                onCheckedChange = { on -> onPlans(context) { PlanRunner.setEnabled(it, plan.id, on) } },
                enabled = plan.end == null,
                colors = switchColors(),
            )
            IconButton(onClick = { onDelete(plan) }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.plans_delete_plan),
                    tint = ConsoleColors.muted,
                )
            }
        }
        Text(planDoes(context, plan), style = ConsoleType.body, color = ConsoleColors.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(planStateLine(context, plan, now, feed), style = ConsoleType.meta, color = if (plan.end == PlanEnd.MISSED) ConsoleColors.warning else ConsoleColors.tertiary)
    }
}

@Composable
private fun KindPill(plan: Plan) {
    val label = if (plan.actions.isNotEmpty()) R.string.plans_kind_auto else when (val trigger = plan.trigger) {
        is PlanTrigger.Once -> R.string.plans_kind_once
        is PlanTrigger.Repeat -> R.string.plans_kind_repeat
        is PlanTrigger.Watch -> R.string.plans_kind_watch
    }
    Text(
        stringResource(label),
        style = ConsoleType.meta,
        color = ConsoleColors.accent,
        modifier = Modifier.background(ConsoleColors.accentSoft, RoundedCornerShape(Dimens.radiusFull))
            .padding(horizontal = Dimens.spaceSm, vertical = Dimens.spaceXxs),
    )
}

@Composable
private fun PlanHistory(history: List<PlanFiring>) {
    if (history.isEmpty()) return
    UsageCard(stringResource(R.string.plans_history_title)) {
        history.take(MAX_SHOWN_FIRINGS).forEachIndexed { index, firing ->
            if (index > 0) HorizontalDivider(color = ConsoleColors.borderSubtle)
            Row {
                Text(stamp(firing.atMillis), Modifier.width(HISTORY_TIME_WIDTH), style = ConsoleType.bodySmall, color = ConsoleColors.muted)
                Text(firing.title, Modifier.weight(1f), style = ConsoleType.bodySmall, color = ConsoleColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(firing.note, style = ConsoleType.bodySmall, color = ConsoleColors.tertiary)
            }
        }
    }
}

/** What a plan does: its car controls, 小博's task, the words it says; 「到点」 for a time, 「触发时」 for a watch. */
internal fun planDoes(context: Context, plan: Plan): String {
    val watch = plan.trigger is PlanTrigger.Watch
    fun line(@StringRes due: Int, @StringRes triggered: Int, what: String) =
        context.getString(if (watch) triggered else due, what)
    val separator = context.getString(R.string.plans_list_separator)
    return listOfNotNull(
        plan.actions.takeIf { it.isNotEmpty() }?.let { actions ->
            val labels = actions.joinToString(separator) { it.label }
            line(R.string.plans_does_auto_due, R.string.plans_does_auto_triggered, labels)
        },
        plan.instruction?.let { line(R.string.plans_does_task_due, R.string.plans_does_task_triggered, it) },
        // A task's words are 小博's to say; only a reminder says its message.
        plan.message.takeIf { it.isNotBlank() && plan.instruction == null }
            ?.let { line(R.string.plans_does_remind_due, R.string.plans_does_remind_triggered, it) },
    ).joinToString(context.getString(R.string.plans_clause_separator))
}

/**
 * Where a plan stands, in a line: 「下次：今天 16:30 · 1 小时 20 分钟后」「已暂停」「已完成 · 10/15 16:30」, or for a
 * watch how it is fed ([feed]: 实时 by push, 定时读取 on its pace, 未连接) and how often it fired.
 */
internal fun planStateLine(context: Context, plan: Plan, now: Long, feed: SubscriptionState? = null): String {
    val zone = PlanRunner.zone()
    val res = context.resources
    val fired = plan.lastFiredAtMillis?.let { context.getString(R.string.plans_state_last, stamp(it)) }
    return when {
        plan.end == PlanEnd.DONE ->
            listOfNotNull(context.getString(R.string.plans_state_done), fired).joinToString(" · ")
        plan.end == PlanEnd.MISSED ->
            context.getString(R.string.plans_state_missed, plan.lastDueAtMillis?.let(::stamp) ?: "—")
        !plan.enabled -> listOfNotNull(context.getString(R.string.plans_paused), fired).joinToString(" · ")
        plan.trigger is PlanTrigger.Watch -> {
            val watch = plan.trigger.watch
            val how = when (feed) {
                null, SubscriptionState.NO_PUSH, SubscriptionState.REFUSED -> {
                    val seconds = (watch.pollMillis / MILLIS_PER_SECOND).toInt()
                    res.getQuantityString(R.plurals.plans_state_poll, seconds, seconds)
                }
                else -> context.getString(feed.label)
            }
            val count = res.getQuantityString(R.plurals.plans_state_fired, plan.firedCount, plan.firedCount)
            listOfNotNull(watch.server, how, count, fired).joinToString(" · ")
        }
        else -> plan.nextDueMillis(now, zone)
            ?.let { next ->
                val moment = describeMomentOnScreen(context, next, now, zone)
                context.getString(R.string.plans_state_next, moment, untilText(context, next - now))
            }
            ?: context.getString(R.string.plans_active)
    }
}

/** 「3 分钟后」「1 小时 20 分钟后」「2 天后」; 「马上」 under a minute. */
internal fun untilText(context: Context, millis: Long): String {
    val minutes = ((millis + MILLIS_PER_MINUTE - 1) / MILLIS_PER_MINUTE).toInt()
    val res = context.resources
    return when {
        millis <= 0 -> context.getString(R.string.plans_until_now)
        minutes < MINUTES_PER_HOUR -> res.getQuantityString(R.plurals.plans_until_minutes, minutes, minutes)
        minutes < MINUTES_PER_DAY -> {
            val hours = minutes / MINUTES_PER_HOUR
            val rest = minutes % MINUTES_PER_HOUR
            if (rest > 0) context.getString(R.string.plans_until_hours_minutes, hours, rest)
            else res.getQuantityString(R.plurals.plans_until_hours, hours, hours)
        }
        else -> (minutes / MINUTES_PER_DAY).let { res.getQuantityString(R.plurals.plans_until_days, it, it) }
    }
}

/** [PlanTrigger.describe] for the page, in the app's language; the model keeps reading the Chinese one. */
internal fun PlanTrigger.describeOnScreen(context: Context, now: Long, zone: ZoneId): String = when (this) {
    is PlanTrigger.Once -> describeMomentOnScreen(context, atMillis, now, zone)
    is PlanTrigger.Repeat ->
        context.getString(R.string.plans_repeat_at, describeDaysOnScreen(context, days), screenClock(minuteOfDay))
    is PlanTrigger.Watch -> {
        val held = if (holdSeconds > 0) {
            context.resources.getQuantityString(R.plurals.plans_watch_hold, holdSeconds, watch.text, holdSeconds)
        } else {
            watch.text
        }
        context.getString(if (repeat) R.string.plans_watch_every_time else R.string.plans_watch_once, held)
    }
}

/** [describeMoment] for the page: 「今天 16:30」「明天 08:00」「10月20日 周二 08:00」, "Today 16:30", "Tue 10/20 08:00". */
internal fun describeMomentOnScreen(context: Context, atMillis: Long, now: Long, zone: ZoneId): String {
    val at = Instant.ofEpochMilli(atMillis).atZone(zone)
    val days = at.toLocalDate().toEpochDay() - Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
    val time = screenClock(at.hour * MINUTES_PER_HOUR + at.minute)
    return when (days) {
        0L -> context.getString(R.string.plans_moment_today, time)
        1L -> context.getString(R.string.plans_moment_tomorrow, time)
        2L -> context.getString(R.string.plans_moment_day_after, time)
        -1L -> context.getString(R.string.plans_moment_yesterday, time)
        else -> {
            val weekday = context.getString(SCREEN_WEEKDAYS[at.dayOfWeek.value - 1])
            context.getString(R.string.plans_moment_date, at.monthValue, at.dayOfMonth, weekday, time)
        }
    }
}

private fun describeDaysOnScreen(context: Context, days: Set<Int>): String = when (days) {
    (1..SCREEN_WEEKDAYS.size).toSet() -> context.getString(R.string.plans_days_daily)
    (1..5).toSet() -> context.getString(R.string.plans_days_weekdays)
    setOf(6, 7) -> context.getString(R.string.plans_days_weekends)
    else -> {
        val separator = context.getString(R.string.plans_list_separator)
        val names = days.sorted().joinToString(separator) { context.getString(SCREEN_WEEKDAYS[it - 1]) }
        context.getString(R.string.plans_days_some, names)
    }
}

private fun screenClock(minuteOfDay: Int): String =
    String.format(Locale.ROOT, "%02d:%02d", minuteOfDay / MINUTES_PER_HOUR, minuteOfDay % MINUTES_PER_HOUR)

/** Plan writes go to disk and the alarm: off the main thread. */
private fun onPlans(context: Context, action: (Context) -> Unit) {
    val app = context.applicationContext
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { action(app) }
}

private fun stamp(millis: Long): String = SimpleDateFormat("M/d HH:mm", Locale.ROOT).format(Date(millis))

private val PLAN_EXAMPLES = listOf(
    R.string.plans_example_parcel, R.string.plans_example_badge, R.string.plans_example_fare,
    R.string.plans_example_weather, R.string.plans_example_umbrella, R.string.plans_example_air,
)
private val SCREEN_WEEKDAYS = listOf(
    R.string.plans_weekday_mon, R.string.plans_weekday_tue, R.string.plans_weekday_wed, R.string.plans_weekday_thu,
    R.string.plans_weekday_fri, R.string.plans_weekday_sat, R.string.plans_weekday_sun,
)
private val PLANS_TITLE_SIZE = 24.sp
private val PLAN_TWO_COLUMNS = 900.dp
private val HISTORY_TIME_WIDTH = 96.dp
private const val MAX_SHOWN_FIRINGS = 10
private const val CLOCK_TICK_MS = 30_000L
private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 24 * 60
