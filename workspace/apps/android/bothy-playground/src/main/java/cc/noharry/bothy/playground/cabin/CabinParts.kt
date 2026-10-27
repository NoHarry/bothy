package cc.noharry.bothy.playground.cabin
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.playground.R
import cc.noharry.bothy.playground.headunit.CabinSpots
import cc.noharry.bothy.playground.headunit.PlaygroundColors
import cc.noharry.bothy.playground.headunit.drawFlashRect
import cc.noharry.bothy.playground.headunit.on
import cc.noharry.bothy.playground.link.open
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The cabin's moving parts, cut from the cabin picture itself (cabin_part_*.png) over a base picture that shows the
 * door openings and the trunk well under them (scene_cabin_base.webp): shut, the parts sit exactly where they were cut,
 * so the car looks as drawn. Seen from above a door swings in the picture's plane about its front hinge, and the
 * tailgate tilts up about its front edge, foreshortening toward it. Measured on those pictures; new ones need these again.
 */
internal object CabinParts {
    val doors = mapOf(
        Seat.DRIVER to Rect(0.0f, 0.1631f, 0.197f, 0.4081f),
        Seat.PASSENGER to Rect(0.803f, 0.1631f, 1.0f, 0.4081f),
        Seat.REAR_LEFT to Rect(0.0335f, 0.4081f, 0.1861f, 0.6658f),
        Seat.REAR_RIGHT to Rect(0.8139f, 0.4081f, 0.9665f, 0.6658f),
    )
    /** Each door's hinge: its front edge, on the body's skin. */
    val hinges = mapOf(
        Seat.DRIVER to Offset(0.052f, 0.207f),
        Seat.PASSENGER to Offset(0.948f, 0.207f),
        Seat.REAR_LEFT to Offset(0.043f, 0.412f),
        Seat.REAR_RIGHT to Offset(0.957f, 0.412f),
    )
    /** The ambient strip on each door's inner panel; it swings with the door. */
    val doorStrips = mapOf(
        Seat.DRIVER to (Offset(0.154f, 0.250f) to Offset(0.143f, 0.395f)),
        Seat.PASSENGER to (Offset(0.846f, 0.250f) to Offset(0.857f, 0.395f)),
        Seat.REAR_LEFT to (Offset(0.136f, 0.479f) to Offset(0.119f, 0.653f)),
        Seat.REAR_RIGHT to (Offset(0.864f, 0.479f) to Offset(0.881f, 0.653f)),
    )
    val tailgate = Rect(0.1699f, 0.8517f, 0.8301f, 0.9577f)
    val images = mapOf(
        Seat.DRIVER to R.drawable.cabin_part_driver,
        Seat.PASSENGER to R.drawable.cabin_part_passenger,
        Seat.REAR_LEFT to R.drawable.cabin_part_rear_left,
        Seat.REAR_RIGHT to R.drawable.cabin_part_rear_right,
    )

    /** How far a door at [open] (0…1, a little past 1 while it rides on its check strap) is turned, degrees. */
    fun swing(seat: Seat, open: Float): Float = (if (seat.leftSide) 1f else -1f) * DOOR_SWING * open.coerceAtLeast(0f)

    /** [point] turned [degrees] about [pivot], as the canvas turns it (clockwise for positive degrees). */
    fun turned(point: Offset, pivot: Offset, degrees: Float): Offset {
        val r = Math.toRadians(degrees.toDouble())
        val d = point - pivot
        return pivot + Offset((d.x * cos(r) - d.y * sin(r)).toFloat(), (d.x * sin(r) + d.y * cos(r)).toFloat())
    }

    const val DOOR_SWING = 62f
    const val TAILGATE_TILT = 80f
}

internal val Seat.leftSide: Boolean get() = this == Seat.DRIVER || this == Seat.REAR_LEFT

/**
 * A door's swing: out on a soft spring that rides a little past the stop and settles back, as a door on its check strap
 * does; shut by a push that is fastest at the very end.
 */
@Composable
internal fun doorSwing(open: Boolean): State<Float> {
    val swing = remember { Animatable(if (open) 1f else 0f) }
    LaunchedEffect(open) {
        if (open) swing.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 32f)) else swing.animateTo(0f, tween(SHUT_MS, easing = SHUT_EASING))
    }
    return swing.asState()
}

/** The electric tailgate: the same slow, even travel both ways. */
@Composable
internal fun tailgateLift(open: Boolean): State<Float> {
    val lift = remember { Animatable(if (open) 1f else 0f) }
    LaunchedEffect(open) { lift.animateTo(if (open) 1f else 0f, tween(TAILGATE_MS, easing = FastOutSlowInEasing)) }
    return lift.asState()
}

