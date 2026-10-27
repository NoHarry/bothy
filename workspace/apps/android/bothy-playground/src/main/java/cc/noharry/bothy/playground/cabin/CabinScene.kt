package cc.noharry.bothy.playground.cabin
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.playground.Highlight
import cc.noharry.bothy.playground.R
import cc.noharry.bothy.playground.headunit.CabinSpots
import cc.noharry.bothy.playground.headunit.ChipAlign
import cc.noharry.bothy.playground.headunit.FittedPicture
import cc.noharry.bothy.playground.headunit.Flash
import cc.noharry.bothy.playground.headunit.PlaygroundColors
import cc.noharry.bothy.playground.headunit.cabin
import cc.noharry.bothy.playground.headunit.drawChip
import cc.noharry.bothy.playground.headunit.drawFlashRect
import cc.noharry.bothy.playground.headunit.drawFlashRing
import cc.noharry.bothy.playground.headunit.drawPerson
import cc.noharry.bothy.playground.headunit.on
import cc.noharry.bothy.playground.headunit.passengerImages
import cc.noharry.bothy.playground.link.open
import cc.noharry.bothy.playground.nav.km
import kotlin.math.roundToInt

/** The values the cabin overlay eases between, so a seat warms up and a window slides rather than jumping. */
private class CabinMotion(
    val heat: Map<Seat, State<Float>>,
    val vent: Map<Seat, State<Float>>,
    val windows: Map<Seat, State<Float>>,
    val ambient: State<Float>,
    val doors: Map<Seat, State<Float>>,
    val trunk: State<Float>,
    val screen: State<Float>,
)

@Composable
private fun cabinMotion(cabin: Cabin): CabinMotion = CabinMotion(
    heat = Seat.entries.associateWith { animateFloatAsState(cabin.seat(it).heat.toFloat(), tween(MOTION_MS), label = "heat") },
    vent = Seat.entries.associateWith { animateFloatAsState(cabin.seat(it).ventilation.toFloat(), tween(MOTION_MS), label = "vent") },
    windows = Seat.entries.associateWith { animateFloatAsState(cabin.window(it) / 100f, tween(WINDOW_MS), label = "window") },
    ambient = animateFloatAsState(if (cabin.ambient.on) cabin.ambient.brightness / 100f else 0f, tween(MOTION_MS), label = "ambient"),
    doors = Seat.entries.associateWith { doorSwing(it in cabin.doorsOpen) },
    trunk = tailgateLift(cabin.trunkOpen),
    screen = animateFloatAsState(cabin.screenBrightness / 100f, tween(MOTION_MS), label = "screen"),
)

/**
 * The cabin from above, with its seats, passengers, doors, windows, tailgate, ambient light, screen and climate drawn as
 * they are now; the one speaking sends out rings. A tap seats or unseats someone, or opens or closes a window or the
 * trunk; a long press on a seat makes its passenger the speaker, on a window opens or closes its door.
 */
@Composable
internal fun CabinScene(
    cabin: Cabin,
    flash: Flash,
    modifier: Modifier = Modifier,
    onTap: (Offset) -> Unit = {},
    onLongPress: (Offset) -> Unit = {},
    /** Small, beside other panels (the head unit): smaller labels, no screen, console or status labels. */
    compact: Boolean = false,
    /** Whether the speaker's seat shows that they speak: always here, only while the assistant listens on the head unit. */
    speaking: Boolean = true,
) {
    val chip = if (compact) COMPACT_CHIP else FULL_CHIP
    val measurer = rememberTextMeasurer()
    val motion = cabinMotion(cabin)
    val people = passengerImages()
    val wave = rememberInfiniteTransition(label = "speaker").animateFloat(0f, 1f, infiniteRepeatable(tween(WAVE_MS, easing = LinearEasing)), label = "wave")
    val doorImages = CabinParts.images.mapValues { (_, res) -> ImageBitmap.imageResource(res) }
    val tailgate = ImageBitmap.imageResource(R.drawable.cabin_part_lid)
    FittedPicture(R.drawable.scene_cabin_base, CabinSpots.ASPECT, modifier, onTap, onLongPress) {
        drawClimateWash(cabin, flash.of(Highlight.CLIMATE))
        drawTailgate(tailgate, motion.trunk.value, flash.of(Highlight.TRUNK))
        drawAmbient(cabin.ambient, motion.ambient.value, flash.of(Highlight.AMBIENT))
        Seat.entries.forEach { drawSeat(it, cabin.seat(it), motion, flash.of(Highlight.seat(it)), measurer) }
        Seat.entries.filter { it in cabin.occupants }.forEach { drawPerson(people[it.ordinal], CabinSpots.seats.getValue(it).on(size), CabinSpots.SEAT_RADIUS * size.width * 2.1f) }
        cabin.speaker?.takeIf { speaking }?.let { drawSpeaker(it, wave.value, measurer, chip) }
        Seat.entries.forEach { drawComfortChip(it, cabin.seat(it), measurer, chip) }
        val strip = stripColor(cabin.ambient, motion.ambient.value, flash.of(Highlight.AMBIENT))
        Seat.entries.forEach { seat ->
            val open = motion.doors.getValue(seat).value
            drawCabinDoor(seat, doorImages.getValue(seat), open, motion.windows.getValue(seat).value, strip, DoorFlash(flash.of(Highlight.door(seat)), flash.of(Highlight.window(seat))))
            drawWindowChip(seat, cabin.window(seat), open, measurer, chip)
        }
        drawScreen(cabin, motion.screen.value, flash.of(Highlight.SCREEN), measurer, compact)
        if (compact) return@FittedPicture
        drawConsole(cabin, measurer)
        drawChip(measurer, cabin.driveLine(), CabinSpots.hood.on(size), PlaygroundColors.text)
        val lock = if (cabin.doorsLocked) "车门已锁" else "车门未锁"
        drawChip(measurer, lock, CabinSpots.lockChip.on(size), if (cabin.doorsLocked) PlaygroundColors.sage else PlaygroundColors.ember)
        drawFlashRing(CabinSpots.lockChip.on(size), 28.dp.toPx(), flash.of(Highlight.DOOR))
    }
}

