package cc.noharry.bothy.playground.headunit

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.playground.CallRecord
import cc.noharry.bothy.playground.PHONE_SHORT_SIDE_DP
import cc.noharry.bothy.playground.Playground
import cc.noharry.bothy.playground.PlaygroundStore
import cc.noharry.bothy.playground.R
import cc.noharry.bothy.playground.ServerState
import cc.noharry.bothy.playground.ServerStatus
import cc.noharry.bothy.playground.cabin.Cabin
import cc.noharry.bothy.playground.cabin.CabinScene
import cc.noharry.bothy.playground.cabin.deg
import cc.noharry.bothy.playground.home.World
import cc.noharry.bothy.playground.link.Assistant
import cc.noharry.bothy.playground.nav.NavState
import cc.noharry.bothy.playground.nav.NaviMapHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A phone held upright. Wide head-unit columns stay for landscape and tablets. */
internal fun usesPortraitHeadUnit(widthDp: Int, orientation: Int): Boolean =
    widthDp < PHONE_SHORT_SIDE_DP && orientation == Configuration.ORIENTATION_PORTRAIT

internal enum class PortraitTab(@StringRes val label: Int) {
    ROUTE(R.string.headunit_tab_route),
    CABIN(R.string.headunit_tab_cabin),
    CLIMATE(R.string.headunit_tab_climate),
}

/**
 * The head unit on a phone held upright, as the phone design draws it: the map over the whole screen (the cabin in its
 * place on the cabin and climate tabs), a menu at the top left with the home, the settings and the servers, the
 * outside temperature at the top right, and at the bottom a panel with the tabs and what the open one offers. A route
 * the assistant plans brings the map back.
 */
@Composable
internal fun PortraitHeadUnit(
    world: World,
    calls: List<CallRecord>,
    server: ServerState,
    flash: Flash,
    flashLevel: Float,
    store: PlaygroundStore,
    onHome: () -> Unit,
    initialTab: PortraitTab = PortraitTab.ROUTE,
) {
    var tab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }
    var sheet by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().background(PlaygroundColors.bg)) {
        var panelHeight by remember { mutableStateOf(0.dp) }
        // The panel's rounded top lies over the picture's foot.
        val pictureHeight = (maxHeight - panelHeight + PANEL_OVERLAP).coerceAtLeast(0.dp)
        PortraitMap(world.cabin.nav, MapBox(0.dp, 0.dp, maxWidth, pictureHeight), showing = tab == PortraitTab.ROUTE) { tab = PortraitTab.ROUTE }
        if (tab != PortraitTab.ROUTE) PortraitCabin(world, flash, store, Modifier.offset(0.dp, 0.dp).size(maxWidth, pictureHeight))
        PortraitTopRow(world.cabin, server, onHome, { sheet = true }, Modifier.align(Alignment.TopCenter))
        PortraitPanel(
            tab, { tab = it }, world, store,
            Modifier.align(Alignment.BottomCenter).onSizeChanged { panelHeight = with(density) { it.height.toDp() } },
        )
        if (sheet) SettingsSheet(world, calls, server, flashLevel, store) { sheet = false }
    }
}

/**
 * The one map of the upright head unit, kept under the cabin on the other tabs so it is not made again. Until the map
 * can show, why not is asked again every second: nothing else here redraws when the navigation service comes up.
 */
@Composable
private fun PortraitMap(nav: NavState, box: MapBox, showing: Boolean, onCue: () -> Unit) {
    val context = LocalContext.current
    var blocked by remember { mutableStateOf(mapBlocked(context)) }
    LaunchedEffect(blocked) {
        while (blocked != null) {
            delay(MAP_RECHECK_MS)
            blocked = mapBlocked(context)
        }
    }
    val map = remember { NaviMapHandle() }
    var cuedAt by remember { mutableLongStateOf(0L) }
    RestTheMap(map, showing, nav.active, cuedAt, box)
    PlayMapCues(map, showing) {
        cuedAt = System.currentTimeMillis()
        onCue()
    }
    FollowTheCar(map, nav, cuedAt)
    MapLayer(blocked, map, box, corner = 0.dp)
}

@Composable
private fun PortraitCabin(world: World, flash: Flash, store: PlaygroundStore, modifier: Modifier) {
    val context = LocalContext.current
    val assistant by Assistant.state.collectAsState()
    Box(modifier.background(PlaygroundColors.bg).statusBarsPadding().padding(horizontal = 12.dp, vertical = 64.dp)) {
        CabinScene(
            world.cabin,
            flash,
            Modifier.fillMaxSize(),
            onTap = { store.tapCabin(context, it) },
            onLongPress = { store.holdCabin(context, it) },
            compact = true,
            speaking = assistant.listening,
            gestureTag = "portrait-cabin",
        )
    }
}

