package cc.noharry.bothy.playground

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Search
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

/** What 小博 shows on the real-navigation head unit: nothing (it retreated), the listening bar, or its panel. */
internal enum class AssistantShows { NOTHING, BAR, PANEL }

/** Where things go on the real-navigation head unit ([realSlots]). [assistant] is null while 小博 shows nothing. */
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
    /** How much of the map's bottom lies under the bar or 小博's listening bar: AMap's logo goes above it. */
    val logoLift: Dp,
)

/**
 * The real-navigation head unit's places for [theme] on a [width] × [height] screen, with 小博 showing [shows] and the
 * route's cards up or not ([routeCards]).
 *
 * In columns (the simulated head unit's grid, the car's column given to 小博), 小博's panel takes the first two columns
 * and the map the rest; listening, its bar sits over the map's top left, the route's cards below it; with nothing to
 * show it retreats and the map runs edge to edge, under the bar along the bottom.
 *
 * With the map first (the map under everything, a bar along the bottom edge), 小博's panel covers the driver's third of
 * the map and its listening bar sits over the bottom left; the route's cards go top left, beside the panel when it is up.
 */
internal fun realSlots(theme: HeadUnitTheme, width: Dp, height: Dp, shows: AssistantShows, routeCards: Boolean): RealSlots = when (theme) {
    HeadUnitTheme.COLUMNS -> columnSlots(width, height, shows, routeCards)
    HeadUnitTheme.FULL_MAP -> fullMapSlots(width, height, shows, routeCards)
}

private fun columnSlots(width: Dp, height: Dp, shows: AssistantShows, routeCards: Boolean): RealSlots {
    val leftWidth = width * 0.25f
    val firstColumn = PAD + leftWidth + GAP
    val column = (width - firstColumn - PAD - GAP * 2) / 3
    fun col(n: Int) = firstColumn + (column + GAP) * (n - 2)
    val barHeight = 58.dp
    val barTop = height - PAD - barHeight
    val contentHeight = barTop - GAP - STATUS_HEIGHT
    val panel = shows == AssistantShows.PANEL
    val mapX = if (panel) col(3) - GAP / 2 else 0.dp
    val routeX = if (panel) col(3) else PAD
    return RealSlots(
        map = MapBox(mapX, 0.dp, width - mapX, height),
        assistant = when (shows) {
            AssistantShows.PANEL -> MapBox(PAD, STATUS_HEIGHT, col(2) + column - PAD, contentHeight)
            AssistantShows.BAR -> MapBox(PAD, STATUS_HEIGHT, leftWidth, BAR_HEIGHT)
            AssistantShows.NOTHING -> null
        },
        route = RouteSlots(
            x = routeX, top = STATUS_HEIGHT + if (shows == AssistantShows.BAR) BAR_SPACE else 0.dp, width = column, actionsBottom = barTop - GAP,
            progressX = width - 30.dp, progressTop = STATUS_HEIGHT + 30.dp, progressHeight = contentHeight * 0.75f,
            roadX = col(4), roadY = barTop - GAP - 36.dp,
        ),
        statusTop = 0.dp,
        statusEnd = PAD,
        bar = MapBox(PAD, barTop, width - PAD * 2, barHeight),
        coverLeft = (routeX - mapX + column + GAP).takeIf { routeCards } ?: 0.dp,
        logoLift = height - barTop + GAP,
    )
}

private fun fullMapSlots(width: Dp, height: Dp, shows: AssistantShows, routeCards: Boolean): RealSlots {
    val barHeight = 64.dp
    val mapHeight = height - barHeight
    val third = width / 3 - PAD
    val routeWidth = width * 0.24f
    val panel = shows == AssistantShows.PANEL
    val routeX = if (panel) PAD + third + GAP else PAD
    return RealSlots(
        map = MapBox(0.dp, 0.dp, width, mapHeight),
        assistant = when (shows) {
            AssistantShows.PANEL -> MapBox(PAD, PAD, third, mapHeight - PAD * 2)
            AssistantShows.BAR -> MapBox(PAD, mapHeight - PAD - BAR_HEIGHT, third, BAR_HEIGHT)
            AssistantShows.NOTHING -> null
        },
        route = RouteSlots(
            x = routeX, top = PAD + STATUS_HEIGHT, width = routeWidth, actionsBottom = mapHeight - PAD,
            progressX = width - 30.dp, progressTop = STATUS_HEIGHT + 30.dp, progressHeight = mapHeight * 0.7f,
            roadX = width / 2 - 80.dp, roadY = mapHeight - PAD - 36.dp,
        ),
        statusTop = PAD / 2,
        statusEnd = PAD,
        bar = MapBox(0.dp, mapHeight, width, barHeight),
        coverLeft = (if (panel) PAD + third + GAP else 0.dp) + if (routeCards) routeWidth + GAP + (if (panel) 0.dp else PAD) else 0.dp,
        logoLift = if (shows == AssistantShows.BAR) PAD + BAR_HEIGHT + GAP else 0.dp,
    )
}

private val PAD = 16.dp
private val GAP = 10.dp
private val STATUS_HEIGHT = 44.dp

/**
 * The head unit with real navigation (owner 2026-10-22): the map, the route's cards, 小博 and a bar along the bottom;
 * nothing simulated. 小博 shows its bar while it listens and its panel while it works and a while after it answered
 * ([AssistantView.open]), then retreats, and the map has almost the whole screen. [theme] places them ([realSlots]).
 */
