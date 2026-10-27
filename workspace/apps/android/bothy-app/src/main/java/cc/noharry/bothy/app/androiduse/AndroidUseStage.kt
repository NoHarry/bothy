package cc.noharry.bothy.app.androiduse
import android.content.Context
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.SweepGradient
import android.hardware.input.InputManager
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import cc.noharry.bothy.app.overlay.Mascot
import cc.noharry.bothy.app.overlay.MascotAction
import cc.noharry.bothy.app.overlay.MascotPose
import cc.noharry.bothy.app.overlay.MascotReaction
import cc.noharry.bothy.app.overlay.OverlayComposeOwner
import cc.noharry.bothy.app.overlay.OverlayVoiceCaption
import cc.noharry.bothy.app.overlay.afterNextFrame
import cc.noharry.bothy.app.overlay.grantsSystemWindows
import cc.noharry.bothy.app.overlay.pointerFingertip
import cc.noharry.bothy.app.overlay.trustOverlay
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android Use on the overlay: a glow round the edges of the whole screen while the agent operates it, and 小博 as the
 * pointer, flying to each tap with its fingertip on the target and dragging along each swipe, with a ripple where it
 * presses. One still, untouchable window covers the display: TYPE_DRAG, above the dock, when the platform signature
 * grants INTERNAL_SYSTEM_WINDOW (as for a system voice assistant's click animation), else an ordinary overlay. Everything moves by
 * layer and draw properties; the window is resized once on entry and once on exit, and is 1×1 and empty between uses.
 */
internal class AndroidUseStage(
    private val context: Context,
    private val windowManager: WindowManager,
    private val pose: MascotPose,
    fallbackType: Int,
    scale: Float,
) {
    /** The act while 小博 is not pointing (the run's own act). */
    var action by mutableStateOf(MascotAction.WORKING)
    var driving by mutableStateOf(false)
    /** A voice session listens: what it hears shows as a caption at the top of the screen. */
    var voice by mutableStateOf(false)
    private var active by mutableStateOf(false)
    private var hidden by mutableStateOf(false)
    private var pointing by mutableStateOf(false)
    private var ripple by mutableStateOf<StageRipple?>(null)
    private val position = Animatable(Offset.Zero, Offset.VectorConverter)
    private val reach = Animatable(0f)
    private val unitPx = context.resources.displayMetrics.density * scale
    private val fingertip = pointerFingertip(unitPx)
    // Animatables need a frame clock; the Compose UI dispatcher carries one, a plain main scope does not.
    private val scope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.Main)
    private val owner = OverlayComposeOwner()
    private val view = ComposeView(context)
    private val origin = IntArray(2)
    private val params = WindowManager.LayoutParams(1, 1, fallbackType, STAGE_FLAGS, PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // Without this, API 30 insets the window by the system bars and the glow stops above the dock.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
        // API 31+ drops touches, injected gestures included, that pass an untrusted window more opaque than this.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alpha = context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
        }
    }
    private val shownAlpha = params.alpha

    init {
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) { Stage() }
        }
        owner.attachTo(view)
        addWindow()
    }

    /** Covers the screen with 小博 at [from] (screen pixels) and lights the edges; [onShown] once it is on screen. */
    fun begin(from: Offset, onShown: () -> Unit) {
        val (width, height) = screenSize()
        params.width = width
        params.height = height
        params.alpha = shownAlpha
        update()
        scope.launch {
            position.snapTo(from)
            reach.snapTo(0f)
        }
        hidden = false
        pointing = false
        active = true
        view.afterNextFrame(onShown)
    }

    /** Flies the fingertip to [target] (screen pixels); a press squashes 小博 and ripples the target. */
    fun pointAt(target: Offset, press: Boolean, onArrived: () -> Unit) {
        scope.launch {
            pointing = true
            launch { reach.animateTo(1f, tween(REACH_MS)) }
            position.animateTo(target, tween(travelMillis(position.value, target), easing = POINT_EASING))
            if (press) {
                ripple = StageRipple(target, System.nanoTime())
                pose.react(MascotReaction.LAND)
            }
            onArrived()
        }
    }

    /** Puts the fingertip on [from], reports [onStarted], then drags it to [to] over [durationMs] with the gesture. */
    fun drag(from: Offset, to: Offset, durationMs: Long, onStarted: () -> Unit) {
        scope.launch {
            pointing = true
            launch { reach.animateTo(1f, tween(REACH_MS)) }
            position.animateTo(from, tween(travelMillis(position.value, from), easing = POINT_EASING))
            onStarted()
            position.animateTo(to, tween(durationMs.toInt().coerceAtLeast(1), easing = LinearEasing))
        }
    }

    /**
     * Hides glow and 小博 for a screenshot; [onHidden] once a frame without them is on screen. A committed frame is
     * not yet a composited one: under load the capture caught the glow about half the time, so the window itself
     * goes transparent too (the compositor applies that without this view redrawing) and a short settle follows.
     */
    fun hide(onHidden: () -> Unit) {
        hidden = true
        windowAlpha(0f)
        view.afterNextFrame { view.afterNextFrame { view.postDelayed(onHidden, CAPTURE_SETTLE_MS) } }
    }

    fun reveal() {
        hidden = false
        windowAlpha(shownAlpha)
    }

    /** Fades the glow and flies 小博 back to [home] (screen pixels); [onLanded] when it is there. */
    fun end(home: Offset, onLanded: () -> Unit) {
        active = false
        hidden = false
        windowAlpha(shownAlpha)
        scope.launch {
            pointing = false
            launch { reach.animateTo(0f, tween(REACH_MS)) }
            position.animateTo(home, tween(travelMillis(position.value, home), easing = POINT_EASING))
            onLanded()
        }
    }

    /** Folds the window back to 1×1 until the next use. */
    fun close() {
        driving = false
        active = false
        pointing = false
        ripple = null
        params.width = 1
        params.height = 1
        update()
    }

    fun destroy() {
        scope.cancel()
        runCatching { windowManager.removeView(view) }
        owner.destroy()
    }

    @Composable
    private fun Stage() {
        val glow by animateFloatAsState(if (active && !hidden) 1f else 0f, tween(if (hidden) 0 else GLOW_FADE_MS), label = "glow")
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (hidden) 0f else 1f }) {
            if (glow > 0f) EdgeGlow(glow)
            if (voice && active) OverlayVoiceCaption(Modifier.align(Alignment.TopCenter))
            ripple?.let { Ripple(it) }
            if (active || driving) {
                Mascot(
                    pose,
                    if (pointing) MascotAction.POINTING else action,
                    driving,
                    modifier = Modifier.graphicsLayer {
                        // Placement runs after the window's relayout, so this is where the window really is.
                        view.getLocationOnScreen(origin)
                        val centre = position.value - fingertip * reach.value
                        translationX = centre.x - origin[0] - size.width / 2f
                        translationY = centre.y - origin[1] - size.height / 2f
                    },
                )
            }
        }
    }

    /** A soft band of colour round the screen edges, turning slowly and breathing; draw-only, no recomposition. */
    @Composable
    private fun EdgeGlow(fade: Float) {
        val motion = rememberInfiniteTransition(label = "edgeGlow")
        val turn by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(GLOW_TURN_MS, easing = LinearEasing)), label = "turn")
        val breath by motion.animateFloat(GLOW_BREATH_LOW, 1f, infiniteRepeatable(tween(GLOW_BREATH_MS), RepeatMode.Reverse), label = "breath")
        Spacer(
            Modifier.fillMaxSize().graphicsLayer { alpha = fade }.drawWithCache {
                val shader = SweepGradient(size.width / 2f, size.height / 2f, GLOW_COLORS, null)
                val brush = ShaderBrush(shader)
                val matrix = Matrix()
                val corner = GLOW_CORNER_DP * density
                onDrawBehind {
                    matrix.setRotate(turn, size.width / 2f, size.height / 2f)
                    shader.setLocalMatrix(matrix)
                    GLOW_BANDS.forEach { (widthDp, bandAlpha) ->
                        val width = widthDp * density
                        drawRoundRect(
                            brush = brush,
                            topLeft = Offset(width / 2f, width / 2f),
                            size = Size(size.width - width, size.height - width),
                            cornerRadius = CornerRadius(corner),
                            style = Stroke(width),
                            alpha = bandAlpha * breath,
                        )
                    }
                }
            },
        )
    }

    @Composable
    private fun Ripple(ripple: StageRipple) {
        val progress = remember(ripple) { Animatable(0f) }
        LaunchedEffect(ripple) {
            progress.animateTo(1f, tween(RIPPLE_MS))
            if (this@AndroidUseStage.ripple == ripple) this@AndroidUseStage.ripple = null
        }
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                view.getLocationOnScreen(origin)
                val centre = ripple.centre - Offset(origin[0].toFloat(), origin[1].toFloat())
                val p = progress.value
                val radius = (RIPPLE_START_DP + (RIPPLE_END_DP - RIPPLE_START_DP) * p) * density
                drawCircle(RIPPLE_COLOR, radius, centre, alpha = (1f - p) * RIPPLE_RING_ALPHA, style = Stroke(RIPPLE_RING_DP * density))
                drawCircle(RIPPLE_COLOR, RIPPLE_DOT_DP * density, centre, alpha = (1f - p) * RIPPLE_DOT_ALPHA)
            },
        )
    }

    private fun travelMillis(from: Offset, to: Offset): Int =
        (POINT_BASE_MS + hypot(to.x - from.x, to.y - from.y) / unitPx * POINT_MS_PER_DP).toInt().coerceAtMost(POINT_MAX_MS)

    /** The whole display, bars included, so the glow runs round the dock and the status bar too. */
    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            @Suppress("DEPRECATION")
            android.graphics.Point().also { windowManager.defaultDisplay.getRealSize(it) }.let { it.x to it.y }
        }

    private fun addWindow() {
        val trusted = params.trustOverlay(context)
        // API 31+ blocks every touch, injected gestures too, under an untrusted window that is not an ordinary overlay,
        // whatever its alpha (2026-10-16: each swipe under the TYPE_DRAG stage was dropped as untrusted, taps
        // got through by ACTION_CLICK). Trusted, it lets them pass; otherwise the ordinary overlay and its alpha do.
        if (context.grantsSystemWindows() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || trusted)) {
            val fallback = params.type
            params.type = TYPE_DRAG
            if (runCatching { windowManager.addView(view, params) }.isSuccess) return
            params.type = fallback
        }
        windowManager.addView(view, params)
    }

    private fun update() {
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun windowAlpha(alpha: Float) {
        if (params.alpha == alpha) return
        params.alpha = alpha
        update()
    }
}

