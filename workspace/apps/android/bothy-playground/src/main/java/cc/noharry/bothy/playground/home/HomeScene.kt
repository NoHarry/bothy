package cc.noharry.bothy.playground.home
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.playground.Highlight
import cc.noharry.bothy.playground.R
import cc.noharry.bothy.playground.cabin.ClimateMode
import cc.noharry.bothy.playground.cabin.deg
import cc.noharry.bothy.playground.headunit.ChipAlign
import cc.noharry.bothy.playground.headunit.FittedPicture
import cc.noharry.bothy.playground.headunit.Flash
import cc.noharry.bothy.playground.headunit.HomeSpots
import cc.noharry.bothy.playground.headunit.PlaygroundColors
import cc.noharry.bothy.playground.headunit.drawChip
import cc.noharry.bothy.playground.headunit.drawFlashRect
import cc.noharry.bothy.playground.headunit.drawFlashRing
import cc.noharry.bothy.playground.headunit.drawPerson
import cc.noharry.bothy.playground.headunit.easedForward
import cc.noharry.bothy.playground.headunit.home
import cc.noharry.bothy.playground.headunit.on
import cc.noharry.bothy.playground.headunit.passengerImages
import kotlin.math.floor
import kotlin.math.roundToInt

private class HomeMotion(val light: Map<Room, State<Float>>, val curtainShut: Map<Room, State<Float>>, val vacuum: State<Float>)

@Composable
private fun homeMotion(home: Home): HomeMotion = HomeMotion(
    light = Room.entries.associateWith { room ->
        val light = home.room(room).light
        animateFloatAsState(if (light.on) light.brightness / 100f else 0f, tween(LIGHT_MS), label = "light")
    },
    curtainShut = Room.entries.associateWith { animateFloatAsState(1f - home.room(it).curtainOpen / 100f, tween(CURTAIN_MS), label = "curtain") },
    vacuum = easedForward(home.vacuum.progress.toFloat(), home.vacuum.status, home.vacuum.room),
)

/**
 * The flat from above at dusk: a room is dark until its light is on, curtains slide along their rails, an air
 * conditioner that runs tints its room, and the vacuum works its way across the floor.
 */
@Composable
internal fun HomeScene(home: Home, flash: Flash, modifier: Modifier = Modifier, onTap: (Offset) -> Unit = {}, onLongPress: (Offset) -> Unit = {}) {
    val measurer = rememberTextMeasurer()
    val motion = homeMotion(home)
    val robot = ImageBitmap.imageResource(R.drawable.home_vacuum)
    val people = passengerImages()
    FittedPicture(R.drawable.scene_home, HomeSpots.ASPECT, modifier, onTap, onLongPress) {
        Room.entries.forEach { drawRoom(it, home.room(it), motion.light.getValue(it).value, flash.of(Highlight.room(it))) }
        Room.entries.forEach { drawCurtain(it, motion.curtainShut.getValue(it).value, flash.of(Highlight.curtain(it))) }
        drawVacuum(home.vacuum, motion.vacuum.value, robot, flash.of(Highlight.VACUUM))
        Room.entries.filter { home.room(it).presence }.forEach {
            drawPerson(people[it.ordinal], HomeSpots.people.getValue(it).on(size), HomeSpots.PERSON_SIZE * size.width)
        }
        Room.entries.forEach { drawRoomChips(it, home.room(it), measurer) }
        drawDoor(home.doorLocked, flash.of(Highlight.DOOR), measurer)
        home.scene?.let { drawChip(measurer, "场景：$it", Offset(size.width / 2, size.height * 0.44f), PlaygroundColors.glow) }
    }
}

private fun DrawScope.drawRoom(room: Room, state: RoomState, light: Float, flash: Float) {
    val rect = HomeSpots.rooms.getValue(room).on(size)
    drawRect(PlaygroundColors.night.copy(alpha = DARK_ALPHA * (1f - light)), rect.topLeft, rect.size)
    if (light > 0.01f) {
        val tone = Color(state.light.tone.color)
        val radius = rect.width * 0.7f
        drawRect(Brush.radialGradient(listOf(tone.copy(alpha = 0.4f * light), Color.Transparent), rect.center, radius), rect.topLeft, rect.size)
    }
    if (state.climate.on && room.hasClimate) {
        val tint = if (state.climate.mode == ClimateMode.HEAT) PlaygroundColors.heat else PlaygroundColors.cool
        val band = Rect(rect.left, rect.top, rect.right, rect.top + rect.height * 0.45f)
        drawRect(Brush.verticalGradient(listOf(tint.copy(alpha = 0.22f), Color.Transparent), band.top, band.bottom), band.topLeft, band.size)
    }
    drawFlashRect(rect, flash)
}