@Composable
internal fun RealNavHeadUnit(
    world: World,
    calls: List<CallRecord>,
    server: ServerState,
    flashLevel: Float,
    store: PlaygroundStore,
    theme: HeadUnitTheme,
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
    val shows = when {
        assistant.open(now, dismissed) -> AssistantShows.PANEL
        assistant.hearing(now, dismissed) -> AssistantShows.BAR
        else -> AssistantShows.NOTHING
    }
    val close = { dismissed = assistant.request.ifBlank { null }; Assistant.close(context) }
    ScreenFill {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val routeCards = nav.driving || (nav.previewing && revealed)
            val slots = realSlots(theme, maxWidth, maxHeight, shows, routeCards)
            val density = LocalDensity.current
            map.cover(with(density) { slots.coverLeft.roundToPx() }, progressSpace(nav.driving))
            map.liftLogo(with(density) { slots.logoLift.roundToPx() })
            RestTheMap(map, true, nav.active, cuedAt, slots.map)
            PlayMapCues(map, true) { cue ->
                cuedAt = System.currentTimeMillis()
                revealed = cue == MapCue.Route
            }
            FollowTheCar(map, nav, cuedAt)
            MapLayer(mapBlocked(context), map, slots.map, corner = if (theme == HeadUnitTheme.COLUMNS) 18.dp else 0.dp)
            if (routeCards) {
                MapOverlays(world, now, map, store, slots.route) { onSheet(true) }
            } else {
                MapToolbar(Modifier.offset(slots.route.x, slots.route.top).width(slots.route.width))
            }
            slots.assistant?.let { box ->
                val placed = Modifier.offset(box.x, box.y).size(box.width, box.height)
                if (shows == AssistantShows.PANEL) AssistantChatPanel(assistant, null, close, placed) else AssistantBar(assistant, close, placed)
            }
            RealStatus(server, weather, now, Modifier.offset(y = slots.statusTop).padding(end = slots.statusEnd).align(Alignment.TopEnd)) { onSheet(true) }
            RealBar(theme, assistant, playing, slots.bar) { onSheet(true) }
        }
        if (sheet) SettingsSheet(world, calls, server, flashLevel, store) { onSheet(false) }
    }
}

/** Top right: that this is real navigation, the server, the weather where the device is, the time and the settings. */
@Composable
private fun RealStatus(server: ServerState, weather: WeatherReport?, now: Long, modifier: Modifier, onSettings: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(UnitColors.card).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (server.status == ServerStatus.RUNNING) PlaygroundColors.sage else PlaygroundColors.ember))
        StatusLabel("真实导航")
        weather?.let { StatusLabel("${it.weather} ${it.temperature}°") }
        StatusLabel(clockText(now, now))
        PlainIcon(Icons.Filled.Settings, size = 20, onClick = onSettings)
    }
}

@Composable
private fun StatusLabel(text: String) {
    Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
}

/**
 * Along the bottom: 小博 (tapped, it listens; its face is where it went when it retreated), search, what to look for
 * nearby, the settings. In columns a card like the simulated dock; with the map first, a flat strip along the edge.
 */
@Composable
private fun RealBar(theme: HeadUnitTheme, assistant: AssistantView, playing: MediaNow, box: MapBox, onSettings: () -> Unit) {
    val context = LocalContext.current
    val shape = if (theme == HeadUnitTheme.COLUMNS) RoundedCornerShape(18.dp) else RoundedCornerShape(0.dp)
    Row(
        Modifier.offset(box.x, box.y).size(box.width, box.height).clip(shape)
            .background(if (theme == HeadUnitTheme.COLUMNS) UnitColors.panel else STRIP).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).clickable { Assistant.wake(context) }.padding(end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Mascot(assistant, 40.dp)
            Column {
                Text("小博", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("说「你好小博」或点我", color = UnitColors.muted, fontSize = 11.sp, maxLines = 1)
            }
        }
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).background(UnitColors.raised).clickable { Assistant.wake(context) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = UnitColors.muted, modifier = Modifier.size(18.dp))
            Text("去哪儿", color = UnitColors.muted, fontSize = 13.sp, maxLines = 1)
        }
        NEARBY_REAL.forEach { (label, line) -> UnitPill(label, on = false) { Assistant.say(context, line) } }
        Spacer(Modifier.weight(1f))
        PlayStrip(playing)
        PlainIcon(Icons.Filled.Settings, size = 24, onClick = onSettings)
    }
}

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
private fun PlayStrip(now: MediaNow) {
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
                PlainIcon(Icons.Filled.SkipPrevious, size = 24) { NowPlaying.previous() }
                PlainIcon(if (now.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, UnitColors.teal, size = 28) { NowPlaying.playPause() }
                PlainIcon(Icons.Filled.SkipNext, size = 24) { NowPlaying.next() }
            }
        }
    }
}

private val PLAY_TEXT_WIDTH = 240.dp

/** What the bar offers to look for nearby, and what each says to 小博. */
private val NEARBY_REAL = listOf("充电站" to "找一下附近的充电站", "停车场" to "找一下附近的停车场", "加油站" to "找一下附近的加油站")

/** 小博's listening bar's height. */
private val BAR_HEIGHT = 56.dp

/** The bottom strip with the map first: darker than the cards over the map. */
private val STRIP = Color(0xFF0B0E12)
