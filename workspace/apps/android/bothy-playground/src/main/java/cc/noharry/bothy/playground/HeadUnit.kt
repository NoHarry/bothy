package cc.noharry.bothy.playground

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.navi.model.AMapLaneInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The car as a head unit shows it, laid out as the head unit's own screen: the car in a left column, then three equal
 * columns. At home they hold the assistant's card (with a small map), the weather and what to say; with the map up (the
 * dock's map button, a search, a route) the map fills all three. While 小博 only listens, the words show in a slim bar;
 * once it takes a request its card opens in the second column with its steps and its answer, the map moving to what it
 * searches; a route shows whole first, then drives. Bothy's own floating card stays hidden meanwhile ([Assistant]).
 */
@Composable
internal fun HeadUnit(world: World, calls: List<CallRecord>, server: ServerState, flash: Flash, flashLevel: Float, store: PlaygroundStore, onHome: () -> Unit) {
    val mode by HeadUnitModes.mode.collectAsState()
    // Held above the switch, so the sheet stays open across it (the switch is in the sheet).
    var sheet by remember { mutableStateOf(false) }
    if (mode.layout == HeadUnitTheme.CLASSIC) {
        SimulatedHeadUnit(world, calls, server, flash, flashLevel, store, onHome, sheet) { sheet = it }
    } else {
        MapHeadUnit(world, calls, server, flashLevel, store, sheet) { sheet = it }
    }
}

/** The simulated car's head unit ([HeadUnit]); the settings sheet shows while [sheet]. */
@Composable
private fun SimulatedHeadUnit(
    world: World,
    calls: List<CallRecord>,
    server: ServerState,
    flash: Flash,
    flashLevel: Float,
    store: PlaygroundStore,
    onHome: () -> Unit,
    sheet: Boolean,
    onSheet: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var mapMode by remember { mutableStateOf(false) }
    // The request whose card was closed with its X: it stays closed, while voice ends.
    var dismissed by remember { mutableStateOf<String?>(null) }
    var cuedAt by remember { mutableLongStateOf(0L) }
    // The planned route's cards wait until the map has shown each place found and then the route.
    var revealed by remember { mutableStateOf(true) }
    val map = remember { NaviMapHandle() }
    val now = rememberClock()
    val assistant by Assistant.state.collectAsState()
    val nav = world.cabin.nav
    TakeOverAssistant()
    // The request whose card was touched: it stays until closed, as Bothy's own card does.
    var held by remember { mutableStateOf<String?>(null) }
    val panel = assistant.open(now, dismissed, held)
    val showMap = mapMode || nav.active || now - cuedAt < CUED_MAP_MS
    val close = { dismissed = assistant.request.ifBlank { null }; Assistant.close(context) }
    ScreenFill {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val grid = Grid(maxWidth, maxHeight)
            val mapFrom = if (panel) 3 else 2
            val density = LocalDensity.current
            // The cards over the map's left part: the route's, when there is one.
            val routeCards = showMap && (nav.driving || (nav.previewing && revealed))
            // The map's left under the route's cards (and the column gap), its right under the progress bar.
            val crossing = Guidance.cross.collectAsState().value != null && nav.driving
            val covered = grid.columnWidth + grid.gap + if (crossing) junctionExtra(grid.columnWidth, grid.gap) else 0.dp
            map.cover(with(density) { covered.roundToPx() }.takeIf { routeCards } ?: 0, progressSpace(nav.driving && showMap))
            RestTheMap(map, showMap, nav.active, cuedAt)
            PlayMapCues(map, showMap) { cue ->
                cuedAt = System.currentTimeMillis()
                revealed = cue == MapCue.Route
            }
            FollowTheCar(map, nav, cuedAt)
            LeftColumn(world, flash, store, grid, assistant.listening, onHome, { mapMode = !mapMode; if (!mapMode) map.overview(false) }) { onSheet(true) }
            if (!showMap && !panel) {
                AssistantMiniCard(Playground.engine?.currentCity().orEmpty(), assistant, close, { mapMode = true }, grid.column(2))
            }
            MapLayer(mapBlocked(context), map, if (showMap) grid.bigMap(mapFrom) else grid.miniMap())
            if (!showMap) HomeCards(world, grid)
            val hearing = assistant.hearing(now, dismissed, held)
            if (routeCards) MapOverlays(world, now, map, store, grid.routeSlots(mapFrom, if (hearing) BAR_SPACE else 0.dp)) { onSheet(true) }
            if (showMap && hearing) AssistantBar(assistant, close, grid.barSlot(mapFrom))
            // The map's own bar stays at its top left, beside 小博's card too, as on the head unit.
            if (showMap && !hearing && !routeCards) MapToolbar(grid.barSlot(mapFrom))
            if (panel) AssistantChatPanel(assistant, world.cabin.speaker, close, grid.column(2), assistant.closesAt(held)) { held = assistant.request }
            StatusRow(world, server, now, grid, onHome) { onSheet(true) }
            ClimateBar(world.cabin, store, Modifier.offset(grid.col(2), grid.barTop).width(grid.col(4) - grid.col(2) - grid.gap).height(grid.barHeight))
        }
        if (sheet) SettingsSheet(world, calls, server, flashLevel, store) { onSheet(false) }
    }
}

