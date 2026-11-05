package cc.noharry.bothy.app.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ui.IconMic
import cc.noharry.bothy.app.console.ui.StatusDot
import cc.noharry.bothy.protocol.VoiceView
import kotlinx.coroutines.delay

/**
 * While the mic is held, as the design shows it: what is heard so far, large, over the conversation, and a green panel
 * at the foot with how long the hold has gone on, the voice's level as bars and the mic. Sliding up cancels and letting
 * go sends; both are the composer's mic button's, which stays under the panel while the finger is on it.
 */
@Composable
internal fun PhoneHold(voice: VoiceView, modifier: Modifier = Modifier) {
    val colors = ConsoleColors
    val heard = heldText(voice)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.clip(CircleShape).background(colors.elevated).padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color = colors.accent, pulsing = true, modifier = Modifier.size(6.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.voice_hold_listening), style = PhoneType.small, color = colors.muted)
        }
        // The newest words: a start ellipsis works on one line only, so a long hold shows its tail after an ellipsis.
        Text(
            if (heard.length > HEARD_TAIL) "…" + heard.takeLast(HEARD_TAIL) else heard,
            style = PhoneType.title.copy(fontSize = HEARD_SIZE, lineHeight = HEARD_LINE),
            color = colors.text,
            textAlign = TextAlign.Center,
            maxLines = HEARD_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = PHONE_MARGIN, vertical = 14.dp),
        )
        HoldPanel(voice)
    }
}

@Composable
private fun HoldPanel(voice: VoiceView) {
    val colors = ConsoleColors
    val startedAt = remember { System.currentTimeMillis() }
    var now by remember { mutableLongStateOf(startedAt) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    val green = if (colors.isDark) listOf(Color(0xFF1F5A42), Color(0xFF123B2B)) else listOf(PANEL_TOP, PANEL_BOTTOM)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = PANEL_RADIUS, topEnd = PANEL_RADIUS))
            .background(Brush.verticalGradient(green))
            .padding(top = 14.dp, bottom = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = Color.White.copy(alpha = SOFT), modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.phone_hold_cancel_here), style = PhoneType.small, color = Color.White.copy(alpha = SOFT))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = MINT, pulsing = true, modifier = Modifier.size(7.dp))
            Spacer(Modifier.width(8.dp))
            Text(holdClock(now - startedAt), style = PhoneType.title, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        LevelWave(voice.micLevels)
        Box(Modifier.padding(vertical = 6.dp).size(RING_SIZE).clip(CircleShape).background(Color.White.copy(alpha = RING_ALPHA)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(MIC_SIZE).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                Icon(IconMic, contentDescription = null, tint = PANEL_BOTTOM, modifier = Modifier.size(30.dp))
            }
        }
        Text(stringResource(R.string.phone_hold_release), style = PhoneType.label, color = Color.White.copy(alpha = STRONG))
        Spacer(Modifier.height(4.dp))
    }
}

/** The voice's newest loudness as white bars, tallest in the middle of each. */
@Composable
private fun LevelWave(levels: List<Float>) {
    val recent = levels.takeLast(WAVE_BARS)
    Canvas(Modifier.width(WAVE_WIDTH).height(WAVE_HEIGHT)) {
        val step = size.width / WAVE_BARS
        val bar = step * BAR_SHARE
        recent.forEachIndexed { index, level ->
            val h = size.height * (MIN_BAR + (1f - MIN_BAR) * level.coerceIn(0f, 1f))
            drawRoundRect(
                color = Color.White.copy(alpha = if (index % 2 == 0) 1f else SOFT),
                topLeft = Offset(index * step + (step - bar) / 2, (size.height - h) / 2),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}

/** A hold's clock: 0:04, 1:12. */
internal fun holdClock(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private val PANEL_TOP = Color(0xFF1F6B4E)
private val PANEL_BOTTOM = Color(0xFF005238)
private val MINT = Color(0xFF8CD6B2)
private val PANEL_RADIUS = 32.dp
private val RING_SIZE = 104.dp
private val MIC_SIZE = 80.dp
private val WAVE_WIDTH = 120.dp
private val WAVE_HEIGHT = 44.dp
private val HEARD_SIZE = 24.sp
private val HEARD_LINE = 32.sp
private const val HEARD_LINES = 4
/** About what [HEARD_LINES] lines hold. */
private const val HEARD_TAIL = 60
private const val WAVE_BARS = 11
private const val BAR_SHARE = 0.45f
private const val MIN_BAR = 0.18f
private const val SOFT = 0.7f
private const val STRONG = 0.9f
private const val RING_ALPHA = 0.14f
private const val TICK_MS = 250L
