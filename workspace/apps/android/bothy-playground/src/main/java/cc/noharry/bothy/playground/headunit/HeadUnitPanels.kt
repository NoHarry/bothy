package cc.noharry.bothy.playground.headunit
import android.content.Context
import androidx.annotation.StringRes
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.playground.Highlight
import cc.noharry.bothy.playground.PlaygroundStore
import cc.noharry.bothy.playground.R
import cc.noharry.bothy.playground.ServerState
import cc.noharry.bothy.playground.ServerStatus
import cc.noharry.bothy.playground.cabin.Cabin
import cc.noharry.bothy.playground.cabin.CabinScene
import cc.noharry.bothy.playground.cabin.Media
import cc.noharry.bothy.playground.cabin.Seat
import cc.noharry.bothy.playground.cabin.SeatComfort
import cc.noharry.bothy.playground.cabin.deg
import cc.noharry.bothy.playground.cabin.played
import cc.noharry.bothy.playground.cabin.seatComfortLog
import cc.noharry.bothy.playground.cabin.seats
import cc.noharry.bothy.playground.home.UserPlace
import cc.noharry.bothy.playground.home.World
import cc.noharry.bothy.playground.link.open
import cc.noharry.bothy.playground.nav.clockText
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
        Text(stringResource(R.string.headunit_range, cabin.rangeKm), color = UnitColors.muted, fontSize = 12.sp)
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
    val context = LocalContext.current
    val resources = LocalResources.current
    Column(modifier) {
        Text(cabin.gear(), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DoorPills(listOf(Seat.DRIVER, Seat.REAR_LEFT), cabin, store)
            CabinScene(
                cabin,
                flash,
                Modifier.weight(1f).fillMaxHeight(),
                { store.tapCabin(context, it) },
                { store.holdCabin(context, it) },
                compact = true,
                speaking = listening,
            )
            DoorPills(listOf(Seat.PASSENGER, Seat.REAR_RIGHT), cabin, store)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            val trunk = stringResource(R.string.headunit_trunk)
            RoundToggle(Icons.Filled.Sensors, trunk, cabin.trunkOpen) { store.toggleTrunk(context) }
            val lock = stringResource(if (cabin.doorsLocked) R.string.headunit_locked else R.string.headunit_unlocked)
            RoundToggle(if (cabin.doorsLocked) Icons.Filled.Lock else Icons.Filled.LockOpen, lock, !cabin.doorsLocked) {
                val line = if (cabin.doorsLocked) R.string.cabin_log_doors_unlocked else R.string.cabin_log_doors_locked
                val summary = resources.getString(line)
                store.cabin(summary, Highlight.DOOR) { it.copy(doorsLocked = !it.doorsLocked) }
            }
            val anyOpen = cabin.windows.values.any { it > 0 }
            val windows =
                stringResource(if (anyOpen) R.string.headunit_close_windows else R.string.headunit_open_windows)
            RoundToggle(Icons.Filled.Window, windows, anyOpen) {
                val open = if (anyOpen) 0 else 100
                val line = if (open == 0) R.string.headunit_log_windows_all_closed else R.string.headunit_log_windows_all_opened
                store.cabin(resources.getString(line), *Seat.entries.map(Highlight::window).toTypedArray()) {
                    it.copy(windows = Seat.entries.associateWith { open })
                }
            }
        }
        MediaCard(cabin.media, store)
    }
}

/** Two doors on one side, front above rear, level with them; lit while the door is open. */
@Composable
private fun DoorPills(seats: List<Seat>, cabin: Cabin, store: PlaygroundStore) {
    val context = LocalContext.current
    Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.Center) {
        Spacer(Modifier.weight(0.3f))
        UnitPill(stringResource(seats[0].pillLabel()), seats[0] in cabin.doorsOpen) { store.toggleDoor(context, seats[0]) }
        Spacer(Modifier.weight(0.25f))
        UnitPill(stringResource(seats[1].pillLabel()), seats[1] in cabin.doorsOpen) { store.toggleDoor(context, seats[1]) }
        Spacer(Modifier.weight(0.45f))
    }
}