/** The door strips' colour now, or null while the ambient light is off. */
private fun stripColor(ambient: AmbientLight, level: Float, flash: Float): Color? =
    if (level <= 0.01f) null else Color(ambient.color).copy(alpha = (0.95f * level + flash * 0.3f).coerceAtMost(1f))

/** How far a lowered window is open, beside it on its door wherever the door has swung. */
private fun DrawScope.drawWindowChip(seat: Seat, percent: Int, open: Float, measurer: TextMeasurer, textSize: TextUnit) {
    if (percent == 0) return
    val (a, b) = CabinSpots.windows.getValue(seat)
    val inward = if (seat.leftSide) 1f else -1f
    val beside = Offset((a.x + b.x) / 2 * size.width + inward * 12.dp.toPx(), (a.y + b.y) / 2 * size.height)
    val at = CabinParts.turned(beside, CabinParts.hinges.getValue(seat).on(size), CabinParts.swing(seat, open))
    drawChip(measurer, "开 $percent%", at, PlaygroundColors.cool, if (seat.leftSide) ChipAlign.START else ChipAlign.END, textSize)
}

private fun DrawScope.drawClimateWash(cabin: Cabin, flash: Float) {
    if (!cabin.climate.on) return
    val tint = when (cabin.climate.mode) {
        ClimateMode.HEAT -> PlaygroundColors.heat
        ClimateMode.COOL -> PlaygroundColors.cool
        ClimateMode.VENT -> Color.White
        ClimateMode.AUTO -> if (cabin.climate.driverTemp < cabin.insideTempC) PlaygroundColors.cool else PlaygroundColors.heat
    }
    val area = CabinSpots.interior.on(size)
    val alpha = 0.2f + 0.25f * flash
    drawRect(Brush.verticalGradient(listOf(tint.copy(alpha = alpha), Color.Transparent), area.top, area.bottom), area.topLeft, area.size)
}

private fun DrawScope.drawAmbient(ambient: AmbientLight, level: Float, flash: Float) {
    if (level <= 0.01f) return
    val color = Color(ambient.color)
    CabinSpots.dashStrips.forEach { (a, b) ->
        val from = a.on(size)
        val to = b.on(size)
        listOf(18.dp to 0.14f, 9.dp to 0.3f, 3.dp to 0.95f).forEach { (width, alpha) ->
            drawLine(color.copy(alpha = (alpha * level + flash * 0.3f).coerceAtMost(1f)), from, to, width.toPx(), StrokeCap.Round)
        }
    }
}

private fun DrawScope.drawSeat(seat: Seat, comfort: SeatComfort, motion: CabinMotion, flash: Float, measurer: TextMeasurer) {
    val center = CabinSpots.seats.getValue(seat).on(size)
    val radius = CabinSpots.SEAT_RADIUS * size.width
    val heat = motion.heat.getValue(seat).value
    val vent = motion.vent.getValue(seat).value
    if (heat > 0.02f) drawGlow(center, radius * 1.3f, PlaygroundColors.heat.copy(alpha = 0.3f + 0.17f * heat))
    if (vent > 0.02f) drawGlow(center, radius * 1.3f, PlaygroundColors.cool.copy(alpha = 0.3f + 0.17f * vent))
    drawFlashRing(center, radius, flash)
}

