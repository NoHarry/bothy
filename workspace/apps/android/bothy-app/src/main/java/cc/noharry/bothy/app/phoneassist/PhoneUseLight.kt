package cc.noharry.bothy.app.phoneassist

import android.content.Context
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.overlay.OverlayComposeOwner
import cc.noharry.bothy.app.overlay.OverlayTaskSignal
import cc.noharry.bothy.app.overlay.overlayWindowType

/**
 * While 小博 operates the screen on a phone: a glow around the screen's edge, so the person knows the phone is not
 * theirs for the moment, a ripple where 小博 taps, and at the top what it does ([status], the capsule's face). It takes
 * no touches and leaves screenshots. One window, not the capsule's beside it: from Android 12 the system lets touches
 * through untouchable windows of other apps only while their opacity together stays under 0.8, and two overlapping
 * windows would stop 小博's own taps there.
 */
internal class PhoneUseLight(context: Context, private val windowManager: WindowManager) {
    private var tap by mutableStateOf<Offset?>(null)
    var status by mutableStateOf<OverlayTaskSignal?>(null)
    private var tapCount by mutableStateOf(0)
    private val owner = OverlayComposeOwner()
    private val view = ComposeView(context)
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        overlayWindowType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    )

    init {
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            Box(Modifier.fillMaxSize()) {
                EdgeGlow(tap, tapCount)
                status?.let { CapsuleFace(it, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars)) }
            }
        }
        owner.attachTo(view)
        view.visibility = View.GONE
        windowManager.addView(view, params)
    }

    var shown: Boolean
        get() = view.visibility == View.VISIBLE
        set(value) {
            view.visibility = if (value) View.VISIBLE else View.GONE
        }

    /** A ripple where 小博 taps, in screen pixels. */
    fun tapAt(x: Int, y: Int) {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        tap = Offset((x - location[0]).toFloat(), (y - location[1]).toFloat())
        tapCount++
    }

    fun destroy() {
        runCatching { windowManager.removeView(view) }
        owner.destroy()
    }
}

@Composable
private fun EdgeGlow(tap: Offset?, tapCount: Int) {
    val breath = rememberInfiniteTransition(label = "edge")
    val alpha by breath.animateFloat(GLOW_DIM, GLOW_FULL, infiniteRepeatable(tween(GLOW_BREATH_MS), RepeatMode.Reverse), label = "alpha")
    val ripple = remember { Animatable(1f) }
    LaunchedEffect(tapCount) {
        if (tapCount == 0) return@LaunchedEffect
        ripple.snapTo(0f)
        ripple.animateTo(1f, tween(RIPPLE_MS))
    }
    Canvas(Modifier.fillMaxSize()) {
        val corner = CornerRadius(EDGE_CORNER.toPx())
        for (layer in 0 until GLOW_LAYERS) {
            val width = (GLOW_LAYERS - layer) * GLOW_STEP.toPx()
            drawRoundRect(GLOW.copy(alpha = alpha / (layer + 1)), cornerRadius = corner, style = Stroke(width))
        }
        val at = tap ?: return@Canvas
        if (ripple.value < 1f) {
            val radius = RIPPLE_RADIUS.toPx() * (0.3f + ripple.value)
            drawCircle(GLOW.copy(alpha = (1f - ripple.value) * RIPPLE_ALPHA), radius, at)
            drawCircle(Color.White.copy(alpha = 1f - ripple.value), radius * 0.35f, at, style = Stroke(2.dp.toPx()))
        }
    }
}

private val GLOW = Color(0xFF6FD6A4)
private val EDGE_CORNER = 28.dp
private val GLOW_STEP = 3.dp
private val RIPPLE_RADIUS = 28.dp
private const val GLOW_LAYERS = 3
private const val GLOW_DIM = 0.35f
private const val GLOW_FULL = 0.9f
private const val GLOW_BREATH_MS = 1_100
private const val RIPPLE_MS = 450
private const val RIPPLE_ALPHA = 0.45f