/**
 * The screen's grid: a left column for the car, three equal columns after it, a status row along the top and a bar row
 * along the bottom. The map, when up, runs from the second column to the right edge, top to bottom.
 */
private class Grid(val width: Dp, val height: Dp) {
    val pad = 16.dp
    val gap = 10.dp
    val statusHeight = 44.dp
    val barHeight = 58.dp
    val leftWidth = width * LEFT_SHARE
    private val firstColumn = pad + leftWidth + gap
    val columnWidth = (width - firstColumn - pad - gap * 2) / 3
    val contentTop = statusHeight
    val barTop = height - pad - barHeight
    val contentHeight = barTop - gap - contentTop

    /** Where column [n] (2…4) starts. */
    fun col(n: Int): Dp = firstColumn + (columnWidth + gap) * (n - 2)

    fun column(n: Int): Modifier = Modifier.offset(col(n), contentTop).width(columnWidth).height(contentHeight)

    /** The map up, from column [from] to the right edge, top to bottom. */
    fun bigMap(from: Int) = MapBox(col(from) - gap / 2, 0.dp, width - col(from) + gap / 2, height)

    fun miniMap() = MapBox(col(2) + 14.dp, contentTop + 84.dp, columnWidth - 28.dp, contentHeight - 84.dp - 58.dp)

    /** The listening bar's place: the top of the map's first column. */
    fun barSlot(from: Int): Modifier = Modifier.offset(col(from), contentTop).width(columnWidth)

    /** The route's cards in the map's first column ([from]), [below] the listening bar when it shows. */
    fun routeSlots(from: Int, below: Dp) = RouteSlots(
        x = col(from), top = contentTop + below, width = columnWidth, actionsBottom = barTop - gap,
        progressX = width - PROGRESS_INSET, progressTop = contentTop + 30.dp, progressHeight = contentHeight * 0.85f,
        roadX = col(4), roadY = barTop + 8.dp,
    )
}

/**
 * Where the route's things go over the map: the cards' column ([x], [top], [width]), the action bar ending at
 * [actionsBottom], the progress bar along the right edge and the road's name.
 */
internal class RouteSlots(
    val x: Dp,
    val top: Dp,
    val width: Dp,
    val actionsBottom: Dp,
    val progressX: Dp,
    val progressTop: Dp,
    val progressHeight: Dp,
    val roadX: Dp,
    val roadY: Dp,
)

internal data class MapBox(val x: Dp, val y: Dp, val width: Dp, val height: Dp)

