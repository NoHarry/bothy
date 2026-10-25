package cc.noharry.bothy.playground

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The assistant's panel, as the head unit's own assistant shows it: 小博 with the words being heard (or the request) on
 * top, then this exchange only: who asked (or which of 小博's plans) and what, what 小博 thought and which tools it called,
 * its reply; the panel keeps the newest in view. 小博 tapped listens again; the X ends voice.
 */
@Composable
internal fun AssistantChatPanel(
    view: AssistantView,
    speaker: Seat?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    closesAt: Long = 0L,
    /**
     * 小博 and its voice are in the bottom bar ([VoiceDock]), not on the card (owner 2026-10-25: 「卡片上面不要再显示一个
     * 头像和话筒吧，统一用底部的表示」): the header is what was asked and the X.
     */
    voiceInBar: Boolean = false,
    onTouched: () -> Unit = {},
) {
    val scroll = rememberScrollState()
    LaunchedEffect(view.request, view.reply, view.timeline.size, view.a2ui) { scroll.animateScrollTo(scroll.maxValue) }
    // Any touch, a scroll's or a tap's, holds the card open ([AssistantView.open]); seen before what it lands on.
    val touched = Modifier.pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); onTouched() } }
    Column(modifier.then(touched).clip(RoundedCornerShape(22.dp)).background(UnitColors.panel).padding(16.dp)) {
        val line = if (voiceInBar) view.request.ifBlank { "你好，我是小博" } else view.heard.ifBlank { view.request.ifBlank { view.voiceText().ifBlank { "你好，我是小博" } } }
        AssistantHeader(view, line, !voiceInBar && view.heard.isNotBlank(), onClose, 17.sp, closesAt = closesAt, voice = !voiceInBar)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (view.request.isNotBlank()) {
                Text(if (view.request.startsWith(PLAN_PREFIX)) "小博的计划" else speaker?.label ?: "你", color = UnitColors.muted, fontSize = 12.sp)
                UserBubble(view.request)
            }
            Process(view)
            if (view.reply.isNotBlank() && !view.running) Text(view.reply, color = Color.White, fontSize = 15.sp, lineHeight = 23.sp)
            if (!view.running && !view.a2ui.isEmpty) A2uiContent(view.a2ui)
        }
    }
}

/**
 * 小博 and one line of words (the newest end in view, as words being heard run on), the microphone, and, while it
 * listens or works, the X that ends voice. 小博 or the microphone tapped wakes it. Without [voice] (小博 is in the bar
 * below) only the line and the X.
 */
@Composable
private fun AssistantHeader(
    view: AssistantView,
    line: String,
    live: Boolean,
    onClose: (() -> Unit)?,
    textSize: TextUnit,
    avatar: Dp = 46.dp,
    closesAt: Long = 0L,
    voice: Boolean = true,
) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (voice) Mascot(view, avatar, Modifier.clickable { Assistant.wake(context) })
        TailText(line, if (live && view.voiceTone == "LIVE") Color.White else if (live) UnitColors.muted else Color.White, textSize, Modifier.weight(1f))
        if (voice) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = "唤醒小博",
                tint = if (view.listening) UnitColors.teal else UnitColors.muted,
                modifier = Modifier.size(22.dp).clickable { Assistant.wake(context) },
            )
        }
        if (onClose != null) CloseCountdown(closesAt, view.holdMs(), onClose)
    }
}

/** As voice ends the card goes with it ([AssistantView.foldsWithVoice]): [onFold] gets the request to close. */
@Composable
internal fun FoldWithVoice(view: AssistantView, held: String?, onFold: (String) -> Unit) {
    var before by remember { mutableStateOf(view.listening) }
    LaunchedEffect(view.listening) {
        if (view.foldsWithVoice(before, held)) onFold(view.request)
        before = view.listening
    }
}

/** 小博's face, breathing while it listens or talks. */
@Composable
internal fun Mascot(view: AssistantView, size: Dp, modifier: Modifier = Modifier) {
    val breath by rememberInfiniteTransition(label = "breath").animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(BREATH_MS), RepeatMode.Reverse),
        label = "breathScale",
    )
    val alive = view.listening || view.speaking
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (alive) Box(Modifier.size(size).scale(breath).clip(CircleShape).background(UnitColors.teal.copy(alpha = 0.22f)))
        Image(painterResource(R.drawable.bothy_mark), contentDescription = "小博", modifier = Modifier.size(size * 0.9f))
    }
}

/**
 * One line from the left; once it runs longer than the room, its end stays in view and the start runs off to the left,
 * as the head unit shows words being heard.
 */
