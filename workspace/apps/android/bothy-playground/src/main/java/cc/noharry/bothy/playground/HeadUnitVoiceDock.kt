package cc.noharry.bothy.playground

import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

/**
 * 小博's corner of the bottom bar, where its voice shows (owner 2026-10-25: 「真实的全屏地图模式小博唤醒就不要单独弹出来一个
 * asr的框悬浮，就复用左下角那个…像外面的卡片一样把那个光效和asr显示放到左下角」). Tapped, 小博 listens; while it listens the
 * words heard take the hint's place and the corner widens to [wide]; tapped again it stops listening ([onClose], owner:
 * 「点击他的头像就退出唤醒，不需要那个x」). The bar draws the light behind it ([VoiceGlow]).
 */
@Composable
internal fun VoiceDock(view: AssistantView, wide: Dp, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val listening = view.listening
    Row(
        modifier.animateContentSize(tween(DOCK_MS)).then(if (listening) Modifier.width(wide) else Modifier)
            .clip(RoundedCornerShape(50)).clickable { if (listening) onClose() else Assistant.wake(context) }
            .padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Mascot(view, 40.dp)
        Column(Modifier.weight(1f, fill = listening)) {
            Text("小博", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (listening) {
                TailText(view.voiceText(), Color.White, 13.sp)
            } else {
                Text("说「你好小博」或点我", color = UnitColors.muted, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

/**
 * The light along the bottom of Bothy's card, in its colours: a band rising from the bottom edge whose top swells on two
 * travelling waves while the hues drift across, fading out at its right end so it has no edge. The head unit hears no
 * loudness from Bothy, so it moves on its own, higher while 小博 talks. Only the draw phase runs per frame.
 */
@Composable
internal fun VoiceGlow(listening: Boolean, speaking: Boolean, modifier: Modifier) {
    val presence by animateFloatAsState(if (listening || speaking) 1f else 0f, tween(if (listening || speaking) GLOW_IN_MS else GLOW_OUT_MS), label = "glow")
    val height by animateFloatAsState(if (speaking) GLOW_SPEAKING else GLOW_LISTENING, tween(GLOW_IN_MS), label = "glowHeight")
    if (presence == 0f) return
    var seconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { seconds = (it - start) / 1e9f }
    }
    val layer = Modifier.graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) renderEffect = BlurEffect(GLOW_BLUR_DP.dp.toPx(), GLOW_BLUR_DP.dp.toPx(), TileMode.Decal)
    }
    Canvas(modifier.then(layer)) { drawGlow(seconds, height, presence) }
}

private fun DrawScope.drawGlow(seconds: Float, height: Float, presence: Float) {
    val band = Path().apply {
        moveTo(0f, size.height)
        for (i in 0..GLOW_POINTS) {
            val u = i.toFloat() / GLOW_POINTS
            val swell = 0.6f * sin(u * 2 * PI.toFloat() * 1.3f - seconds * 1.6f) + 0.4f * sin(u * 2 * PI.toFloat() * 2.1f + seconds * 1.1f)
            lineTo(size.width * u, size.height * (1f - height * (1f + 0.35f * swell)))
        }
        lineTo(size.width, size.height)
        close()
    }
    val drift = seconds * GLOW_DRIFT
    val stops = List(GLOW_STOPS) { glowColor(drift + it.toFloat() / (GLOW_STOPS - 1)) }
    drawPath(band, Brush.horizontalGradient(stops), alpha = presence * GLOW_ALPHA)
    // Fades upward: full at the bottom edge, faint at the crest; and out towards its right end.
    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Black)), blendMode = BlendMode.DstIn)
    drawRect(Brush.horizontalGradient(0f to Color.Black, 0.7f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
}

/** Bothy's card's palette, round and round: [at] in palette lengths. */
private fun glowColor(at: Float): Color {
    val position = ((at % 1f) + 1f) % 1f * GLOW_PALETTE.size
    val from = GLOW_PALETTE[position.toInt() % GLOW_PALETTE.size]
    val to = GLOW_PALETTE[(position.toInt() + 1) % GLOW_PALETTE.size]
    return androidx.compose.ui.graphics.lerp(from, to, position - position.toInt())
}

private val GLOW_PALETTE = listOf(Color(0xFF8CC7A5), Color(0xFFF5C26B), Color(0xFFF2925A), Color(0xFF7FB3C9))
private const val GLOW_POINTS = 24
private const val GLOW_STOPS = 6
private const val GLOW_DRIFT = 0.08f
private const val GLOW_ALPHA = 0.85f
private const val GLOW_LISTENING = 0.55f
private const val GLOW_SPEAKING = 0.8f
private const val GLOW_BLUR_DP = 10
private const val GLOW_IN_MS = 420
private const val GLOW_OUT_MS = 700
private const val DOCK_MS = 260