/** The car's column: gear and range, the car, the media, and the dock under it. */
@Composable
private fun LeftColumn(world: World, flash: Flash, store: PlaygroundStore, grid: Grid, listening: Boolean, onHome: () -> Unit, onMap: () -> Unit, onSettings: () -> Unit) {
    Box(Modifier.offset(grid.pad, 14.dp)) { GearStrip(world.cabin) }
    CarColumn(world.cabin, flash, store, listening, Modifier.offset(grid.pad, grid.contentTop).width(grid.leftWidth).height(grid.contentHeight))
    Dock(onHome, onSettings, onMap, Modifier.offset(grid.pad, grid.barTop).width(grid.leftWidth).height(grid.barHeight))
}

/** At home, the weather and what to say in the third and fourth columns. */
@Composable
private fun HomeCards(world: World, grid: Grid) {
    val context = LocalContext.current
    val weather = rememberWeather()
    WeatherCard(weather, world.cabin.drive.outsideTempC, grid.column(3))
    SuggestionsCard(SUGGESTIONS, { Assistant.say(context, it) }, grid.column(4))
}

/**
 * Over the map, in the route's column ([slots]; on the simulated head unit the map's first, the third while 小博's card
 * holds the second, below the listening bar when it shows): the route (its preview, or the turn and the next stop, or
 * every stop); the quick actions under it; the progress along the right edge.
 */
@Composable
internal fun MapOverlays(world: World, now: Long, map: NaviMapHandle, store: PlaygroundStore, slots: RouteSlots, onMore: (() -> Unit)?) {
    val nav = world.cabin.nav
    val scope = rememberCoroutineScope()
    var details by remember { mutableStateOf(false) }
    // The stops listed, the map shows the whole route they are on; folded, it follows the car again.
    LaunchedEffect(details) { if (nav.driving) map.overview(details) }
    val navigation = Playground.navigation
    val lanes by Guidance.lanes.collectAsState()
    val cross by Guidance.cross.collectAsState()
    val room = slots.actionsBottom - slots.top - (if (nav.driving) ACTION_BAR_HEIGHT + 10.dp else 0.dp)
    FittingColumn(10.dp, Modifier.offset(slots.x, slots.top).width(slots.width).heightIn(max = room.coerceAtLeast(0.dp))) {
        if (nav.previewing) {
            RoutePreviewCard(nav, { scope.launch(Dispatchers.IO) { navigation?.begin() } }, { scope.launch(Dispatchers.IO) { navigation?.stop() } })
            RouteDetails(nav, now)
        } else {
            val replay: () -> Unit = { scope.launch(Dispatchers.IO) { Playground.engine?.replay() } }
            // The junction's picture while AMap shows it, as its stock screen does: over the next column too, the turn on top.
            val junction = cross.takeIf { nav.driving }
            when {
                junction != null -> JunctionCard(
                    nav, junction,
                    Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(slots.width + junctionExtra(slots.width, JUNCTION_GAP)),
                )
                details -> {
                    TurnCard(nav, now, onReplay = replay.takeIf { nav.driving })
                    RouteDetails(nav, now, Modifier.clickable { details = false })
                }
                else -> {
                    TurnCard(nav, now, onReplay = replay.takeIf { nav.driving })
                    NextStopCard(nav, now, { details = true })
                }
            }
            if (nav.driving && nav.guide.stopName.isNotBlank()) RoadsideCard(nav.guide)
        }
    }
    lanes?.takeIf { nav.driving }?.let { LanesOverMap(it, slots) }
    if (nav.driving) DrivingControls(nav, map, store, slots, onMore)
}

/**
 * Its children top to bottom, [gap] apart, as many as fit its height: on a short screen (a phone, owner 2026-10-23) the
 * cards under the turn give way rather than run under the actions. The first always shows, within the height.
 */