/** A door's pill beside the car picture: short, so English leaves the picture its room. */
@StringRes
private fun Seat.pillLabel(): Int = when (this) {
    Seat.DRIVER -> R.string.headunit_pill_driver
    Seat.PASSENGER -> R.string.headunit_pill_passenger
    Seat.REAR_LEFT -> R.string.headunit_pill_rear_left
    Seat.REAR_RIGHT -> R.string.headunit_pill_rear_right
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
    val context = LocalContext.current
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
            val title = media.title.ifEmpty { stringResource(R.string.headunit_nothing_playing) }
            val under = media.artist.ifEmpty { stringResource(R.string.headunit_volume, media.volume) }
            Text(title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(under, color = UnitColors.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PlainIcon(Icons.Filled.SkipPrevious) { store.playMedia(context, "previous") }
        PlainIcon(if (media.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow) {
            store.playMedia(context, if (media.playing) "pause" else "resume")
        }
        PlainIcon(Icons.Filled.SkipNext) { store.playMedia(context, "next") }
    }
}

internal fun PlaygroundStore.playMedia(context: Context, action: String) {
    val media = read().cabin.media.played(action, null)
    val summary = if (media.playing) {
        context.getString(R.string.cabin_log_playing, media.title)
    } else {
        context.getString(R.string.cabin_log_music_paused)
    }
    cabin(summary, Highlight.SCREEN) { it.copy(media = media) }
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
    val context = LocalContext.current
    val resources = LocalResources.current
    val climate = cabin.climate
    Row(
        modifier.clip(RoundedCornerShape(18.dp)).background(UnitColors.panel).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        fun temp(to: Double) {
            store.cabin(resources.getString(R.string.cabin_log_climate_temp, to.deg()), Highlight.CLIMATE) {
                it.copy(climate = it.climate.copy(on = true, driverTemp = to, passengerTemp = to))
            }
        }
        BarText("‹", 22) { temp((climate.driverTemp - 0.5).coerceAtLeast(16.0)) }
        Text(if (climate.on) "%.1f".format(java.util.Locale.ROOT, climate.driverTemp) else "OFF", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        BarText("›", 22) { temp((climate.driverTemp + 0.5).coerceAtMost(30.0)) }
        BarToggle("A/C", climate.on) {
            val line = if (climate.on) R.string.cabin_log_climate_off else R.string.cabin_log_climate_on
            store.cabin(resources.getString(line), Highlight.CLIMATE) { it.copy(climate = it.climate.copy(on = !it.climate.on)) }
        }
        val fan = stringResource(R.string.headunit_fan, climate.fan)
        BarToggle(fan, false) {
            store.cabin(resources.getString(R.string.cabin_log_fan, climate.fan % 7 + 1), Highlight.CLIMATE) {
                it.copy(climate = it.climate.copy(on = true, fan = it.climate.fan % 7 + 1))
            }
        }
        SeatHeat(Seat.DRIVER, cabin, store)
        SeatHeat(Seat.PASSENGER, cabin, store)
        val ambient = stringResource(R.string.headunit_ambient)
        BarToggle(ambient, cabin.ambient.on) {
            val line = if (cabin.ambient.on) R.string.cabin_log_ambient_off else R.string.cabin_log_ambient_on
            store.cabin(resources.getString(line), Highlight.AMBIENT) { it.copy(ambient = it.ambient.copy(on = !it.ambient.on)) }
        }
    }
}

@Composable
private fun SeatHeat(seat: Seat, cabin: Cabin, store: PlaygroundStore) {
    val context = LocalContext.current
    val heat = cabin.seat(seat).heat
    val name = stringResource(seat.labelRes)
    val label = if (heat > 0) {
        stringResource(R.string.headunit_seat_heat_level, name, heat)
    } else {
        stringResource(R.string.headunit_seat_heat, name)
    }
    BarToggle(label, heat > 0) {
        val next = SeatComfort(heat = (heat + 1) % 4)
        store.cabin(context.seatComfortLog(seat, next), Highlight.seat(seat)) {
            it.copy(seats = it.seats + (seat to next))
        }
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

/**
 * The right end of the status bar, over the map: the mode's switch, the way to the home ([onHome]), where the user is, the
 * servers, the weather, the time, settings.
 */
@Composable
internal fun StatusCluster(world: World, server: ServerState, now: Long, onMore: () -> Unit, onHome: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(UnitColors.card).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModeSwitch()
        SceneSwitch(onHome)
        val where =
            if (world.userAt == UserPlace.HOME) R.string.headunit_user_at_home else R.string.headunit_user_in_cabin
        StatusText(stringResource(where))
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