/** The seat's heating or ventilation, as a label at the back of the seat, clear of whoever sits on it. */
private fun DrawScope.drawComfortChip(seat: Seat, comfort: SeatComfort, measurer: TextMeasurer, textSize: TextUnit) {
    val at = CabinSpots.seats.getValue(seat).on(size) + Offset(0f, CabinSpots.SEAT_RADIUS * size.width * 1.05f)
    when {
        comfort.heat > 0 -> drawChip(measurer, "加热 ${comfort.heat} 档", at, PlaygroundColors.heat, fontSize = textSize)
        comfort.ventilation > 0 -> drawChip(measurer, "通风 ${comfort.ventilation} 档", at, PlaygroundColors.cool, fontSize = textSize)
    }
}

/** Rings going out from the speaker's seat, with a label in front of it. */
private fun DrawScope.drawSpeaker(seat: Seat, wave: Float, measurer: TextMeasurer, textSize: TextUnit) {
    val center = CabinSpots.seats.getValue(seat).on(size)
    val radius = CabinSpots.SEAT_RADIUS * size.width
    repeat(2) { i ->
        val t = (wave + i * 0.5f) % 1f
        drawCircle(PlaygroundColors.glow.copy(alpha = 0.85f * (1f - t)), radius * (0.95f + 0.55f * t), center, style = Stroke(2.5.dp.toPx()))
    }
    drawChip(measurer, "说话中", center - Offset(0f, radius * 1.05f), PlaygroundColors.glow, fontSize = textSize)
}

private fun DrawScope.drawGlow(center: Offset, radius: Float, color: Color) =
    drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center, radius), radius, center)

private fun DrawScope.drawScreen(cabin: Cabin, brightness: Float, flash: Float, measurer: TextMeasurer, compact: Boolean = false) {
    val rect = CabinSpots.screen.on(size)
    drawRoundRect(Color(0xFF0B0F0D), rect.topLeft, rect.size, CornerRadius(5.dp.toPx()))
    if (compact) {
        // Too small to read: the screen only glows as bright as it is set.
        drawRoundRect(PlaygroundColors.sage.copy(alpha = 0.45f * brightness), rect.topLeft, rect.size, CornerRadius(5.dp.toPx()))
        if (flash > 0f) drawFlashRect(rect, flash)
        return
    }
    val levels = (if (cabin.screenBrightness == 0) "熄屏" else "亮度 ${cabin.screenBrightness}%") + " · 音量 ${cabin.media.volume}"
    drawChip(measurer, levels, Offset(rect.center.x, rect.bottom + 13.dp.toPx()), PlaygroundColors.text, fontSize = 11.sp)
    if (brightness <= 0.01f) {
        if (flash > 0f) drawFlashRect(rect, flash)
        return
    }
    val lines = buildList {
        cabin.nav.takeIf { it.active }?.let { add("→ ${it.destination?.name} ${it.remainingMeters.km()}" to PlaygroundColors.glow) }
        if (cabin.media.title.isNotEmpty()) add((if (cabin.media.playing) "♪ " else "Ⅱ ") + cabin.media.title to PlaygroundColors.sage)
        if (isEmpty()) add("Bothy" to PlaygroundColors.sage)
    }
    val step = rect.height / (lines.size + 1)
    lines.forEachIndexed { i, (text, color) ->
        val dimmed = color.copy(alpha = 0.3f + 0.7f * brightness)
        drawChip(measurer, text, Offset(rect.center.x, rect.top + step * (i + 1)), dimmed, fontSize = 10.sp, background = Color.Transparent)
    }
    if (flash > 0f) {
        drawRoundRect(PlaygroundColors.glow.copy(alpha = flash), rect.topLeft, Size(rect.width, rect.height), CornerRadius(6.dp.toPx()), style = Stroke(3.dp.toPx()))
    }
}

/** Inside temperature and the air conditioning, in the gap between the front seats and the rear ones. */
private fun DrawScope.drawConsole(cabin: Cabin, measurer: TextMeasurer) {
    val climate = cabin.climate
    val setting = if (climate.on) "空调 ${climate.driverTemp.deg()} ${climate.mode.label}" else "空调关"
    drawChip(measurer, "车内 ${cabin.insideTempC.deg()} · $setting", CabinSpots.betweenRows.on(size), if (climate.on) PlaygroundColors.cool else PlaygroundColors.text)
}

private fun Cabin.driveLine(): String =
    if (drive.moving) "行驶中 ${drive.speedKmh.roundToInt()} km/h · 电量 ${drive.batteryPct.roundToInt()}% · 续航 $rangeKm km"
    else "停车 · 电量 ${drive.batteryPct.roundToInt()}% · 续航 $rangeKm km"

private const val MOTION_MS = 600
private const val WAVE_MS = 1600
private val FULL_CHIP = 12.sp
private val COMPACT_CHIP = 9.sp
private const val WINDOW_MS = 1400
