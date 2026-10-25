package cc.noharry.bothy.playground

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Where things go on the real-navigation head unit ([realSlots]). [assistant] is null while 小博's panel is down. */
internal class RealSlots(
    val map: MapBox,
    val assistant: MapBox?,
    val route: RouteSlots,
    /** The status chips' top right corner. */
    val statusTop: Dp,
    val statusEnd: Dp,
    val bar: MapBox,
    /** How far from the map's left edge the cards over it reach: the map keeps the car and the route clear of them. */
    val coverLeft: Dp,
)

/**
 * The real-navigation head unit's places on a [width] × [height] screen, with 小博's [panel] up or not and the route's
 * cards up or not ([routeCards]): the map under everything, a bar along the bottom edge; 小博's panel covers the driver's
 * third of the map (its voice is in the bar's corner, [VoiceDock]); the route's cards go top left, beside the panel.
 */
internal fun realSlots(width: Dp, height: Dp, panel: Boolean, routeCards: Boolean): RealSlots {
    val barHeight = 64.dp
    val mapHeight = height - barHeight
    val third = width / 3 - PAD
    val routeWidth = width * 0.24f
    val routeX = if (panel) PAD + third + GAP else PAD
    return RealSlots(
        map = MapBox(0.dp, 0.dp, width, mapHeight),
        assistant = if (panel) MapBox(PAD, PAD, third, mapHeight - PAD * 2) else null,
        route = RouteSlots(
            x = routeX, top = PAD + STATUS_HEIGHT, width = routeWidth, actionsBottom = mapHeight - PAD,
            progressX = width - PROGRESS_INSET, progressTop = STATUS_HEIGHT + 30.dp, progressHeight = mapHeight * 0.8f,
            roadX = width / 2 - 80.dp, roadY = mapHeight - PAD - 36.dp,
        ),
        statusTop = PAD / 2,
        statusEnd = PAD,
        bar = MapBox(0.dp, mapHeight, width, barHeight),
        coverLeft = (if (panel) PAD + third + GAP else 0.dp) + if (routeCards) routeWidth + GAP + (if (panel) 0.dp else PAD) else 0.dp,
    )
}

private val PAD = 16.dp
private val GAP = 10.dp
private val STATUS_HEIGHT = 44.dp

/**
 * The real-navigation head unit (owner 2026-10-22): the map, the route's cards, 小博 and a bar along the bottom with the
 * device's own media. 小博 shows its bar while it listens and its panel while it works and a while after it answered
 * ([AssistantView.open]), then retreats, and the map has almost the whole screen ([realSlots]).
 */
@Composable
internal fun MapHeadUnit(
    world: World,
    calls: List<CallRecord>,
    server: ServerState,
    flashLevel: Float,
    store: PlaygroundStore,
    sheet: Boolean,
    onSheet: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    // The request whose panel was closed with its X: it stays closed, while voice ends.
    var dismissed by remember { mutableStateOf<String?>(null) }
    var cuedAt by remember { mutableLongStateOf(0L) }
    // The planned route's cards wait until the map has shown each place found and then the route.
    var revealed by remember { mutableStateOf(true) }
    val map = remember { NaviMapHandle() }
    val now = rememberClock()
    val assistant by Assistant.state.collectAsState()
    val weather = rememberWeather()
    val playing by NowPlaying.state.collectAsState()
    val nav = world.cabin.nav
    TakeOverAssistant()
    FollowTheMedia()
    // The panel only while there is something to show; listening is the bar's corner ([VoiceDock]). Touched, it stays.
    var held by remember { mutableStateOf<String?>(null) }
    FoldOnDismiss(assistant) { dismissed = it }
    val panel = assistant.open(now, dismissed, held)
    val close = { dismissed = assistant.request.ifBlank { null }; Assistant.close(context) }
    ScreenFill {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val routeCards = nav.driving || (nav.previewing && revealed)
            val slots = realSlots(maxWidth, maxHeight, panel, routeCards)
            val density = LocalDensity.current
            // The junction's picture spans the next column too: the car moves over, as on AMap's stock screen.
            val crossing = Guidance.cross.collectAsState().value != null && nav.driving && routeCards
            val covered = slots.coverLeft + if (crossing) junctionExtra(slots.route.width, JUNCTION_GAP) else 0.dp
            map.cover(with(density) { covered.roundToPx() }, progressSpace(nav.driving))
            RestTheMap(map, true, nav.active, cuedAt, slots.map)
            PlayMapCues(map, true) { cue ->
                cuedAt = System.currentTimeMillis()
                revealed = cue == MapCue.Route
            }
            FollowTheCar(map, nav, cuedAt)
            MapLayer(mapBlocked(context), map, slots.map, corner = 0.dp)
            // Search and what to look for nearby are on the bar: nothing over the map until there is a route.
            // The bar holds the settings: no 「更多」 on the route's actions.
            if (routeCards) MapOverlays(world, now, map, store, slots.route, onMore = null)
            slots.assistant?.let { box ->
                val placed = Modifier.offset(box.x, box.y).size(box.width, box.height)
                AssistantChatPanel(assistant, null, close, placed, assistant.closesAt(held), voiceInBar = true) { held = assistant.request }
            }
            RealStatus(server, weather, now, Modifier.offset(y = slots.statusTop).padding(end = slots.statusEnd).align(Alignment.TopEnd))
            RealBar(assistant, playing, DEVICE_MEDIA, slots.bar, maxWidth / 3, close) { onSheet(true) }
        }
        if (sheet) SettingsSheet(world, calls, server, flashLevel, store) { onSheet(false) }
    }
}

