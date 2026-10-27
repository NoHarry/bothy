package cc.noharry.bothy.app.console.ui
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import cc.noharry.bothy.app.a2ui.initialValue
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.color

/**
 * Replicates the Codex status-line shimmer animation.
 *
 * A cosine-band sweep travels across the text every [SWEEP_DURATION_MS].
 * Instead of Codex's per-character gradient, we animate the entire text's
 * color between [baseColor] and [highlightColor] following a cosine curve,
 * which gives the same "breathing highlight" feel for short status strings.
 */
@Composable
internal fun ShimmerText(
    text: String,
    modifier: Modifier = Modifier,
    baseColor: Color = ConsoleColors.muted,
    highlightColor: Color = ConsoleColors.activity,
    fontWeight: FontWeight = FontWeight.SemiBold,
) {
    if (text.isEmpty()) return
    val transition = rememberInfiniteTransition(label = "shimmer")
    // Cosine breathing: 0 → 1 → 0 using RepeatMode.Reverse with linear tween
    // produces a triangle wave. We use that directly as the blend factor.
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(SWEEP_DURATION_MS / 2, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmer-progress",
    )
    // Cosine curve for smoother breathing (0 → 1 → 0).
    val blend = 0.5f * (1f + kotlin.math.cos((1f - progress) * Math.PI).toFloat())
    val color = lerp(baseColor, highlightColor, blend * ALPHA_CAP)

    androidx.compose.material3.Text(
        text = text,
        color = color,
        fontWeight = fontWeight,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

// Codex visual constants (from shimmer.rs)
private const val SWEEP_DURATION_MS = 2000     // 2.0s full breath cycle
private const val ALPHA_CAP = 0.9f             // never fully replace base color
