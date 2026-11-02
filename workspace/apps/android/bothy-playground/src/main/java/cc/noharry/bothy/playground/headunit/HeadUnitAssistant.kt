package cc.noharry.bothy.playground.headunit
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.playground.PlaygroundStore
import cc.noharry.bothy.playground.R
import cc.noharry.bothy.playground.cabin.CABIN_TOOLS
import cc.noharry.bothy.playground.cabin.Seat
import cc.noharry.bothy.playground.home.HOME_TOOLS
import cc.noharry.bothy.playground.home.line
import cc.noharry.bothy.playground.link.Assistant
import cc.noharry.bothy.playground.link.AssistantView
import cc.noharry.bothy.playground.link.TurnEntry
import cc.noharry.bothy.playground.link.closesAt
import cc.noharry.bothy.playground.link.dismissedSince
import cc.noharry.bothy.playground.link.holdMs
import cc.noharry.bothy.playground.nav.NavController
import cc.noharry.bothy.playground.nav.NavState
import cc.noharry.bothy.playground.nav.UnavailableNavEngine
import cc.noharry.bothy.playground.nav.appNavigationTool
import cc.noharry.bothy.playground.nav.navTools
import cc.noharry.bothy.playground.nav.place

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
        val greeting = stringResource(R.string.headunit_greeting)
        val line = if (voiceInBar) {
            view.request.ifBlank { greeting }
        } else {
            view.heard.ifBlank { view.request.ifBlank { view.voiceText().ifBlank { greeting } } }
        }
        AssistantHeader(view, line, !voiceInBar && view.heard.isNotBlank(), onClose, 17.sp, closesAt = closesAt, voice = !voiceInBar)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (view.request.isNotBlank()) {
                val who = when {
                    view.request.startsWith(PLAN_PREFIX) -> stringResource(R.string.headunit_plan_of_xiaobo)
                    else -> stringResource(speaker?.labelRes ?: R.string.headunit_you)
                }
                Text(who, color = UnitColors.muted, fontSize = 12.sp)
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
                contentDescription = stringResource(R.string.headunit_wake_xiaobo),
                tint = if (view.listening) UnitColors.teal else UnitColors.muted,
                modifier = Modifier.size(22.dp).clickable { Assistant.wake(context) },
            )
        }
        if (onClose != null) CloseCountdown(closesAt, view.holdMs(), onClose)
    }
}

/**
 * The speaker dismissed voice ([AssistantView.dismissedSince]): [onFold] gets the request whose card closes with it. The
 * first of Bothy's reports only sets where that stands: a dismissal from before the head unit started is not one now.
 */
@Composable
internal fun FoldOnDismiss(view: AssistantView, onFold: (String) -> Unit) {
    var seen by remember { mutableStateOf<Long?>(null) }
    val reported = view.updatedAt > 0L
    LaunchedEffect(view.dismissedAt, reported) {
        if (!reported) return@LaunchedEffect
        val last = seen
        if (last != null && view.dismissedSince(last) && view.request.isNotBlank()) onFold(view.request)
        seen = view.dismissedAt
    }
}

/**
 * A new run starts ([onNew]): a closed or held card is per run, not per wording, so the same words asked again (「下一首」)
 * open their card again.
 */