private fun DrawScope.drawCurtain(room: Room, shut: Float, flash: Float) {
    val (a, b) = HomeSpots.curtains.getValue(room)
    val from = a.on(size)
    val to = b.on(size)
    drawLine(Color(0xFF2E3430), from, to, 3.dp.toPx(), StrokeCap.Round)
    val half = (to.x - from.x) / 2 * shut
    val thick = 14.dp.toPx()
    if (half > 1f) {
        listOf(from.x to from.x + half, to.x - half to to.x).forEach { (left, right) ->
            drawRect(PlaygroundColors.curtain, Offset(left, from.y - thick / 2), Size(right - left, thick))
            var x = left + PLEAT_PX
            while (x < right) {
                drawLine(Color.Black.copy(alpha = 0.22f), Offset(x, from.y - thick / 2), Offset(x, from.y + thick / 2), 1.dp.toPx())
                x += PLEAT_PX
            }
        }
    }
    if (flash > 0f) drawLine(PlaygroundColors.glow.copy(alpha = flash), from, to, 14.dp.toPx(), StrokeCap.Round)
}

private fun DrawScope.drawRoomChips(room: Room, state: RoomState, measurer: TextMeasurer) {
    val rect = HomeSpots.rooms.getValue(room).on(size)
    val x = rect.left + 12.dp.toPx()
    val y = rect.top + rect.height * 0.16f
    drawChip(measurer, "${room.label} ${state.tempC.deg()}", Offset(x, y), PlaygroundColors.text, ChipAlign.START)
    if (state.climate.on && room.hasClimate) {
        drawChip(measurer, "空调 ${state.climate.targetTemp.deg()} ${state.climate.mode.label}", Offset(x, y + 24.dp.toPx()), PlaygroundColors.cool, ChipAlign.START)
    }
}

private fun DrawScope.drawDoor(locked: Boolean, flash: Float, measurer: TextMeasurer) {
    val rect = HomeSpots.door.on(size)
    drawChip(measurer, if (locked) "门已上锁" else "门锁开着", rect.center, if (locked) PlaygroundColors.sage else PlaygroundColors.ember)
    drawFlashRing(rect.center, rect.width * 0.7f, flash)
}

private fun DrawScope.drawVacuum(vacuum: Vacuum, progress: Float, robot: ImageBitmap, flash: Float) {
    val at = vacuumSpot(vacuum, progress).on(size)
    val side = HomeSpots.VACUUM_SIZE * size.width
    drawImage(
        robot,
        dstOffset = IntOffset((at.x - side / 2).roundToInt(), (at.y - side / 2).roundToInt()),
        dstSize = IntSize(side.roundToInt(), side.roundToInt()),
    )
    drawFlashRing(at, side * 0.7f, flash)
}

/**
 * Where the vacuum is, as a fraction of the picture: on its dock, sweeping its room in rows ([progress] across the
 * room), or on its way back from the room's middle to the dock.
 */
internal fun vacuumSpot(vacuum: Vacuum, progress: Float): Offset {
    val room = vacuum.room?.let { HomeSpots.rooms.getValue(it) } ?: return HomeSpots.dock
    return when (vacuum.status) {
        VacuumStatus.DOCKED -> HomeSpots.dock
        VacuumStatus.RETURNING -> lerp(room.center, HomeSpots.dock, progress.coerceIn(0f, 1f))
        VacuumStatus.CLEANING, VacuumStatus.PAUSED -> {
            val area = Rect(room.left + room.width * INSET, room.top + room.height * INSET, room.right - room.width * INSET, room.bottom - room.height * INSET)
            val t = progress.coerceIn(0f, 0.999f) * ROWS
            val row = floor(t).toInt()
            val along = t - row
            val x = if (row % 2 == 0) area.left + area.width * along else area.right - area.width * along
            Offset(x, area.top + area.height * (row + 0.5f) / ROWS)
        }
    }
}

private const val DARK_ALPHA = 0.68f
private const val LIGHT_MS = 700
private const val CURTAIN_MS = 1800
private const val PLEAT_PX = 9f
private const val ROWS = 3
private const val INSET = 0.18f