private class StageRipple(val centre: Offset, val id: Long)

/** WindowManager.LayoutParams.TYPE_DRAG (FIRST_SYSTEM_WINDOW + 16), hidden in the public SDK; needs INTERNAL_SYSTEM_WINDOW. */
private const val TYPE_DRAG = 2016

private const val STAGE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

/** Sage, mist, glow, heather and back: the assistant's calm palette, so it reads as 助手正在操作, not as an alert. */
private val GLOW_COLORS = intArrayOf(
    Color(0xFF8CC7A5).toArgb(),
    Color(0xFF7FB3C9).toArgb(),
    Color(0xFFF5C26B).toArgb(),
    Color(0xFFB79AC8).toArgb(),
    Color(0xFF8CC7A5).toArgb(),
)

/** Bands sharing the screen edge, wide and faint to narrow and bright, so the light fades inward. */
private val GLOW_BANDS = listOf(26f to 0.14f, 14f to 0.26f, 6f to 0.5f, 2.5f to 0.9f)
private const val GLOW_CORNER_DP = 18f
private const val GLOW_TURN_MS = 4_800
private const val GLOW_BREATH_MS = 1_600
private const val GLOW_BREATH_LOW = 0.7f
private const val GLOW_FADE_MS = 420
/** After the hidden frames, a margin for the compositor to latch them before the screenshot. */
private const val CAPTURE_SETTLE_MS = 48L
private const val REACH_MS = 220
private const val POINT_BASE_MS = 160f
private const val POINT_MS_PER_DP = 0.35f
private const val POINT_MAX_MS = 420
private const val RIPPLE_MS = 420
private const val RIPPLE_START_DP = 6f
private const val RIPPLE_END_DP = 34f
private const val RIPPLE_RING_DP = 3f
private const val RIPPLE_DOT_DP = 7f
private const val RIPPLE_RING_ALPHA = 0.9f
private const val RIPPLE_DOT_ALPHA = 0.55f
private val RIPPLE_COLOR = Color(0xFFF5C26B)
private val POINT_EASING = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)