@Composable
internal fun TailText(text: String, color: Color, size: TextUnit, modifier: Modifier = Modifier) {
    Layout(
        content = { Text(text, color = color, fontSize = size, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) },
        modifier = modifier.clipToBounds(),
    ) { measurables, constraints ->
        val line = measurables.single().measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
        val width = constraints.maxWidth
        layout(width, line.height) { line.place(if (line.width <= width) 0 else width - line.width, 0) }
    }
}

/** What the voice line says now: the words heard, 聆听中… while nothing is, Bothy's own word otherwise; empty when not listening. */
internal fun AssistantView.voiceText(): String = when {
    !listening -> ""
    heard.isNotBlank() -> heard
    voiceLine.isBlank() || voiceLine == "我在听，请说" -> "聆听中…"
    else -> voiceLine
}

@Composable
private fun UserBubble(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, UnitColors.raised, RoundedCornerShape(14.dp))
            .background(Color(0xFF0D1218)).padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/**
 * How 小博 went about it: the tools it called on one line that scrolls to the newest (owner 2026-10-25: 「卡片不需要每个
 * 工具调用都去用一行，弄成滚动的一行就行了」), each ticked off as it finishes; then, in order, what it thought on the way
 * (small and grey) and what it said while working; 「正在思考」 until the first of them.
 */
@Composable
private fun Process(view: AssistantView) {
    if (!view.running && view.timeline.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val calls = view.timeline.filterIsInstance<TurnEntry.Called>()
        if (calls.isNotEmpty()) CalledLine(calls)
        view.timeline.filterNot { it is TurnEntry.Called || view.repeatsReply(it) }.forEach { entry ->
            when (entry) {
                is TurnEntry.Thought -> Text(entry.text, color = UnitColors.muted, fontSize = 12.sp, lineHeight = 18.sp)
                is TurnEntry.Said -> Text(entry.text, color = Color.White.copy(alpha = 0.86f), fontSize = 13.sp, lineHeight = 20.sp)
                is TurnEntry.Called -> Unit
            }
        }
        if (view.running && view.timeline.lastOrNull() !is TurnEntry.Called) Thinking()
    }
}

/**
 * 「小博 调用」 and the tool it is on: one line, each new call rolling in from below over the one before (owner
 * 2026-10-25: 「不是现在这种往后叠加的状态」); the same call finishing only ticks, it does not roll.
 */
@Composable
private fun CalledLine(calls: List<TurnEntry.Called>) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("小博 调用", color = UnitColors.muted, fontSize = 12.sp)
        AnimatedContent(
            targetState = calls.size to calls.last(),
            contentKey = { it.first },
            transitionSpec = {
                (fadeIn(tween(ROLL_MS)) + slideInVertically(tween(ROLL_MS)) { it }) togetherWith
                    (fadeOut(tween(ROLL_MS)) + slideOutVertically(tween(ROLL_MS)) { -it })
            },
            label = "called",
        ) { (_, call) -> Called(call) }
    }
}

/** What 小博 said on the way that is its answer after all: shown once, as the reply (with its picks), not twice. */
private fun AssistantView.repeatsReply(entry: TurnEntry): Boolean {
    if (entry !is TurnEntry.Said || running || reply.isBlank()) return false
    val start = reply.filterNot(Char::isWhitespace).take(REPLY_START)
    return start.length >= REPLY_START && start in entry.text.filterNot(Char::isWhitespace)
}

@Composable
private fun Called(entry: TurnEntry.Called) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        when (entry.state) {
            "running" -> Dots()
            "error" -> Icon(Icons.Filled.Error, contentDescription = null, tint = PlaygroundColors.ember, modifier = Modifier.size(13.dp))
            else -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = UnitColors.teal, modifier = Modifier.size(13.dp))
        }
        Text(stepTitle(entry.tool), color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun Thinking() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Dots()
        Text("小博 正在思考", color = UnitColors.teal, fontSize = 12.sp)
    }
}

/** Three dots taking turns, for work under way. */
@Composable
private fun Dots() {
    val phase by rememberInfiniteTransition(label = "dots").animateFloat(0f, 3f, infiniteRepeatable(tween(DOTS_MS)), label = "dotsPhase")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.width(16.dp)) {
        repeat(3) { i ->
            val lit = phase.toInt() == i
            Box(Modifier.size(4.dp).clip(CircleShape).background(if (lit) UnitColors.teal else UnitColors.muted.copy(alpha = 0.5f)))
        }
    }
}

/** A tool's name as the panel shows it: the head unit's own titles, else the name without its server prefix. */
internal fun stepTitle(tool: String): String {
    val bare = tool.substringAfterLast("__")
    return TOOL_TITLES[bare] ?: bare
}