@Composable
private fun FittingColumn(gap: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { children, constraints ->
        val gapPx = gap.roundToPx()
        val shown = mutableListOf<Placeable>()
        var used = 0
        for (child in children) {
            val top = if (shown.isEmpty()) 0 else used + gapPx
            val placeable = child.measure(constraints.copy(minWidth = 0, minHeight = 0, maxHeight = if (shown.isEmpty()) constraints.maxHeight else Constraints.Infinity))
            if (shown.isNotEmpty() && top + placeable.height > constraints.maxHeight) break
            shown += placeable
            used = top + placeable.height
        }
        layout(constraints.maxWidth, used) {
            var y = 0
            shown.forEach { it.place(0, y); y += it.height + gapPx }
        }
    }
}

/**
 * Driving, the controls along the map, as AMap's stock screen has them: the actions, the traffic bar, the road; once the
 * user moves the map off the car, 继续导航 and zoom in the road's place until AMap locks it on the car again.
 */
@Composable
private fun DrivingControls(nav: NavState, map: NaviMapHandle, store: PlaygroundStore, slots: RouteSlots, onMore: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    var overview by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    // A simulated drive can be held, as on AMap's stock screen; a new route drives again.
    var paused by remember(nav.destination, nav.totalMeters) { mutableStateOf(false) }
    val browsing by map.browsing.collectAsState()
    val traffic by Guidance.traffic.collectAsState()
    NavActionBar(
        overview, muted, paused.takeIf { nav.simulated },
        onExit = {
            scope.launch {
                withContext(Dispatchers.IO) { Playground.navigation?.stop() }
                store.record("cabin", PlaygroundStore.MANUAL, "退出导航", setOf(Highlight.NAV), fromAssistant = false)
            }
        },
        onOverview = { overview = !overview; map.overview(overview) },
        onMute = { muted = !muted; Playground.engine?.setMuted(muted) },
        onPause = { paused = !paused; scope.launch(Dispatchers.IO) { Playground.engine?.pause(paused) } },
        onMore = onMore,
        modifier = Modifier.offset(slots.x, slots.actionsBottom - ACTION_BAR_HEIGHT).width(slots.width),
    )
    RouteProgressBar(nav, traffic, Modifier.offset(slots.progressX, slots.progressTop).height(slots.progressHeight))
    if (browsing && !overview) {
        BrowseControls({ map.relockNow() }, map::zoom, Modifier.offset(slots.roadX, slots.roadY - 10.dp))
    } else {
        RoadChip(nav.currentRoad, Modifier.offset(slots.roadX, slots.roadY))
    }
}

/** The lanes at the junction ahead, over the top of the open map between the route and the progress, as AMap's stock screen puts them. */
@Composable
private fun LanesOverMap(lanes: AMapLaneInfo, slots: RouteSlots) {
    val left = slots.x + slots.width + 10.dp
    Box(Modifier.offset(left, slots.top).width(slots.progressX - 10.dp - left), contentAlignment = Alignment.TopCenter) { LaneBar(lanes) }
}

/** The map's own bar while nothing else is on it: search (小博 listens) and what to look for nearby (said to 小博). */
@Composable
internal fun MapToolbar(modifier: Modifier) {
    val context = LocalContext.current
    Row(
        modifier.clip(RoundedCornerShape(18.dp)).background(UnitColors.card).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(UnitColors.raised).clickable { Assistant.wake(context) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = UnitColors.muted, modifier = Modifier.size(18.dp))
            Text("搜索", color = UnitColors.muted, fontSize = 13.sp, maxLines = 1)
        }
        NEARBY.forEach { (label, line) -> UnitPill(label, on = false) { Assistant.say(context, line) } }
    }
}

/** The top row: gear and range over the car, where the user is, the servers, the weather and the time on the right. */
@Composable
private fun StatusRow(world: World, server: ServerState, now: Long, grid: Grid, onHome: () -> Unit, onSettings: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(grid.statusHeight)) {
        StatusCluster(world, server, now, onSettings, onHome, Modifier.align(Alignment.CenterEnd).padding(end = grid.pad))
    }
}