/** Top right: the mode's switch, the server, the weather and the time (the settings are on the bar). */
@Composable
private fun RealStatus(server: ServerState, weather: WeatherReport?, now: Long, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(UnitColors.card).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModeSwitch()
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (server.status == ServerStatus.RUNNING) PlaygroundColors.sage else PlaygroundColors.ember))
        weather?.let { StatusLabel("${it.weather} ${it.temperature}°") }
        StatusLabel(clockText(now, now))
    }
}

@Composable
private fun StatusLabel(text: String) {
    Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
}

/**
 * Along the bottom, each control once (owner 2026-10-22: 「很多重复控件」): 小博 (tapped, it listens: search is said to it;
 * its face is where it went when it retreated), what to look for nearby, the play bar, the settings: a flat strip along
 * the edge.
 */
@Composable
private fun RealBar(assistant: AssistantView, playing: MediaNow, controls: MediaControls, box: MapBox, voiceWidth: Dp, onClose: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.offset(box.x, box.y).size(box.width, box.height).background(STRIP)) {
        // In the bar itself, not a box of its own: it rises from the bar's bottom edge under 小博's corner and fades out.
        VoiceGlow(assistant.listening, assistant.speaking, Modifier.fillMaxHeight().width(voiceWidth + GLOW_FADE))
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VoiceDock(assistant, voiceWidth, onClose)
            // While 小博 listens or talks the corner and its light need the room (owner: 「这个引导不要和光效叠一起」).
            if (!assistant.listening && !assistant.speaking) SuggestionTicker(REAL_SUGGESTIONS) { Assistant.say(context, it) }
            Spacer(Modifier.weight(1f))
            PlayStrip(playing, controls)
            PlainIcon(Icons.Filled.Settings, size = 24, onClick = onSettings)
        }
    }
}

/** How far past 小博's corner the voice light reaches as it fades. */
private val GLOW_FADE = 120.dp

/** While the head unit is in front it follows the device's media sessions ([NowPlaying]), again after the settings. */
@Composable
private fun FollowTheMedia() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) NowPlaying.start(context) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            NowPlaying.stop()
        }
    }
}

/**
 * The play bar in the bottom bar: what plays and in which app, previous, play or pause, next, through the playing app's
 * media session ([NowPlaying]). Without notification access it offers the settings page that grants it.
 */
@Composable
private fun PlayStrip(now: MediaNow, controls: MediaControls) {
    val context = LocalContext.current
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(UnitColors.raised)
            .then(if (now.access) Modifier else Modifier.clickable { NowPlaying.openAccessSettings(context) })
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.MusicNote, contentDescription = null, tint = if (now.playing) UnitColors.teal else UnitColors.muted, modifier = Modifier.size(20.dp))
        when {
            !now.access -> Text("点此开启播放控制", color = UnitColors.muted, fontSize = 13.sp, maxLines = 1)
            !now.any -> Text("没有在放", color = UnitColors.muted, fontSize = 13.sp, maxLines = 1)
            else -> {
                Column(Modifier.widthIn(max = PLAY_TEXT_WIDTH)) {
                    Text(now.title.ifBlank { now.app }, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val under = listOf(now.artist, now.app).filter(String::isNotBlank).joinToString(" · ")
                    if (under.isNotEmpty()) Text(under, color = UnitColors.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PlainIcon(Icons.Filled.SkipPrevious, size = 24, onClick = controls.previous)
                PlainIcon(if (now.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, UnitColors.teal, size = 28, onClick = controls.playPause)
                PlainIcon(Icons.Filled.SkipNext, size = 24, onClick = controls.next)
            }
        }
    }
}

private val PLAY_TEXT_WIDTH = 240.dp

/** What the play bar's buttons do. */
internal class MediaControls(val previous: () -> Unit, val playPause: () -> Unit, val next: () -> Unit)

// Lambdas, not references: NowPlaying is made on first use, never when this file loads (it needs the main looper).
private val DEVICE_MEDIA = MediaControls({ NowPlaying.previous() }, { NowPlaying.playPause() }, { NowPlaying.next() })

/** What the bar offers to look for nearby, and what each says to 小博. */
/**
 * What the bar suggests saying, a line at a time (owner 2026-10-25: 「改成滚动的吧，多弄几个引导的场景，不光是导航的」):
 * what real navigation can do with the device alone, the places nearby, the weather, music and video, reminders, apps.
 */
private val REAL_SUGGESTIONS = listOf(
    "附近有充电站吗", "明天天气怎么样", "放点轻松的音乐", "找一下附近的停车场", "明早八点提醒我带伞", "现在放的是什么歌",
    "这周末附近有什么活动", "找个加油站", "打开哔哩哔哩", "下一首", "附近有什么好吃的", "音量调小一点", "今天有什么新闻",
)


/** The bottom strip with the map first: darker than the cards over the map. */
private val STRIP = Color(0xFF0B0E12)