@Composable
internal fun OnNewRun(view: AssistantView, onNew: () -> Unit) {
    var was by remember { mutableStateOf(view.running) }
    LaunchedEffect(view.running) {
        if (view.running && !was) onNew()
        was = view.running
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
        val name = stringResource(R.string.headunit_xiaobo)
        Image(painterResource(R.drawable.bothy_mark), contentDescription = name, modifier = Modifier.size(size * 0.9f))
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
@Composable
internal fun AssistantView.voiceText(): String = when {
    !listening -> ""
    heard.isNotBlank() -> heard
    voiceLine.isBlank() || voiceLine in BOTHY_LISTENING_LINES -> stringResource(R.string.headunit_listening)
    else -> voiceLine
}

/** Bothy's waiting line in either of its languages, which the head unit words in its own. */
private val BOTHY_LISTENING_LINES = setOf("我在听，请说", "I'm listening, go ahead")

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
        Text(stringResource(R.string.headunit_xiaobo_calls), color = UnitColors.muted, fontSize = 12.sp)
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
        Text(stringResource(R.string.headunit_xiaobo_thinking), color = UnitColors.teal, fontSize = 12.sp)
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

/**
 * A tool's name as the panel shows it: the head unit's own titles, else the title this app's servers give it, else the
 * name without its server prefix.
 */
@Composable
internal fun stepTitle(tool: String): String {
    val bare = tool.substringAfterLast("__")
    return TOOL_TITLES[bare]?.let { stringResource(it) } ?: SERVED_TITLES[bare] ?: bare
}

/** The head unit's own titles for the tools 小博 calls: this app's servers', then Bothy's. */
private val TOOL_TITLES: Map<String, Int> = mapOf(
    "get_cabin_status" to R.string.headunit_tool_get_cabin_status, "set_climate" to R.string.headunit_tool_set_climate,
    "set_seat" to R.string.headunit_tool_set_seat, "set_window" to R.string.headunit_tool_set_window,
    "set_ambient_light" to R.string.headunit_tool_set_ambient_light, "play_music" to R.string.headunit_tool_play_music,
    "set_door_lock" to R.string.headunit_tool_set_door_lock, "set_door" to R.string.headunit_tool_set_door,
    "set_trunk" to R.string.headunit_tool_set_trunk, "set_screen" to R.string.headunit_tool_set_screen,
    "set_volume" to R.string.headunit_tool_set_volume, "get_home_status" to R.string.headunit_tool_get_home_status,
    "set_light" to R.string.headunit_tool_set_light, "set_curtain" to R.string.headunit_tool_set_curtain,
    "control_vacuum" to R.string.headunit_tool_control_vacuum, "run_scene" to R.string.headunit_tool_run_scene,
    "search_places" to R.string.headunit_tool_search_places,
    "start_navigation" to R.string.headunit_tool_start_navigation,
    "navigate_in_amap_app" to R.string.headunit_tool_navigate_in_amap_app,
    "begin_navigation" to R.string.headunit_tool_begin_navigation,
    "add_waypoint" to R.string.headunit_tool_add_waypoint, "remove_waypoint" to R.string.headunit_tool_remove_waypoint,
    "replace_waypoint" to R.string.headunit_tool_replace_waypoint,
    "reorder_waypoints" to R.string.headunit_tool_reorder_waypoints,
    "change_destination" to R.string.headunit_tool_change_destination,
    "set_route_preference" to R.string.headunit_tool_set_route_preference,
    "get_route" to R.string.headunit_tool_get_route, "search_along_route" to R.string.headunit_tool_search_along_route,
    "stop_navigation" to R.string.headunit_tool_stop_navigation, "web_search" to R.string.headunit_tool_web_search,
    "web_fetch" to R.string.headunit_tool_web_fetch, "spawn_agent" to R.string.headunit_tool_spawn_agent,
    "wait_agent" to R.string.headunit_tool_wait_agent, "search_tools" to R.string.headunit_tool_search_tools,
    "show_card" to R.string.headunit_tool_show_card, "ask_user" to R.string.headunit_tool_ask_user,
    "read_file" to R.string.headunit_tool_read_file, "list_dir" to R.string.headunit_tool_list_dir,
    "write_file" to R.string.headunit_tool_write_file, "todo_write" to R.string.headunit_tool_todo_write,
    "update_todo" to R.string.headunit_tool_update_todo, "create_plan" to R.string.headunit_tool_set_reminder,
    "update_plan" to R.string.headunit_tool_update_plan, "android_use" to R.string.headunit_tool_android_use,
    "finish" to R.string.headunit_tool_finish, "plan_manage" to R.string.headunit_tool_set_reminder,
    "plan_list" to R.string.headunit_tool_plan_list, "user_memory" to R.string.headunit_tool_user_memory,
    "offer_options" to R.string.headunit_tool_offer_choice, "offer_choice" to R.string.headunit_tool_offer_choice,
    "offer_slider" to R.string.headunit_tool_offer_slider, "offer_switch" to R.string.headunit_tool_offer_switch,
    // Bothy's own media tools (the runtime's device media provider): what plays on the tablet, wherever it is
    // shown.
    "media.status" to R.string.headunit_tool_media_status, "media.control" to R.string.headunit_tool_media_control,
    "media.volume" to R.string.headunit_tool_media_volume,
    "media.play_from_search" to R.string.headunit_tool_media_play_from_search,
)

/** A tool of this app's servers that [TOOL_TITLES] does not name yet: the title it is served with. */
private val SERVED_TITLES: Map<String, String> by lazy {
    val nav = NavController(PlaygroundStore(), UnavailableNavEngine(""))
    (CABIN_TOOLS + HOME_TOOLS + navTools(nav) + appNavigationTool(nav)).associate { it.name to it.title }
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
                    val greeting = stringResource(R.string.headunit_greeting)
                    Text(greeting, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    val hint = stringResource(R.string.headunit_wake_hint)
                    Text(hint, color = UnitColors.muted, fontSize = 12.sp, maxLines = 1)
                }
                val wake = stringResource(R.string.headunit_wake_xiaobo)
                Icon(Icons.Filled.Mic, contentDescription = wake, tint = UnitColors.muted, modifier = Modifier.size(22.dp).clickable { Assistant.wake(context) })
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            if (city.isEmpty()) {
                stringResource(R.string.headunit_locating)
            } else {
                stringResource(R.string.headunit_current_location_in, city)
            },
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
        val title = stringResource(R.string.headunit_try_saying_to)
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
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
        val title = stringResource(R.string.headunit_route_details)
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
        nav.waypoints.forEachIndexed { i, stop ->
            val left = nav.toWaypoints.getOrNull(i)
            StopRow(stringResource(R.string.headunit_stop_badge, i + 1), stop.name, left?.seconds, left?.meters, now)
        }
        val end = stringResource(R.string.nav_end_badge)
        StopRow(end, nav.destination?.name.orEmpty(), nav.remainingSeconds, nav.remainingMeters, now, isEnd = true)
    }
}

@Composable
private fun StopRow(badge: String, name: String, seconds: Int?, meters: Int?, now: Long, isEnd: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(badge, color = if (isEnd) PlaygroundColors.ember else UnitColors.teal, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (seconds != null && meters != null) {
                val eta = etaText(now + seconds * 1000L, now)
                val left = "${seconds.durationText()} · ${meters.distanceText()} · $eta"
                Text(left, color = UnitColors.muted, fontSize = 12.sp)
            }
        }
    }
}
