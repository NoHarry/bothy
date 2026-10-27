package cc.noharry.bothy.playground.headunit
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.playground.R
import cc.noharry.bothy.playground.nav.anchor
import kotlin.math.roundToInt

/** Which spots the latest change flashes, and how bright the flash is now (1 just after, fading to 0). */
internal class Flash(private val keys: Set<String>, private val level: State<Float>) {
    fun of(key: String): Float = if (key in keys) level.value else 0f

    fun any(predicate: (String) -> Boolean): Float = if (keys.any(predicate)) level.value else 0f
}

/**
 * [res] fitted into the space at its [aspect], with [overlay] drawn over it in the picture's own coordinates. A tap or a
 * long press reports where, as fractions of the picture.
 */
@Composable
internal fun FittedPicture(
    @DrawableRes res: Int,
    aspect: Float,
    modifier: Modifier = Modifier,
    onTap: (Offset) -> Unit = {},
    onLongPress: (Offset) -> Unit = {},
    overlay: DrawScope.() -> Unit,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val width = min(maxWidth, maxHeight * aspect)
        val tap by rememberUpdatedState(onTap)
        val press by rememberUpdatedState(onLongPress)
        val gestures = Modifier.pointerInput(Unit) {
            fun fraction(at: Offset) = Offset(at.x / size.width, at.y / size.height)
            detectTapGestures(onTap = { tap(fraction(it)) }, onLongPress = { press(fraction(it)) })
        }
        Box(Modifier.size(width, width / aspect).then(gestures)) {
            Image(painterResource(res), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(Modifier.fillMaxSize()) { overlay() }
        }
    }
}

/** A value that eases toward [target] but jumps back at once (a vacuum starting its next room). */
@Composable
internal fun easedForward(target: Float, vararg keys: Any?): State<Float> {
    val value = remember { Animatable(target) }
    LaunchedEffect(target, *keys) {
        if (target < value.value) value.snapTo(target) else value.animateTo(target, tween(EASE_MS, easing = LinearEasing))
    }
    return value.asState()
}

internal enum class ChipAlign { START, CENTER, END }

/** A rounded label at [anchor] (its vertical centre), readable over the picture. */
internal fun DrawScope.drawChip(
    measurer: TextMeasurer,
    text: String,
    anchor: Offset,
    color: Color = PlaygroundColors.text,
    align: ChipAlign = ChipAlign.CENTER,
    fontSize: TextUnit = 12.sp,
    background: Color = PlaygroundColors.chip,
) {
    val layout = measurer.measure(text, TextStyle(color = color, fontSize = fontSize, fontWeight = FontWeight.Medium))
    val padX = 8.dp.toPx()
    val padY = 3.dp.toPx()
    val width = layout.size.width + padX * 2
    val height = layout.size.height + padY * 2
    val left = when (align) {
        ChipAlign.START -> anchor.x
        ChipAlign.CENTER -> anchor.x - width / 2
        ChipAlign.END -> anchor.x - width
    }
    val top = anchor.y - height / 2
    drawRoundRect(background, Offset(left, top), Size(width, height), CornerRadius(height / 2))
    drawText(layout, topLeft = Offset(left + padX, top + padY))
}

/** The ring a changed spot gets for a moment, growing out as it fades. */
internal fun DrawScope.drawFlashRing(center: Offset, radius: Float, level: Float) {
    if (level <= 0f) return
    drawCircle(PlaygroundColors.glow.copy(alpha = level * 0.9f), radius * (1.35f - 0.35f * level), center, style = Stroke(3.dp.toPx()))
}

internal fun DrawScope.drawFlashRect(rect: Rect, level: Float) {
    if (level <= 0f) return
    val grow = (1f - level) * 6.dp.toPx()
    drawRoundRect(
        PlaygroundColors.glow.copy(alpha = level * 0.9f),
        Offset(rect.left - grow, rect.top - grow),
        Size(rect.width + grow * 2, rect.height + grow * 2),
        CornerRadius(10.dp.toPx()),
        style = Stroke(3.dp.toPx()),
    )
}

/** The four felt people seen from above, facing up the picture; seats and rooms take them in order. */
@Composable
internal fun passengerImages(): List<ImageBitmap> = PASSENGERS.map { ImageBitmap.imageResource(it) }

private val PASSENGERS = listOf(R.drawable.passenger_0, R.drawable.passenger_1, R.drawable.passenger_2, R.drawable.passenger_3)

/** [image] with its centre at [center], [side] across. */
internal fun DrawScope.drawPerson(image: ImageBitmap, center: Offset, side: Float) {
    drawImage(
        image,
        dstOffset = IntOffset((center.x - side / 2).roundToInt(), (center.y - side / 2).roundToInt()),
        dstSize = IntSize(side.roundToInt(), side.roundToInt()),
    )
}

private const val EASE_MS = 520