/** Over the picture's top: the menu (home, settings, the servers) and the outside temperature. */
@Composable
private fun PortraitTopRow(cabin: Cabin, server: ServerState, onHome: () -> Unit, onSettings: () -> Unit, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    val menuLabel = stringResource(R.string.headunit_menu)
    Row(modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Box(
                Modifier
                    .size(TOP_BUTTON)
                    .clip(RoundedCornerShape(16.dp))
                    .background(UnitColors.card)
                    .clickable(role = Role.Button) { menu = true }
                    .semantics { contentDescription = menuLabel },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Menu, contentDescription = null, tint = PlaygroundColors.text) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.playground_scene_home)) }, onClick = { menu = false; onHome() })
                DropdownMenuItem(text = { Text(stringResource(R.string.headunit_settings_title)) }, onClick = { menu = false; onSettings() })
                Text(server.portraitLine(), color = PlaygroundColors.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        Box(Modifier.weight(1f))
        Text(
            cabin.drive.outsideTempC.deg(),
            color = PlaygroundColors.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(UnitColors.card).padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/**
 * The bottom panel: the tabs, then the route (with the way to start it), nothing more for the cabin (its picture is
 * tapped), or the climate.
 */
@Composable
private fun PortraitPanel(tab: PortraitTab, onTab: (PortraitTab) -> Unit, world: World, store: PlaygroundStore, modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(PlaygroundColors.panel)
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PortraitTabs(tab, onTab)
        // The same height under each tab, so the tabs stay where the finger left them.
        Column(Modifier.heightIn(min = PANEL_CONTENT_MIN_HEIGHT), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (tab) {
                PortraitTab.ROUTE -> RouteSummary(world.cabin.nav)
                PortraitTab.CABIN -> Unit
                PortraitTab.CLIMATE -> {
                    Text(world.cabin.climateSummary(), color = PlaygroundColors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    ClimatePanel(world.cabin, store, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun PortraitTabs(tab: PortraitTab, onTab: (PortraitTab) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(PlaygroundColors.bg).padding(4.dp)) {
        PortraitTab.entries.forEach { entry ->
            Text(
                stringResource(entry.label),
                color = if (tab == entry) PlaygroundColors.sage else PlaygroundColors.muted,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (tab == entry) PlaygroundColors.sage.copy(alpha = 0.16f) else PlaygroundColors.bg)
                    .selectable(selected = tab == entry, role = Role.Tab) { onTab(entry) }
                    .padding(vertical = 13.dp),
            )
        }
    }
}

/** Where the route goes and how long it takes, with the way to start a planned one; or where to, before any. */
@Composable
private fun RouteSummary(nav: NavState) {
    val scope = rememberCoroutineScope()
    val now = System.currentTimeMillis()
    val title = nav.destination?.name?.takeIf { nav.active } ?: stringResource(R.string.headunit_where_to)
    val line = when {
        nav.previewing -> stringResource(R.string.headunit_route_summary, nav.totalMeters.distanceText(), nav.totalSeconds.durationText(), clockLabel(now + nav.totalSeconds * 1000L, now))
        nav.active -> "${nav.remainingSeconds.durationText()} · ${nav.remainingMeters.distanceText()} · ${etaText(now + nav.remainingSeconds * 1000L, now)}"
        else -> stringResource(R.string.headunit_nav_hint)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = PlaygroundColors.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(line, color = PlaygroundColors.muted, fontSize = 14.sp)
    }
    if (!nav.previewing) return
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(PlaygroundColors.sage)
            .clickable(role = Role.Button) { scope.launch(Dispatchers.IO) { Playground.navigation?.begin() } }
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Navigation, contentDescription = null, tint = PlaygroundColors.bg, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.headunit_start_navigation), color = PlaygroundColors.bg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
    }
}

/** The climate as the screen words it; [cc.noharry.bothy.playground.cabin.climateLine] is what the model reads. */
@Composable
private fun Cabin.climateSummary(): String =
    if (climate.on) {
        stringResource(R.string.cabin_chip_climate, climate.driverTemp.deg(), stringResource(climate.mode.labelRes))
    } else {
        stringResource(R.string.cabin_chip_climate_off)
    }

@Composable
private fun ServerState.portraitLine(): String = when (status) {
    ServerStatus.STARTING -> stringResource(R.string.playground_server_starting)
    ServerStatus.RUNNING -> stringResource(R.string.playground_server_running, address)
    ServerStatus.FAILED -> stringResource(R.string.playground_server_failed, problem)
}

private val TOP_BUTTON = 48.dp
private const val MAP_RECHECK_MS = 1_000L
private val PANEL_CONTENT_MIN_HEIGHT = 168.dp
private val PANEL_OVERLAP: Dp = 28.dp
