@file:OptIn(ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.runtime.SubagentState
import cc.noharry.agent.runtime.SubagentStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.net.URI
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The batch of subagents the card shows, oldest first: the children that still work, whose result still waits for its
 * turn ([deliveries]), or that settled or were reported less than [LANE_LINGER_MS] ago, and every sibling whose work
 * overlapped theirs; empty once the whole batch has been reported for that long.
 */
internal fun subagentsOnCard(board: Map<String, List<SubagentStatus>>, now: Long, deliveries: Map<String, Long> = emptyMap()): List<SubagentStatus> {
    val rows = board.values.flatten().sortedBy { it.startedAtEpochMillis }
    var since = rows.filter { it.onCard(now, deliveries[it.deliveryKey()]) }.minOfOrNull { it.startedAtEpochMillis } ?: return emptyList()
    rows.asReversed().forEach { row ->
        if (row.startedAtEpochMillis < since && (!row.state.finished || row.finishedAtEpochMillis >= since)) since = row.startedAtEpochMillis
    }
    return rows.filter { it.startedAtEpochMillis >= since }.takeLast(MAX_LANES)
}

private fun SubagentStatus.onCard(now: Long, delivered: Long?): Boolean = when {
    !state.finished -> true
    delivered == REPORT_QUEUED -> now - finishedAtEpochMillis < QUEUED_GIVE_UP_MS
    else -> now - maxOf(finishedAtEpochMillis, delivered ?: 0L) < LANE_LINGER_MS
}

/** Finished, with its result still waiting for the report's turn. */
private fun SubagentStatus.queued(deliveries: Map<String, Long>): Boolean = state.finished && deliveries[deliveryKey()] == REPORT_QUEUED

/** One line on what a child is doing, or what it came back with, in the card's words. */
internal fun SubagentStatus.cardActivity(now: Long): String = when (state) {
    SubagentState.RUNNING -> latestStep() ?: "思考中"
    SubagentState.STALLED -> "${(now - lastActivityEpochMillis) / 1_000} 秒没有进展"
    SubagentState.COMPLETED -> result.headline()
    SubagentState.CANCELLED -> state.chinese()
    SubagentState.FAILED, SubagentState.TIMED_OUT -> state.chinese() + error.takeIf(String::isNotEmpty)?.let { "：$it" }.orEmpty()
}

/**
 * 小博's ring for subagents while the card is folded: one segment per child of the batch, lit as each finishes (a
 * single child turns instead); amber while one is stalled; all lit while their results wait to be told. Null once the
 * batch has been reported.
 */
internal fun overlaySubagentSignal(board: Map<String, List<SubagentStatus>>, now: Long, deliveries: Map<String, Long> = emptyMap()): OverlayTaskSignal? {
    val lanes = subagentsOnCard(board, now, deliveries)
    val working = lanes.filter { !it.state.finished }
    if (working.isEmpty() && lanes.none { it.queued(deliveries) }) return null
    val done = lanes.size - working.size
    val stalled = working.firstOrNull { it.state == SubagentState.STALLED }
    val focus = stalled ?: working.lastOrNull()
    return OverlayTaskSignal(
        tone = if (stalled != null) OverlayTaskTone.STALLED else OverlayTaskTone.WORKING,
        stepsDone = done,
        stepsTotal = if (lanes.size > 1) lanes.size else 0,
        title = when {
            focus == null -> "子任务都完成了"
            lanes.size > 1 -> "子任务 · $done/${lanes.size} 完成"
            else -> "子任务「${focus.label}」"
        },
        detail = when {
            focus == null -> "马上告诉你结果"
            lanes.size > 1 -> "「${focus.label}」" + focus.cardActivity(now)
            else -> focus.cardActivity(now)
        },
        more = 0,
        key = "subagents|${lanes.first().id}|$done|${stalled?.id}",
    )
}

/** What folded 小博 shows: a delegation first (it may hold a result), else subagents at work. */
internal fun overlayFoldedSignal(delegations: List<DebugDelegationView>, now: Long = System.currentTimeMillis()): OverlayTaskSignal? =
    overlayTaskSignal(delegations, now) ?: overlaySubagentSignal(DebugSubagents.board.value, now, DebugSubagents.deliveries.value)

/** Calls [onChange] whenever a subagent starts, moves, settles or is reported, so folded 小博's ring follows. */
internal fun CoroutineScope.followSubagents(onChange: () -> Unit): Job =
    launch { combine(DebugSubagents.board, DebugSubagents.deliveries) { _, _ -> }.collect { onChange() } }

/**
 * The card's subagent panel (owner 2026-10-17: observable on the card, and it should look the part). The main agent is
 * the dot at the top; its branch flows down to one lane per child. A lane's node turns while the child works, pulses
 * amber when it stalls and bursts green with a tick when it is done; seconds, one pip per tool call and the token
 * count roll up as it goes, and the lane's bar sweeps until it fills.
 */
@Composable
internal fun OverlaySubagentLanes(turnDone: Boolean = false) {
    val board by DebugSubagents.board.collectAsState()
    val deliveries by DebugSubagents.deliveries.collectAsState()
    // Seconds count between events; the clock stops once the batch has left the card.
    val now by produceState(System.currentTimeMillis(), board, deliveries) {
        value = System.currentTimeMillis()
        while (subagentsOnCard(board, value, deliveries).isNotEmpty()) {
            delay(LANE_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    val lanes = subagentsOnCard(board, now, deliveries)
    if (lanes.isEmpty()) return
    val working = lanes.any { !it.state.finished }
    val telling = lanes.any { it.queued(deliveries) }
    // The answer is up and nothing is left to tell (owner 2026-10-17: 展示最终完成页面的时候子agent那个就可以收起来了):
    // the batch folds to one line, and a tap opens it again.
    var opened by remember(lanes.first().id) { mutableStateOf(false) }
    val settled = turnDone && !working && !telling
    // Read only while drawing, so the motion redraws the lanes without recomposing them.
    val pulse = if (working) flowPhase() else remember { mutableFloatStateOf(0f) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .then(if (settled) Modifier.clickable { opened = !opened } else Modifier)
            .background(OverlayPalette.tile)
            .drawBehind {
                val glow = if (working) OverlayPalette.glow else OverlayPalette.live
                drawRect(Brush.radialGradient(listOf(glow.copy(alpha = 0.12f), Color.Transparent), Offset.Zero, size.width * 0.6f))
            }
            .border(1.dp, OverlayPalette.tileEdge, PanelShape)
            .drawWithContent {
                drawContent()
                if (working) drawSheen(pulse.value)
            }
            .padding(start = 6.dp, end = 14.dp, top = 8.dp, bottom = 6.dp),
    ) {
        if (settled && !opened) {
            FoldedLanes(lanes)
            return@Column
        }
        LanesHeader(lanes, now, pulse, telling = telling)
        lanes.forEachIndexed { index, lane ->
            val feeding = lanes.drop(index).any { !it.state.finished }
            Lane(lane, now, pulse, last = index == lanes.lastIndex, feeding = feeding)
        }
    }
}

/** The settled batch in one line: how many, how long, what they read, and the way to open it. */
@Composable
private fun FoldedLanes(lanes: List<SubagentStatus>) {
    val seconds = delegationElapsed(lanes.maxOf { it.finishedAtEpochMillis }, lanes.minOf { it.startedAtEpochMillis })
    val tokens = lanes.sumOf { it.inputTokens + it.outputTokens }
    val failed = lanes.count { it.state != SubagentState.COMPLETED }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (failed > 0) OverlayPalette.amber else OverlayPalette.live))
        Text(
            "${lanes.size} 路子任务完成" + (if (failed > 0) "（$failed 路没成）" else "") + " · $seconds · ${compactTokens(tokens)} tok",
            color = OverlayPalette.secondary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("展开", color = OverlayPalette.glow, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun flowPhase(): State<Float> = rememberInfiniteTransition(label = "subagent-flow")
    .animateFloat(0f, 1f, infiniteRepeatable(tween(FLOW_MS, easing = LinearEasing), RepeatMode.Restart), label = "phase")

@Composable
private fun LanesHeader(lanes: List<SubagentStatus>, now: Long, pulse: State<Float>, telling: Boolean) {
    val done = lanes.count { it.state.finished }
    val all = done == lanes.size
    val end = if (all) lanes.maxOf { it.finishedAtEpochMillis } else now
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Canvas(Modifier.width(GUTTER).fillMaxHeight()) { drawRoot(pulse.value, all) }
        Text(
            when {
                all -> (if (lanes.size > 1) "子任务全部完成" else "子任务完成") + if (telling) " · 马上汇报" else ""
                lanes.size > 1 -> "${lanes.size} 路子任务并行"
                else -> "子任务进行中"
            },
            color = OverlayPalette.secondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.6.sp,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Text("  $done/${lanes.size}", color = if (all) OverlayPalette.live else OverlayPalette.glow, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, style = Tabular)
        Spacer(Modifier.weight(1f))
        Text(delegationElapsed(end, lanes.minOf { it.startedAtEpochMillis }), color = OverlayPalette.secondary, fontSize = 13.sp, style = Tabular)
    }
}

@Composable
private fun Lane(lane: SubagentStatus, now: Long, pulse: State<Float>, last: Boolean, feeding: Boolean) {
    val tone = lane.state.tone()
    // The green burst when a child comes back; a card opened later plays it once too.
    val burst = remember(lane.id) { Animatable(1f) }
    LaunchedEffect(lane.state) {
        if (lane.state == SubagentState.COMPLETED) {
            burst.snapTo(0f)
            burst.animateTo(1f, tween(BURST_MS))
        }
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Canvas(Modifier.width(GUTTER).fillMaxHeight()) {
            drawBranch(lane.state, tone, pulse.value, last, feeding)
            drawNode(lane.state, tone, pulse.value, burst.value, Offset(NODE_X.toPx(), NODE_Y.toPx()))
        }
        Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            LaneTitle(lane, now, tone)
            Text(
                lane.cardActivity(now),
                color = if (lane.state == SubagentState.COMPLETED) OverlayPalette.text.copy(alpha = 0.88f) else OverlayPalette.secondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LaneBar(lane.state, tone, pulse)
        }
    }
}

@Composable
private fun LaneTitle(lane: SubagentStatus, now: Long, tone: Color) {
    val tokens by animateIntAsState((lane.inputTokens + lane.outputTokens).toInt(), tween(TOKEN_ROLL_MS), label = "tokens")
    val end = if (lane.state.finished) lane.finishedAtEpochMillis else now
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                lane.label.ifBlank { lane.id },
                color = OverlayPalette.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            repeat(lane.toolCalls.coerceAtMost(MAX_PIPS)) { Box(Modifier.size(5.dp).clip(CircleShape).background(tone.copy(alpha = 0.85f))) }
            if (lane.toolCalls > MAX_PIPS) Text("+${lane.toolCalls - MAX_PIPS}", color = OverlayPalette.faint, fontSize = 11.sp)
        }
        if (tokens > 0) Text("${compactTokens(tokens.toLong())} tok", color = OverlayPalette.faint, fontSize = 11.sp, style = Tabular)
        Text(delegationElapsed(end, lane.startedAtEpochMillis), color = OverlayPalette.text, fontSize = 13.sp, style = Tabular)
    }
}

/** A sweep while the child works, a pulse while it stalls, full once it settles. */
@Composable
private fun LaneBar(state: SubagentState, tone: Color, pulse: State<Float>) {
    Canvas(Modifier.fillMaxWidth().height(BAR_HEIGHT)) {
        val corner = CornerRadius(size.height / 2)
        drawRoundRect(OverlayPalette.hairline, cornerRadius = corner)
        when (state) {
            SubagentState.RUNNING -> {
                val band = size.width * SWEEP_SHARE
                val start = -band + (size.width + band) * pulse.value
                drawRoundRect(Brush.horizontalGradient(listOf(Color.Transparent, tone, Color.Transparent), start, start + band), cornerRadius = corner)
            }
            SubagentState.STALLED -> drawRoundRect(tone.copy(alpha = 0.3f + 0.4f * wave(pulse.value)), cornerRadius = corner)
            else -> drawRoundRect(tone.copy(alpha = 0.75f), cornerRadius = corner)
        }
    }
}

/** The main agent: a dot that breathes while children work, and the trunk down to them. */
private fun DrawScope.drawRoot(phase: Float, settled: Boolean) {
    val center = Offset(TRUNK_X.toPx(), size.height / 2)
    val color = if (settled) OverlayPalette.live else OverlayPalette.sage
    drawLine(OverlayPalette.hairline, center, Offset(center.x, size.height), LINE_WIDTH.toPx())
    if (!settled) drawCircle(OverlayPalette.glow.copy(alpha = 0.25f * (1f - phase)), ROOT_R.toPx() * (1f + 1.4f * phase), center)
    drawCircle(Brush.linearGradient(listOf(color, OverlayPalette.glow), center - Offset(8f, 8f), center + Offset(8f, 8f)), ROOT_R.toPx(), center)
}

/** The trunk past this lane and the curve off it to the lane's node; dashes flow toward children still working. */
private fun DrawScope.drawBranch(state: SubagentState, tone: Color, phase: Float, last: Boolean, feeding: Boolean) {
    val x = TRUNK_X.toPx()
    val y = NODE_Y.toPx()
    val elbow = ELBOW.toPx()
    val width = LINE_WIDTH.toPx()
    val trunk = Path().apply { moveTo(x, 0f); lineTo(x, if (last) y - elbow else size.height) }
    val branch = Path().apply {
        moveTo(x, y - elbow)
        quadraticTo(x, y, x + elbow, y)
        lineTo(NODE_X.toPx() - NODE_R.toPx() - 3.dp.toPx(), y)
    }
    drawPath(trunk, OverlayPalette.hairline, style = Stroke(width))
    if (feeding) drawPath(trunk, OverlayPalette.glow.copy(alpha = 0.7f), style = flowing(phase, width))
    when {
        !state.finished -> {
            drawPath(branch, OverlayPalette.hairline, style = Stroke(width))
            drawPath(branch, tone, style = flowing(phase, width))
        }
        else -> drawPath(branch, tone.copy(alpha = 0.6f), style = Stroke(width, cap = StrokeCap.Round))
    }
}

/** A light running round the panel's edge while children work. */
private fun DrawScope.drawSheen(phase: Float) {
    val width = SHEEN_WIDTH.toPx()
    val angle = phase * 2 * PI
    val reach = Offset(cos(angle).toFloat() * size.width / 2, sin(angle).toFloat() * size.height / 2)
    val middle = Offset(size.width / 2, size.height / 2)
    drawRoundRect(
        Brush.linearGradient(listOf(Color.Transparent, OverlayPalette.glow.copy(alpha = 0.9f), Color.Transparent), middle + reach * 0.6f, middle + reach),
        topLeft = Offset(width / 2, width / 2),
        size = Size(size.width - width, size.height - width),
        cornerRadius = CornerRadius(PANEL_CORNER.toPx() - width / 2),
        style = Stroke(width),
    )
}

private fun DrawScope.flowing(phase: Float, width: Float): Stroke {
    val dash = DASH.toPx()
    val period = dash * 2
    return Stroke(width, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash), period * (1f - phase)))
}

private fun DrawScope.drawNode(state: SubagentState, tone: Color, phase: Float, burst: Float, center: Offset) {
    val r = NODE_R.toPx()
    val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
    when (state) {
        SubagentState.RUNNING -> {
            drawCircle(tone.copy(alpha = 0.16f), r, center)
            drawArc(tone, phase * 360f, SPIN_SWEEP, false, center - Offset(r, r), Size(r * 2, r * 2), style = stroke)
            drawCircle(tone, r * (0.26f + 0.14f * wave(phase)), center)
        }
        SubagentState.STALLED -> {
            drawCircle(tone.copy(alpha = 0.15f + 0.3f * wave(phase)), r, center)
            drawCircle(tone, r, center, style = stroke)
        }
        SubagentState.COMPLETED -> {
            if (burst < 1f) drawCircle(tone.copy(alpha = 0.6f * (1f - burst)), r * (1f + 1.6f * burst), center, style = stroke)
            drawCircle(tone.copy(alpha = 0.22f), r * 1.5f, center)
            drawCircle(tone, r, center)
            drawPath(tickPath(center, r), OverlayPalette.onSignature, style = stroke)
        }
        SubagentState.CANCELLED -> {
            drawCircle(tone, r, center, style = stroke)
            drawLine(tone, center - Offset(r * 0.45f, 0f), center + Offset(r * 0.45f, 0f), stroke.width, StrokeCap.Round)
        }
        SubagentState.FAILED, SubagentState.TIMED_OUT -> {
            drawCircle(tone, r, center)
            val d = r * 0.38f
            drawLine(OverlayPalette.onSignature, center - Offset(d, d), center + Offset(d, d), stroke.width, StrokeCap.Round)
            drawLine(OverlayPalette.onSignature, center + Offset(-d, d), center + Offset(d, -d), stroke.width, StrokeCap.Round)
        }
    }
}

private fun tickPath(center: Offset, r: Float) = Path().apply {
    moveTo(center.x - r * 0.45f, center.y + r * 0.02f)
    lineTo(center.x - r * 0.1f, center.y + r * 0.36f)
    lineTo(center.x + r * 0.48f, center.y - r * 0.32f)
}

private fun SubagentState.tone(): Color = when (this) {
    SubagentState.RUNNING -> OverlayPalette.glow
    SubagentState.STALLED -> OverlayPalette.amber
    SubagentState.COMPLETED -> OverlayPalette.live
    SubagentState.CANCELLED -> OverlayPalette.faint
    SubagentState.FAILED, SubagentState.TIMED_OUT -> OverlayPalette.warm
}

private fun wave(phase: Float): Float = (sin(phase * 2 * PI).toFloat() + 1f) / 2f

/** The latest call as a phrase: 搜索「…」, 读取 host, or the tool's own name. */
private fun SubagentStatus.latestStep(): String? {
    if (latest.isEmpty()) return null
    val args = latest.substringAfter(' ', "")
    val name = latest.substringBefore(' ').let { if (it == "call_tool") args.stringArg("name") ?: it else it }
    return when (val tool = name.substringAfterLast("__")) {
        "web_search" -> args.stringArg("query")?.let { "搜索「${it.take(MAX_ARG_CHARS)}」" } ?: "联网搜索"
        "web_fetch" -> "读取 " + (args.stringArg("url")?.let(::hostOf) ?: "网页")
        "read_file", "list_dir", "grep" -> "查阅资料"
        "search_tools" -> "找工具"
        "exec_js" -> "计算"
        "update_todo" -> "排步骤"
        else -> "调用 $tool"
    }
}

/** The answer's first line worth reading, without markdown marks: past a list number and a lead-in. */
private fun String.headline(): String {
    val lines = lineSequence().map { it.trim().trimStart('#', '*', '-', '>', ' ').replace(LIST_NUMBER, "").replace(EMPHASIS, "").trim() }.filter(String::isNotEmpty).toList()
    return lines.firstOrNull { !it.isLeadIn() } ?: lines.firstOrNull().orEmpty()
}

/** "…值得看的展如下：", "…确认的演出如下。": says a list follows, nothing yet. */
private fun String.isLeadIn(): Boolean = endsWith('：') || endsWith(':') || trimEnd('。', '.', '：', ':').endsWith("如下")

/** A string argument from possibly cut-off JSON. */
private fun String.stringArg(key: String): String? =
    Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)").find(this)?.groupValues?.get(1)
        ?.replace("\\\"", "\"")?.takeIf(String::isNotBlank)