private val TOOL_TITLES: Map<String, String> by lazy {
    val nav = NavController(PlaygroundStore(), UnavailableNavEngine(""))
    (CABIN_TOOLS + HOME_TOOLS + navTools(nav) + appNavigationTool(nav)).associate { it.name to it.title } +
        mapOf(
            "web_search" to "联网搜索", "web_fetch" to "打开网页", "spawn_agent" to "派子任务", "wait_agent" to "等子任务",
            "search_tools" to "找工具", "show_card" to "出卡片", "ask_user" to "问你", "read_file" to "读资料", "list_dir" to "翻资料", "write_file" to "记下来",
            "todo_write" to "列待办", "update_todo" to "更新待办", "create_plan" to "设提醒", "update_plan" to "改提醒",
            "android_use" to "操作屏幕", "finish" to "整理结果", "plan_manage" to "设提醒", "plan_list" to "看提醒",
            "user_memory" to "记住你的习惯", "offer_options" to "给你选", "offer_choice" to "给你选",
            "offer_slider" to "出调节条", "offer_switch" to "出开关",
            // Bothy's own media tools (the runtime's device media provider): what plays on the tablet, wherever it is shown.
            "media.status" to "看在放什么", "media.control" to "控制播放", "media.volume" to "调音量", "media.play_from_search" to "搜歌播放",
        )
}

/**
 * At home, the assistant's card: 小博 and a greeting, or, while it listens, the words as they are heard (with the X to
 * end voice); the small map shows under its header (the screen places it there). 小博 tapped listens; the rest opens the map.
 */
@Composable
internal fun AssistantMiniCard(city: String, view: AssistantView, onClose: () -> Unit, onMap: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(UnitColors.panel).clickable(onClick = onMap).padding(16.dp)) {
        if (view.listening) {
            AssistantHeader(view, view.voiceText(), live = true, onClose = onClose, textSize = 16.sp)
        } else {
            val context = LocalContext.current
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Mascot(view, 46.dp, Modifier.clickable { Assistant.wake(context) })
                Column(Modifier.weight(1f)) {
                    Text("你好，我是小博", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("说「你好小博」或点我", color = UnitColors.muted, fontSize = 12.sp, maxLines = 1)
                }
                Icon(Icons.Filled.Mic, contentDescription = "唤醒小博", tint = UnitColors.muted, modifier = Modifier.size(22.dp).clickable { Assistant.wake(context) })
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            if (city.isEmpty()) "正在定位…" else "当前位置 · $city",
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(UnitColors.card).padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** While 小博 listens over the map: a slim bar with 小博, the words as they are recognised, the microphone and the X. */
@Composable
internal fun AssistantBar(view: AssistantView, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(UnitColors.card).padding(horizontal = 12.dp, vertical = 8.dp)) {
        AssistantHeader(view, view.voiceText(), live = true, onClose = onClose, textSize = 15.sp, avatar = 36.dp)
    }
}

/** How Bothy names a run one of its plans started (「计划 · 拿蛋糕」). */
private const val PLAN_PREFIX = "计划 ·"
/** How much of the reply's start a line said on the way must hold to be the reply itself. */
private const val REPLY_START = 12
private const val BREATH_MS = 900
private const val DOTS_MS = 900
private const val ROLL_MS = 260

/** Idle, what to say to 小博: each line, tapped, is said to it. */
@Composable
internal fun SuggestionsCard(lines: List<String>, onSay: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(UnitColors.panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("试试对小博说", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lines.forEachIndexed { i, line ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(UnitColors.raised).clickable { onSay(line) }.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("${i + 1}", color = UnitColors.teal, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(14.dp))
                    Text(line, color = Color.White, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Navigating, every stop ahead with how long, how far and when, then the destination, as the head unit lists a route. */
@Composable
internal fun RouteDetails(nav: NavState, now: Long, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(UnitColors.card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("路线详情", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
        nav.waypoints.forEachIndexed { i, stop ->
            val left = nav.toWaypoints.getOrNull(i)
            StopRow("途${i + 1}", stop.name, left?.seconds, left?.meters, now)
        }
        StopRow("终", nav.destination?.name.orEmpty(), nav.remainingSeconds, nav.remainingMeters, now)
    }
}

@Composable
private fun StopRow(badge: String, name: String, seconds: Int?, meters: Int?, now: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(badge, color = if (badge == "终") PlaygroundColors.ember else UnitColors.teal, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (seconds != null && meters != null) {
                Text("${seconds.duration()} · ${meters.km()} · ${clockText(now + seconds * 1000L, now)}到", color = UnitColors.muted, fontSize = 12.sp)
            }
        }
    }
}
