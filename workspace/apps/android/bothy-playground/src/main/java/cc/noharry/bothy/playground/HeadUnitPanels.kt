package cc.noharry.bothy.playground

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Window
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Gear, then range and charge, along the top of the car's side. */
@Composable
internal fun GearStrip(cabin: Cabin) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Row {
            listOf("P", "R", "N", "D").forEachIndexed { i, gear ->
                if (i > 0) Text("/", color = UnitColors.muted.copy(alpha = 0.5f), fontSize = 12.sp)
                Text(gear, color = if (gear == cabin.gear()) Color.White else UnitColors.muted.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text("续航 ${cabin.rangeKm}km", color = UnitColors.muted, fontSize = 12.sp)
        Box(Modifier.width(34.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(UnitColors.raised)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((cabin.drive.batteryPct / 100).toFloat()).background(UnitColors.teal))
        }
        Text("${cabin.drive.batteryPct.roundToInt()}%", color = UnitColors.muted, fontSize = 12.sp)
    }
}

internal fun Cabin.gear(): String = if (nav.speedKmh > 0 || drive.speedKmh > 0.5) "D" else "P"

/**
 * The car from above with what moves on it (doors, windows, passengers, the speaker's rings), the four doors as buttons
 * beside it, and the trunk, lock and windows under it.
 */
@Composable
internal fun CarColumn(cabin: Cabin, flash: Flash, store: PlaygroundStore, listening: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(cabin.gear(), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DoorPills(listOf(Seat.DRIVER, Seat.REAR_LEFT), cabin, store)
            CabinScene(cabin, flash, Modifier.weight(1f).fillMaxHeight(), store::tapCabin, store::holdCabin, compact = true, speaking = listening)
            DoorPills(listOf(Seat.PASSENGER, Seat.REAR_RIGHT), cabin, store)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundToggle(Icons.Filled.Sensors, "后备箱", cabin.trunkOpen) { store.toggleTrunk() }
            RoundToggle(if (cabin.doorsLocked) Icons.Filled.Lock else Icons.Filled.LockOpen, if (cabin.doorsLocked) "已锁" else "未锁", !cabin.doorsLocked) {
                store.cabin(if (cabin.doorsLocked) "车门解锁" else "车门上锁", Highlight.DOOR) { it.copy(doorsLocked = !it.doorsLocked) }
            }
            val anyOpen = cabin.windows.values.any { it > 0 }
            RoundToggle(Icons.Filled.Window, if (anyOpen) "关窗" else "开窗", anyOpen) {
                val open = if (anyOpen) 0 else 100
                store.cabin(if (open == 0) "车窗全部关上" else "车窗全部打开", *Seat.entries.map(Highlight::window).toTypedArray()) { it.copy(windows = Seat.entries.associateWith { open }) }
            }
        }
        MediaCard(cabin.media, store)
    }
}

/** Two doors on one side, front above rear, level with them; lit while the door is open. */
@Composable
private fun DoorPills(seats: List<Seat>, cabin: Cabin, store: PlaygroundStore) {
    Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.Center) {
        Spacer(Modifier.weight(0.3f))
        UnitPill(seats[0].label, seats[0] in cabin.doorsOpen) { store.toggleDoor(seats[0]) }
        Spacer(Modifier.weight(0.25f))
        UnitPill(seats[1].label, seats[1] in cabin.doorsOpen) { store.toggleDoor(seats[1]) }
        Spacer(Modifier.weight(0.45f))
    }
}

@Composable
internal fun UnitPill(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (on) UnitColors.screen else Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) UnitColors.teal else UnitColors.raised).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun RoundToggle(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = label, tint = if (on) UnitColors.teal else Color.White, modifier = Modifier.size(22.dp))
        Text(label, color = UnitColors.muted, fontSize = 10.sp)
    }
}

/** What is playing, with previous, play or pause, next. */
@Composable
private fun MediaCard(media: Media, store: PlaygroundStore) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(UnitColors.panel).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(PlaygroundColors.sage, UnitColors.teal), Offset.Zero, Offset(120f, 120f))),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp)) }
        Column(Modifier.weight(1f)) {
            Text(media.title.ifEmpty { "没有在放" }, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(media.artist.ifEmpty { "音量 ${media.volume}" }, color = UnitColors.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PlainIcon(Icons.Filled.SkipPrevious) { store.playMedia("previous") }
        PlainIcon(if (media.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow) { store.playMedia(if (media.playing) "pause" else "resume") }
        PlainIcon(Icons.Filled.SkipNext) { store.playMedia("next") }
    }
}

internal fun PlaygroundStore.playMedia(action: String) {
    val media = read().cabin.media.played(action, null)
    cabin(if (media.playing) "播放《${media.title}》" else "音乐暂停", Highlight.SCREEN) { it.copy(media = media) }
}

@Composable
internal fun PlainIcon(icon: ImageVector, tint: Color = Color.White, size: Int = 26, onClick: () -> Unit) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(4.dp).size(size.dp))
}

