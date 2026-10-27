package cc.noharry.bothy.playground.headunit
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.playground.link.closesAt
import cc.noharry.bothy.playground.link.holdMs
import kotlin.math.ceil

/**
 * The card's X, counting down while the card waits to fold by itself (owner 2026-10-25: 「卡片退出倒计时也加上」, as on
 * Bothy's own card): a ring round the cross that empties and the seconds left beside it, until [closesAt] (epoch ms; 0
 * while 小博 works on it or the card was touched). [holdMs] is the whole wait, so the ring starts full.
 */
@Composable
internal fun CloseCountdown(closesAt: Long, holdMs: Long, onClose: () -> Unit) {
    val left = remember { Animatable(0f) }
    LaunchedEffect(closesAt, holdMs) {
        val remaining = if (closesAt > 0L && holdMs > 0L) (closesAt - System.currentTimeMillis()).coerceIn(0L, holdMs) else 0L
        left.snapTo(remaining.toFloat() / holdMs.coerceAtLeast(1L))
        if (remaining > 0L) left.animateTo(0f, tween(remaining.toInt(), easing = LinearEasing))
    }
    // Recomposes once a second; the ring reads the animation while drawing.
    val seconds by remember(holdMs) { derivedStateOf { ceil(left.value * holdMs / 1000f).toInt() } }
    Row(
        Modifier.clip(CircleShape).clickable(onClick = onClose),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (seconds > 0) Text("${seconds}s", color = UnitColors.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            if (seconds > 0) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 2.dp.toPx()
                    drawArc(
                        color = UnitColors.teal,
                        startAngle = -90f,
                        sweepAngle = 360f * left.value,
                        useCenter = false,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Icon(Icons.Filled.Close, contentDescription = "退出语音", tint = UnitColors.muted, modifier = Modifier.size(20.dp))
        }
    }
}