/** The one map, moved and resized between the small one at home and the big one, never made again. */
@Composable
internal fun MapLayer(blocked: String?, map: NaviMapHandle, box: MapBox, corner: Dp = 18.dp) {
    val x by animateDpAsState(box.x, tween(MOVE_MS.toInt()), label = "mapX")
    val y by animateDpAsState(box.y, tween(MOVE_MS.toInt()), label = "mapY")
    val w by animateDpAsState(box.width, tween(MOVE_MS.toInt()), label = "mapW")
    val h by animateDpAsState(box.height, tween(MOVE_MS.toInt()), label = "mapH")
    val placed = Modifier.offset(x, y).size(w, h).clip(RoundedCornerShape(corner))
    when {
        blocked == null -> NaviMap(map, placed)
        // No key yet: the way to one, in the map's place.
        !AmapKey.configured(LocalContext.current) -> AmapKeyPrompt(placed)
        else -> MapPlaceholder(blocked, placed)
    }
}

/** Grown or shrunk with nothing on it ([active] no route, nothing found lately), the map shows the car's district again. */
@Composable
internal fun RestTheMap(map: NaviMapHandle, showing: Boolean, active: Boolean, cuedAt: Long, box: MapBox? = null) {
    val density = LocalDensity.current.density
    // [box]: a map that only changed size (real navigation, 小博's panel opening or closing) comes back to the car too.
    LaunchedEffect(showing, active, box) {
        if (active || System.currentTimeMillis() - cuedAt < CUED_MAP_MS) return@LaunchedEffect
        delay(MOVE_MS + GROW_SETTLE_MS)
        Playground.engine?.currentLocation()?.let { map.showCar(it, density) }
    }
}

/**
 * Plays what the navigation finds, one after another ([MapCue]): the map pins each place and moves to it, then shows the
 * route whole, and only then does the route's countdown to driving start ([NavController.routeShown]). [onCue] hears of
 * each as it starts (to keep the map up, and to hold back the route's cards until the route shows); when the map was not
 * up, it is first given time to grow.
 */
@Composable
internal fun PlayMapCues(map: NaviMapHandle, showing: Boolean, onCue: (MapCue) -> Unit) {
    val density = LocalDensity.current.density
    val shown by rememberUpdatedState(showing)
    // While it plays them, planning waits for it (a route drawn pulls the map away from the place it shows).
    DisposableEffect(map) {
        Playground.mapShowing = true
        onDispose {
            Playground.mapShowing = false
            Playground.navigation?.placesShown(all = true)
        }
    }
    LaunchedEffect(map) {
        Playground.mapCues.collect { cue ->
            val grow = !shown
            onCue(cue)
            if (grow) delay(MOVE_MS + GROW_SETTLE_MS)
            when (cue) {
                is MapCue.Found -> {
                    // Driving, the map is held on the car: it shows the whole route instead, the places pinned on it.
                    val driving = Playground.store.read().cabin.nav.driving
                    map.showPlaces(cue.places, density, cue.adding, cue.ending, frame = !driving)
                    if (driving) map.overview(true)
                    delay(if (cue.places.size == 1) PLACE_SHOWN_MS else PLACES_SHOWN_MS)
                    withContext(Dispatchers.IO) { Playground.navigation?.placesShown() }
                }
                MapCue.Route -> {
                    // AMap draws the route itself and moves the map as it does: framed after that.
                    map.drawRoute()
                    delay(ROUTE_DRAWN_MS)
                    map.clearPlaces()
                    if (Playground.store.read().cabin.nav.previewing) {
                        map.showRoute(Playground.engine?.routeLine().orEmpty(), density)
                        withContext(Dispatchers.IO) { Playground.navigation?.routeShown() }
                    }
                }
            }
        }
    }
}

/**
 * Driving, the map follows the car (again a while after it showed places found along the way, [cuedAt]), and loses the
 * route once stopped; before anything, it opens where the car is.
 */