/** One door, its shadow on the ground, and what rides on it: its window (lowered by [window]) and its ambient strip. */
internal fun DrawScope.drawCabinDoor(seat: Seat, image: ImageBitmap, open: Float, window: Float, strip: Color?, flash: DoorFlash) {
    val rect = CabinParts.doors.getValue(seat).on(size)
    val pivot = CabinParts.hinges.getValue(seat).on(size)
    val degrees = CabinParts.swing(seat, open)
    val offset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt())
    val drawn = IntSize(rect.width.roundToInt(), rect.height.roundToInt())
    if (degrees != 0f) {
        val shade = ColorFilter.tint(Color.Black.copy(alpha = (0.45f * open).coerceAtMost(0.4f)), BlendMode.SrcIn)
        val dropX = 3.dp.toPx()
        val dropY = 5.dp.toPx()
        withTransform({
            translate(dropX, dropY)
            rotate(degrees, pivot)
        }) { drawImage(image, dstOffset = offset, dstSize = drawn, colorFilter = shade) }
    }
    withTransform({ rotate(degrees, pivot) }) {
        drawImage(image, dstOffset = offset, dstSize = drawn)
        strip?.let { drawDoorStrip(seat, it) }
        drawLoweredWindow(seat, window)
        if (flash.window > 0f) {
            val (a, b) = CabinSpots.windows.getValue(seat)
            drawLine(PlaygroundColors.glow.copy(alpha = flash.window), a.on(size), b.on(size), 9.dp.toPx(), StrokeCap.Round)
        }
        drawFlashRect(rect, flash.door)
    }
}

internal class DoorFlash(val door: Float, val window: Float)

private fun DrawScope.drawDoorStrip(seat: Seat, color: Color) {
    val (a, b) = CabinParts.doorStrips.getValue(seat)
    listOf(16.dp to 0.14f, 8.dp to 0.3f, 3.dp to 0.95f).forEach { (width, alpha) ->
        drawLine(color.copy(alpha = (color.alpha * alpha / 0.95f).coerceAtMost(1f)), a.on(size), b.on(size), width.toPx(), StrokeCap.Round)
    }
}

/**
 * A lowered window seen from above: the glass sinks into the door, so its strip darkens into the gap it leaves, and
 * air moves through.
 */
private fun DrawScope.drawLoweredWindow(seat: Seat, open: Float) {
    if (open <= 0.01f) return
    val (a, b) = CabinSpots.windows.getValue(seat)
    val top = a.on(size)
    val bottom = b.on(size)
    drawLine(Color(0xFF0E1311).copy(alpha = 0.88f * open), top, bottom, 5.dp.toPx(), StrokeCap.Round)
    val inward = if (seat.leftSide) 3.dp.toPx() else -3.dp.toPx()
    drawLine(Color.White.copy(alpha = 0.22f * open), top + Offset(inward, 0f), bottom + Offset(inward, 0f), 1.dp.toPx())
    val outward = if (seat.leftSide) -1f else 1f
    repeat(3) { i ->
        val y = top.y + (bottom.y - top.y) * (0.3f + 0.2f * i)
        val start = Offset(top.x, y)
        drawLine(Color.White.copy(alpha = 0.45f * open), start, start + Offset(outward * 12.dp.toPx(), -3.dp.toPx()), 1.5.dp.toPx(), StrokeCap.Round)
    }
}

/** The tailgate tilting up about its front edge, the trunk light coming on in the well it uncovers. */
internal fun DrawScope.drawTailgate(image: ImageBitmap, lift: Float, flash: Float) {
    val rect = CabinParts.tailgate.on(size)
    if (lift > 0.01f) {
        drawRect(Brush.radialGradient(listOf(PlaygroundColors.glow.copy(alpha = 0.22f * lift), Color.Transparent), rect.center, rect.width * 0.5f), rect.topLeft, rect.size)
    }
    val foreshortened = cos(Math.toRadians((CabinParts.TAILGATE_TILT * lift).toDouble())).toFloat()
    withTransform({ scale(1f, foreshortened, pivot = rect.topLeft) }) {
        drawImage(image, dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()), dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()))
        // Tilted toward the light, the lid's paint brightens.
        if (lift > 0.01f) drawRect(Color.White.copy(alpha = 0.16f * lift), rect.topLeft, rect.size)
    }
    drawFlashRect(rect, flash)
}

private const val SHUT_MS = 460
private val SHUT_EASING = CubicBezierEasing(0.5f, 0f, 1f, 0.4f)
private const val TAILGATE_MS = 1600
