package cc.noharry.bothy.app.plans
import android.content.Context
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        PlanSection("进行中", going.sortedBy { it.nextDueMillis(now, PlanRunner.zone()) ?: Long.MAX_VALUE }, now, feeds, onDelete = { deleting = it })
        PlanSection("已暂停", paused, now, feeds, onDelete = { deleting = it })
        PlanSection("已结束", ended.sortedByDescending { it.lastFiredAtMillis ?: it.lastDueAtMillis ?: it.createdAtMillis }, now, feeds, onDelete = { deleting = it })
        PlanHistory(history)
        Text(PLANS_FOOTER, style = ConsoleType.bodySmall, color = ConsoleColors.tertiary)
    }
    deleting?.let { plan ->
        BothyAlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这个计划？") },
            text = { Text("「${plan.title}」 · ${plan.trigger.describe(now, PlanRunner.zone())}") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onPlans(context) { PlanRunner.delete(it, plan.id) }
                }) { Text("删除", color = ConsoleColors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun PlansHeader(plans: List<Plan>, now: Long) {
    val going = plans.filter { it.active }
    val next = going.mapNotNull { plan -> plan.nextDueMillis(now, PlanRunner.zone())?.let { plan to it } }.minByOrNull { it.second }
    val watching = going.count { it.trigger is PlanTrigger.Watch }
    val summary = listOfNotNull(
        if (going.isEmpty()) "没有进行中的计划" else "${going.size} 个进行中",
        next?.let { (plan, at) -> "下一个：${describeMoment(at, now, PlanRunner.zone())} ${plan.title}" },
        watching.takeIf { it > 0 }?.let { "$it 个在看状态" },
    ).joinToString(" · ")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceLg)) {
        Text("计划", style = ConsoleType.hero.copy(fontSize = PLANS_TITLE_SIZE), color = ConsoleColors.text)
        Text(summary, style = ConsoleType.bodySmall, color = ConsoleColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PlansEmpty() {
    UsageCard("还没有计划") {
        Text("对小博说一句就能安排，到点时小博会弹出卡片并念给你听：", style = ConsoleType.bodySmall, color = ConsoleColors.muted)
        PLAN_EXAMPLES.forEach { Text("「$it」", style = ConsoleType.body, color = ConsoleColors.text) }
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
                Text(plan.trigger.describe(now, zone), style = ConsoleType.bodySmall, color = ConsoleColors.muted)
            }
            Switch(
                checked = plan.active,
                onCheckedChange = { on -> onPlans(context) { PlanRunner.setEnabled(it, plan.id, on) } },
                enabled = plan.end == null,
                colors = switchColors(),
            )
            IconButton(onClick = { onDelete(plan) }) { Icon(Icons.Default.Delete, contentDescription = "删除计划", tint = ConsoleColors.muted) }
        }
        Text(planDoes(plan), style = ConsoleType.body, color = ConsoleColors.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(planStateLine(plan, now, feed), style = ConsoleType.meta, color = if (plan.end == PlanEnd.MISSED) ConsoleColors.warning else ConsoleColors.tertiary)
    }
}

@Composable
private fun KindPill(plan: Plan) {
    val label = if (plan.actions.isNotEmpty()) "自动" else when (val trigger = plan.trigger) {
        is PlanTrigger.Once -> "一次"
        is PlanTrigger.Repeat -> "重复"
        is PlanTrigger.Watch -> "状态"
    }
    Text(
        label,
        style = ConsoleType.meta,
        color = ConsoleColors.accent,
        modifier = Modifier.background(ConsoleColors.accentSoft, RoundedCornerShape(Dimens.radiusFull))
            .padding(horizontal = Dimens.spaceSm, vertical = Dimens.spaceXxs),
    )
}

@Composable
private fun PlanHistory(history: List<PlanFiring>) {
    if (history.isEmpty()) return
    UsageCard("最近触发") {
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
internal fun planDoes(plan: Plan): String {
    val at = if (plan.trigger is PlanTrigger.Watch) "触发时" else "到点"
    return listOfNotNull(
        plan.actions.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "${at}自动：") { it.label },
        plan.instruction?.let { "${at}让小博去办：$it" },
        // A task's words are 小博's to say; only a reminder says its message.
        plan.message.takeIf { it.isNotBlank() && plan.instruction == null }?.let { "${at}提醒：$it" },
    ).joinToString("；")
}

/**
 * Where a plan stands, in a line: 「下次：今天 16:30 · 1 小时 20 分钟后」「已暂停」「已完成 · 10/15 16:30」, or for a
 * watch how it is fed ([feed]: 实时 by push, 定时读取 on its pace, 未连接) and how often it fired.
 */
internal fun planStateLine(plan: Plan, now: Long, feed: SubscriptionState? = null): String {
    val zone = PlanRunner.zone()
    val fired = plan.lastFiredAtMillis?.let { " · 上次 ${stamp(it)}" }.orEmpty()
    return when {
        plan.end == PlanEnd.DONE -> "已完成$fired"
        plan.end == PlanEnd.MISSED -> "已错过 · 本该在 ${plan.lastDueAtMillis?.let(::stamp) ?: "—"}"
        !plan.enabled -> "已暂停$fired"
        plan.trigger is PlanTrigger.Watch -> {
            val watch = plan.trigger.watch
            val how = when (feed) {
                null, SubscriptionState.NO_PUSH, SubscriptionState.REFUSED -> "每 ${watch.pollMillis / MILLIS_PER_SECOND} 秒读一次"
                else -> feed.label
            }
            "${watch.server} · $how · 已触发 ${plan.firedCount} 次$fired"
        }
        else -> plan.nextDueMillis(now, zone)?.let { "下次：${describeMoment(it, now, zone)} · ${untilText(it - now)}" } ?: "进行中"
    }
}

/** 「3 分钟后」「1 小时 20 分钟后」「2 天后」; 「马上」 under a minute. */
internal fun untilText(millis: Long): String {
    val minutes = (millis + MILLIS_PER_MINUTE - 1) / MILLIS_PER_MINUTE
    return when {
        millis <= 0 -> "马上"
        minutes < MINUTES_PER_HOUR -> "$minutes 分钟后"
        minutes < MINUTES_PER_DAY -> "${minutes / MINUTES_PER_HOUR} 小时${(minutes % MINUTES_PER_HOUR).takeIf { it > 0 }?.let { " $it 分钟" }.orEmpty()}后"
        else -> "${minutes / MINUTES_PER_DAY} 天后"
    }
}

/** Plan writes go to disk and the alarm: off the main thread. */
private fun onPlans(context: Context, action: (Context) -> Unit) {
    val app = context.applicationContext
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { action(app) }
}

private fun stamp(millis: Long): String = SimpleDateFormat("M/d HH:mm", Locale.ROOT).format(Date(millis))

private val PLAN_EXAMPLES = listOf(
    "两小时后提醒我拿快递", "每个工作日早上 8 点提醒我带工牌", "深圳飞成都的机票低于 800 元提醒我", "每天早上 8 点给我播报天气",
    "下雨的时候提醒我带伞", "空气质量变好的时候提醒我开窗",
)
private const val PLANS_FOOTER =
    "到点时小博会弹出卡片并念出提醒；「去办」的计划由小博执行一轮；「自动」的计划直接执行操作，不经过模型。" +
        "状态类计划能看什么，取决于当前已加载的 MCP 服务（设置 → 连接 → MCP 或 连接应用）：资源按设定的间隔读取，只有查询工具的服务按间隔调用（最快 5 分钟一次）；" +
        "只在小博运行时检查。设备关着时错过的一次性提醒，2 小时内开机会补上，更久就记为已错过。"
private val PLANS_TITLE_SIZE = 24.sp
private val PLAN_TWO_COLUMNS = 900.dp
private val HISTORY_TIME_WIDTH = 96.dp
private const val MAX_SHOWN_FIRINGS = 10
private const val CLOCK_TICK_MS = 30_000L
private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 24 * 60L