@Composable
internal fun FollowTheCar(map: NaviMapHandle, nav: NavState, cuedAt: Long) {
    val driving by rememberUpdatedState(nav.driving)
    val cued by rememberUpdatedState(cuedAt)
    LaunchedEffect(cuedAt) {
        if (cuedAt == 0L) return@LaunchedEffect
        delay(DRIVING_OVERVIEW_MS)
        if (driving) {
            map.clearPlaces()
            map.overview(false)
        }
    }
    var was by remember { mutableStateOf(nav.status) }
    LaunchedEffect(nav.status) {
        map.moving(nav.status == NavStatus.NAVIGATING)
        when {
            nav.status == NavStatus.NAVIGATING -> map.overview(false)
            // Stopped or arrived, the route it drew would otherwise stay on the map.
            nav.status in ENDED && was !in ENDED -> map.clearRoute()
        }
        was = nav.status
    }
    val density = LocalDensity.current.density
    LaunchedEffect(Unit) {
        var centredOn: GeoPoint? = null
        while (true) {
            Playground.engine?.currentLocation()?.let {
                map.centreOnce(it)
                map.markCar(it, density)
                // Locked on the car once the route's overview is over: it is kept in the middle of the open map.
                if (driving && System.currentTimeMillis() - cued > DRIVING_OVERVIEW_MS) map.keepCarCentred(it)
                // Parked somewhere else (the demo start switched, the device moved far): the map goes with it.
                val moved = centredOn?.let { at -> at.metersTo(it) > RECENTRE_M } ?: false
                if (moved && !driving) map.showCar(it, density)
                if (centredOn == null || moved) centredOn = it
            }
            delay(CENTRE_RETRY_MS)
        }
    }
}

/** While the head unit is in front it shows the assistant: Bothy hides its card. Renewed, so a dead head unit lapses. */
@Composable
internal fun TakeOverAssistant() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> Assistant.announce(context, true)
                Lifecycle.Event.ON_PAUSE -> Assistant.announce(context, false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            Assistant.announce(context, false)
        }
    }
    LaunchedEffect(lifecycle) {
        while (true) {
            delay(RENEW_MS)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) Assistant.announce(context, true)
        }
    }
}

@Composable
internal fun rememberClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(CLOCK_TICK_MS)
        }
    }
    return now
}

/** AMap's weather where the car is, fetched in the background, again every so often. */
@Composable
internal fun rememberWeather(): WeatherReport? {
    var weather by remember { mutableStateOf<WeatherReport?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            weather = withContext(Dispatchers.IO) { runCatching { Playground.engine?.weather() }.getOrNull() } ?: weather
            delay(if (weather == null) WEATHER_RETRY_MS else WEATHER_REFRESH_MS)
        }
    }
    return weather
}

/** Why the map cannot show yet, or null when it can (key built in, terms agreed, the SDK started). */
internal fun mapBlocked(context: android.content.Context): String? = when {
    !AmapKey.configured(context) -> "没有高德 key：在设置里填写"
    !AMapPrivacy.agreed(context) -> "同意高德地图隐私政策后显示地图"
    Playground.engine?.ensureStarted() == null -> "高德导航启动中…"
    else -> null
}