/** The head unit's dock: the home, the car's controls, the map, settings. */
@Composable
internal fun Dock(onHome: () -> Unit, onControls: () -> Unit, onMap: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(18.dp)).background(UnitColors.panel).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        PlainIcon(Icons.Filled.Home, onClick = onHome)
        PlainIcon(Icons.Filled.Navigation, UnitColors.teal, onClick = onMap)
        PlainIcon(Icons.Filled.DirectionsCar, onClick = onControls)
        PlainIcon(Icons.Filled.Settings, onClick = onControls)
    }
}

/** The climate strip under the car and the assistant: temperature, A/C, fan, seat heating, ambient light, volume. */
@Composable
internal fun ClimateBar(cabin: Cabin, store: PlaygroundStore, modifier: Modifier = Modifier) {
    val climate = cabin.climate
    Row(
        modifier.clip(RoundedCornerShape(18.dp)).background(UnitColors.panel).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        fun temp(to: Double) = store.cabin("空调设到 ${to.deg()}", Highlight.CLIMATE) { it.copy(climate = it.climate.copy(on = true, driverTemp = to, passengerTemp = to)) }
        BarText("‹", 22) { temp((climate.driverTemp - 0.5).coerceAtLeast(16.0)) }
        Text(if (climate.on) "%.1f".format(java.util.Locale.ROOT, climate.driverTemp) else "OFF", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        BarText("›", 22) { temp((climate.driverTemp + 0.5).coerceAtMost(30.0)) }
        BarToggle("A/C", climate.on) { store.cabin(if (climate.on) "空调关闭" else "空调打开", Highlight.CLIMATE) { it.copy(climate = it.climate.copy(on = !it.climate.on)) } }
        BarToggle("风 ${climate.fan}", false) { store.cabin("风量 ${climate.fan % 7 + 1}", Highlight.CLIMATE) { it.copy(climate = it.climate.copy(on = true, fan = it.climate.fan % 7 + 1)) } }
        SeatHeat(Seat.DRIVER, cabin, store)
        SeatHeat(Seat.PASSENGER, cabin, store)
        BarToggle("氛围灯", cabin.ambient.on) { store.cabin(if (cabin.ambient.on) "氛围灯关闭" else "氛围灯打开", Highlight.AMBIENT) { it.copy(ambient = it.ambient.copy(on = !it.ambient.on)) } }
    }
}

@Composable
private fun SeatHeat(seat: Seat, cabin: Cabin, store: PlaygroundStore) {
    val heat = cabin.seat(seat).heat
    BarToggle("${seat.label}${if (heat > 0) "热$heat" else "加热"}", heat > 0) {
        val next = SeatComfort(heat = (heat + 1) % 4)
        store.cabin("${seat.label}座椅${next.comfortLine()}", Highlight.seat(seat)) { it.copy(seats = it.seats + (seat to next)) }
    }
}

@Composable
private fun BarText(text: String, size: Int, onClick: () -> Unit) {
    Text(text, color = UnitColors.muted, fontSize = size.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp))
}

@Composable
private fun BarToggle(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (on) UnitColors.teal else Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
    )
}

/** The right end of the status bar, over the map: the mode's switch, where the user is, the servers, the weather, the time, settings. */
@Composable
internal fun StatusCluster(world: World, server: ServerState, now: Long, onMore: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(UnitColors.card).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModeSwitch()
        StatusText("你在${world.userAt.label}")
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (server.status == ServerStatus.RUNNING) PlaygroundColors.sage else PlaygroundColors.ember))
        StatusText("MCP")
        StatusText(world.cabin.drive.outsideTempC.deg())
        StatusText(clockText(now, now))
        PlainIcon(Icons.Filled.Settings, size = 20, onClick = onMore)
    }
}

@Composable
private fun RowScope.StatusText(text: String) {
    Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

@Composable
internal fun ScreenFill(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(UnitColors.screen)) { content() }
}
