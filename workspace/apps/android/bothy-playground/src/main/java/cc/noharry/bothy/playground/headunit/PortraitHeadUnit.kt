package cc.noharry.bothy.playground.headunit

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.playground.CallRecord
import cc.noharry.bothy.playground.PHONE_SHORT_SIDE_DP
import cc.noharry.bothy.playground.PlaygroundStore
import cc.noharry.bothy.playground.R
import cc.noharry.bothy.playground.ServerState
import cc.noharry.bothy.playground.ServerStatus
import cc.noharry.bothy.playground.cabin.CabinScene
import cc.noharry.bothy.playground.cabin.Seat
import cc.noharry.bothy.playground.cabin.climateLine
import cc.noharry.bothy.playground.home.World
import cc.noharry.bothy.playground.link.Assistant

/** A phone held upright. Wide head-unit columns stay for landscape and tablets. */
internal fun usesPortraitHeadUnit(widthDp: Int, orientation: Int): Boolean =
    widthDp < PHONE_SHORT_SIDE_DP && orientation == Configuration.ORIENTATION_PORTRAIT

internal enum class PortraitTab(@StringRes val label: Int) {
    ROUTE(R.string.headunit_tab_route),
    CABIN(R.string.headunit_tab_cabin),
    CLIMATE(R.string.headunit_tab_climate),
}

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
    var tab by remember { mutableStateOf(initialTab) }
    var sheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val assistant by Assistant.state.collectAsState()
    val nav = world.cabin.nav
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(PlaygroundColors.bg)) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
                when (tab) {
                    PortraitTab.CABIN -> CabinScene(
                        world.cabin,
                        flash,
                        Modifier.fillMaxSize(),
                        onTap = { store.tapCabin(context, it) },
                        onLongPress = { store.holdCabin(context, it) },
                        compact = true,
                        speaking = assistant.listening,
                        gestureTag = "portrait-cabin",
                    )
                    PortraitTab.CLIMATE -> Text(
                        world.cabin.climateLine(),
                        color = PlaygroundColors.text,
                        fontSize = 22.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                    PortraitTab.ROUTE -> Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            nav.destination?.name ?: stringResource(R.string.headunit_where_to),
                            color = PlaygroundColors.text,
                            fontSize = 22.sp,
                        )
                        Text(nav.currentRoad, color = PlaygroundColors.muted, fontSize = 14.sp)
                        if (nav.totalSeconds > 0) {
                            Text(
                                "${nav.remainingSeconds.durationText()} · ${nav.remainingMeters.distanceText()}",
                                color = PlaygroundColors.sage,
                                fontSize = 16.sp,
                            )
                        }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(PlaygroundColors.panel).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(server.portraitLine(), color = PlaygroundColors.muted, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PortraitTab.entries.forEach { entry ->
                        Text(
                            stringResource(entry.label),
                            color = if (tab == entry) PlaygroundColors.sage else PlaygroundColors.muted,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (tab == entry) PlaygroundColors.sage.copy(alpha = 0.16f) else PlaygroundColors.bg)
                                .selectable(selected = tab == entry, role = Role.Tab) { tab = entry }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.playground_scene_home),
                        color = PlaygroundColors.text,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp)).background(PlaygroundColors.bg).clickable(role = Role.Button, onClick = onHome).padding(vertical = 12.dp),
                    )
                    Text(
                        stringResource(R.string.headunit_settings_title),
                        color = PlaygroundColors.text,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp)).background(PlaygroundColors.bg).clickable(role = Role.Button) { sheet = true }.padding(vertical = 12.dp),
                    )
                }
                if (tab == PortraitTab.CABIN) {
                    Seat.entries.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { seat ->
                                Text(
                                    stringResource(seat.labelRes),
                                    color = PlaygroundColors.text,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(PlaygroundColors.bg)
                                        .testTag("seat-${seat.name}")
                                        .clickable { store.tapCabin(context, CabinSpots.seats.getValue(seat)) }
                                        .padding(12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (sheet) SettingsSheet(world, calls, server, flashLevel, store) { sheet = false }
    }
}

@Composable
private fun ServerState.portraitLine(): String = when (status) {
    ServerStatus.STARTING -> stringResource(R.string.playground_server_starting)
    ServerStatus.RUNNING -> stringResource(R.string.playground_server_running, address)
    ServerStatus.FAILED -> stringResource(R.string.playground_server_failed, problem)
}