private fun hostOf(url: String): String =
    runCatching { URI(url).host }.getOrNull()?.removePrefix("www.") ?: url.take(MAX_ARG_CHARS)

internal fun compactTokens(tokens: Long): String = when {
    tokens < 1_000 -> "$tokens"
    tokens < 100_000 -> String.format(Locale.ROOT, "%.1fk", tokens / 1_000.0)
    else -> "${tokens / 1_000}k"
}

private val PANEL_CORNER = 14.dp
private val PanelShape = RoundedCornerShape(PANEL_CORNER)
private val SHEEN_WIDTH = 1.5.dp
private val LIST_NUMBER = Regex("^\\d+[.、)]\\s*")
private val EMPHASIS = Regex("\\*\\*|__|`")
private val Tabular = TextStyle(fontFeatureSettings = "tnum")
private val GUTTER = 40.dp
private val TRUNK_X = 10.dp
private val NODE_X = 26.dp
private val NODE_Y = 16.dp
private val NODE_R = 7.dp
private val ROOT_R = 5.dp
private val ELBOW = 6.dp
private val LINE_WIDTH = 1.5.dp
private val DASH = 4.dp
private val BAR_HEIGHT = 3.dp
private const val SPIN_SWEEP = 250f
private const val SWEEP_SHARE = 0.35f
private const val FLOW_MS = 1_400
private const val BURST_MS = 700
private const val TOKEN_ROLL_MS = 600
private const val LANE_TICK_MS = 1_000L
private const val LANE_LINGER_MS = 20_000L
/** A result never told (its turn did not start) stops holding the card after this long. */
private const val QUEUED_GIVE_UP_MS = 10 * 60_000L
private const val MAX_LANES = 4
private const val MAX_PIPS = 8
private const val MAX_ARG_CHARS = 24
