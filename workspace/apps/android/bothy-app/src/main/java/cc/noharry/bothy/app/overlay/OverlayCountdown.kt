package cc.noharry.bothy.app.overlay
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.app.console.color
import kotlin.math.ceil

/**
 * The card's close button. While the card waits to fold by itself it counts down to it (owner 2026-10-25: a card that
 * folded after a while on its own looked odd): a ring round the cross that empties, and the seconds left beside it. The
 * cross stays, to fold the card sooner; a touch on the card starts the wait again, and the ring with it.
 */
@Composable
internal fun OverlayCloseButton(collapseAt: Long, collapseMillis: Long, onClick: () -> Unit) {
    val left = remember { Animatable(0f) }
    LaunchedEffect(collapseAt, collapseMillis) {
        val remaining = if (collapseAt > 0L && collapseMillis > 0L) (collapseAt - System.currentTimeMillis()).coerceIn(0L, collapseMillis) else 0L
        left.snapTo(remaining.toFloat() / collapseMillis.coerceAtLeast(1L))
        if (remaining > 0L) left.animateTo(0f, tween(remaining.toInt(), easing = LinearEasing))
    }
    // Recomposes once a second, not every frame: the ring reads the animation while drawing.
    val seconds by remember(collapseMillis) { derivedStateOf { ceil(left.value * collapseMillis / 1000f).toInt() } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (seconds > 0) Text("$seconds 秒后收起", color = OverlayPalette.faint, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(OverlayPalette.tile)
                .clickable(onClick = onClick)
                .semantics { contentDescription = "收起" },
        ) {
            if (seconds > 0) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 2.5.dp.toPx()
                    val inset = stroke / 2 + 1.dp.toPx()
                    drawArc(
                        color = OverlayPalette.sage,
                        startAngle = -90f,
                        sweepAngle = 360f * left.value,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - inset * 2, size.height - inset * 2),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Icon(Icons.Filled.Close, contentDescription = null, tint = OverlayPalette.secondary, modifier = Modifier.size(21.dp))
        }
    }
}