/** Settings and every manual control, over the right of the screen. */
@Composable
internal fun SettingsSheet(world: World, calls: List<CallRecord>, server: ServerState, flash: Float, store: PlaygroundStore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val mode by HeadUnitModes.mode.collectAsState()
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(remember { MutableInteractionSource() }, null, onClick = onClose)) {
        Column(
            Modifier.align(Alignment.CenterEnd).width(SHEET_WIDTH).fillMaxHeight().background(PlaygroundColors.bg)
                .clickable(remember { MutableInteractionSource() }, null) {}.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("设置与控制", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                PlainIcon(Icons.Filled.Close, onClick = onClose)
            }
            AmapKeyRow()
            // Real navigation: the device's own position and real driving, the simulated car and home taken down.
            SheetSwitch("真实导航模式（用设备的真实定位，不模拟车和家）", mode.realNav) { on ->
                scope.launch(Dispatchers.IO) { HeadUnitModes.set(context, mode.copy(realNav = on)) }
            }
            if (mode.realNav) {
                // Routes driven in the 高德地图 App instead, its stock screen with all it shows (owner 2026-10-22: 方案二).
                SheetSwitch("导航交给高德地图 App（有途经点时在高德里点开始导航）", mode.amapApp) { on ->
                    scope.launch(Dispatchers.IO) { HeadUnitModes.set(context, mode.copy(amapApp = on)) }
                }
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { PlaceSwitch(store) }
            SheetSwitch("导航模拟行驶（不用真开车）", world.cabin.nav.simulated) { on -> scope.launch(Dispatchers.IO) { Playground.navigation?.setSimulated(on) } }
            var demoStart by remember { mutableStateOf(Playground.engine?.demoStart == true) }
            SheetSwitch("车停在深圳市民中心（不用设备的真实位置）", demoStart) { on ->
                Playground.engine?.demoStart = on
                demoStart = on
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("重置车和家", selected = false) { store.reset() }
            }
            SidePanel(PlaygroundScene.CABIN, world, calls, server, flash, store, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun SheetSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = PlaygroundColors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = PlaygroundColors.sage, checkedThumbColor = PlaygroundColors.bg))
    }
}

/** Quick searches on the map's bar, and what each says to 小博. */
private val NEARBY = listOf("充电站" to "找一下附近的充电站", "停车场" to "找一下附近的停车场")

/** The demo's lines, as the head unit offers them at home; tapped, each is said to 小博. */
private val SUGGESTIONS = listOf(
    "我有个朋友坐CA3636来深圳，她是吃素的，你先去机场接她，然后带她去吃一家好点的素食餐厅，你规划下路线",
    "我带外地朋友来深圳，想把这8个地方都打卡一遍：莲花山、世界之窗、欢乐谷、深圳湾公园、平安金融中心、华强北、东门老街、大梅沙，" +
        "先去莲花山，最后去大梅沙看海，你帮我排一条路线",
    "我老婆在深圳湾万象城逛街，老大在南山书城学美术，老二在香蜜湖学芭蕾，老三在科技园学乐高，老四在深圳湾体育中心打羽毛球，" +
        "先去接老大，然后接老四，再接老三，最后接上老二再去接我老婆，晚上要在海上世界给老四过生日，帮我规划一条导航路线",
    "我有点冷，把我的座椅加热打开",
    "打开后备箱",
)

private const val LEFT_SHARE = 0.25f
internal val ACTION_BAR_HEIGHT = 66.dp
private val SHEET_WIDTH = 460.dp
private const val MOVE_MS = 380L
private const val CLOCK_TICK_MS = 1_000L
private const val RENEW_MS = 10_000L
private val ENDED = setOf(NavStatus.IDLE, NavStatus.ARRIVED)
/** How long the map stays up after the navigation last showed something on it. */
private const val CUED_MAP_MS = 60_000L
/** A place found stays in view this long before the map moves on; several found together, longer. */
private const val PLACE_SHOWN_MS = 1_300L
private const val PLACES_SHOWN_MS = 2_200L
private const val GROW_SETTLE_MS = 120L
private const val ROUTE_DRAWN_MS = 900L
/** Driving, how long the whole route stays up with the places found on it before the map follows the car again. */
private const val DRIVING_OVERVIEW_MS = 15_000L
/** The right edge of the map under the route's progress bar, in pixels, while it shows. */
@Composable
internal fun progressSpace(shows: Boolean): Int = if (shows) with(LocalDensity.current) { PROGRESS_SPACE.roundToPx() } else 0

private val PROGRESS_SPACE = 44.dp
/** Between the route's column and the next, which the junction's picture spans. */
internal val JUNCTION_GAP = 10.dp
/** Room above the route's cards for the listening bar. */
internal val BAR_SPACE = 62.dp
private const val CENTRE_RETRY_MS = 2_000L
private const val RECENTRE_M = 2_000.0
private const val WEATHER_RETRY_MS = 15_000L
private const val WEATHER_REFRESH_MS = 20 * 60 * 1000L
