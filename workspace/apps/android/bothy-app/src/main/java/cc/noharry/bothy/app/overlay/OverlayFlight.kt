package cc.noharry.bothy.app.overlay
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 小博 travelling between the bubble's spot and its seat in the card. The flight is a layer translation inside a
 * window that spans the whole path and holds still meanwhile, so it draws at frame rate; moving the bubble's own
 * window instead costs a WindowManager relayout per frame, and on the bench (API 30) those land off the frame clock
 * and stutter. The window lives as long as the overlay, 1×1 and empty between flights, so a take-off never waits for
 * a window to be created.
 *
 * Path points are screen pixels, and 小博 is placed against where the window really is on screen, so a window the
 * system places a little differently (a status bar, the screen edge) never makes 小博 jump at a hand-over.
 */
internal class OverlayFlight(
    context: Context,
    private val windowManager: WindowManager,
    private val pose: MascotPose,
    windowType: Int,
    private val scale: Float,
) {
    /** The act before take-off and after landing; in the air 小博 flies. */
    var action by mutableStateOf(MascotAction.IDLE)
    var driving by mutableStateOf(false)
    private var route by mutableStateOf<FlightPath?>(null)
    private var airborne by mutableStateOf(false)
    private var takeOff by mutableStateOf<Takeoff?>(null)
    private val density = context.resources.displayMetrics.density
    private val owner = OverlayComposeOwner()
    private val view = ComposeView(context)
    private val origin = IntArray(2)
    private val params = WindowManager.LayoutParams(
        1,
        1,
        windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.CENTER
        // Spans the card while it unfolds or folds: untrusted, the two together would block taps beneath them.
        trustOverlay(context)
    }

    init {
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
                route?.let { path -> Flight(path) }
            }
        }
        owner.attachTo(view)
        windowManager.addView(view, params)
    }

    @Composable
    private fun Flight(path: FlightPath) {
        val progress = remember(path) { Animatable(0f) }
        val flight = takeOff
        LaunchedEffect(flight) {
            if (flight == null) return@LaunchedEffect
            progress.animateTo(1f, tween(path.millis, easing = FLIGHT_EASING))
            airborne = false
            pose.react(MascotReaction.LAND)
            flight.onArrived()
        }
        Box(Modifier.fillMaxSize()) {
            Mascot(
                pose,
                if (airborne) MascotAction.FLYING else action,
                driving,
                aim = path.heading,
                modifier = Modifier.graphicsLayer {
                    // Placement runs after the window's relayout, so this is where the window really is.
                    view.getLocationOnScreen(origin)
                    val point = path.at(progress.value)
                    translationX = point.x - origin[0] - size.width / 2f
                    translationY = point.y - origin[1] - size.height / 2f
                },
            )
        }
    }

    /**
     * Shows 小博 at [from] (screen pixels) in a window spanning the way to [to]; [onShown] runs once that is on
     * screen. [frame] is the area overlay windows are laid out in, in screen pixels; centre offsets count from its
     * centre, and the window stays inside it so the system never shifts it.
     */
    fun open(from: Offset, to: Offset, frame: Rect, pace: Float = 1f, onShown: () -> Unit) {
        val path = FlightPath.between(from, to, margin = MASCOT_BOX.value * density * scale, frame = frame, pace = pace)
        params.width = path.width
        params.height = path.height
        params.x = (path.centre.x - frame.center.x).roundToInt()
        params.y = (path.centre.y - frame.center.y).roundToInt()
        update()
        airborne = false
        takeOff = null
        route = path
        view.afterNextFrame(onShown)
    }

    /** Flies from the start to the end of the path; this window should be driving the pose by now. */
    fun fly(onArrived: () -> Unit) {
        val path = route ?: return
        airborne = true
        takeOff = Takeoff(path.millis, onArrived)
    }

    /** Empties the window and folds it to 1×1 until the next flight. */
    fun close() {
        driving = false
        airborne = false
        takeOff = null
        route = null
        params.width = 1
        params.height = 1
        update()
    }

    fun destroy() {
        runCatching { windowManager.removeView(view) }
        owner.destroy()
    }

    private fun update() {
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private class Takeoff(val millis: Int, val onArrived: () -> Unit)
}

/**
 * A gentle arc in screen pixels from [from] to [to], bowed upward like a hop, with the window rectangle that holds
 * it: [centre] and [width]×[height], kept inside the overlay frame. [at] gives 小博's centre for a progress 0…1.
 */
internal class FlightPath private constructor(
    private val from: Offset,
    private val control: Offset,
    private val to: Offset,
    val centre: Offset,
    val width: Int,
    val height: Int,
    val heading: Offset,
    val millis: Int,
) {
    fun at(t: Float): Offset {
        val u = 1f - t
        return from * (u * u) + control * (2 * u * t) + to * (t * t)
    }

    companion object {
        /** [pace] scales the duration: below 1 is quicker (a voice wake answers at once). */
        fun between(from: Offset, to: Offset, margin: Float, frame: Rect, pace: Float = 1f): FlightPath {
            val delta = to - from
            val distance = hypot(delta.x, delta.y).coerceAtLeast(1f)
            // The perpendicular whose y points up.
            val bow = if (delta.x >= 0f) Offset(delta.y, -delta.x) / distance else Offset(-delta.y, delta.x) / distance
            val control = (from + to) / 2f + bow * (distance * FLIGHT_ARC)
            val left = maxOf(minOf(from.x, to.x, control.x) - margin, frame.left)
            val right = minOf(maxOf(from.x, to.x, control.x) + margin, frame.right)
            val top = maxOf(minOf(from.y, to.y, control.y) - margin, frame.top)
            val bottom = minOf(maxOf(from.y, to.y, control.y) + margin, frame.bottom)
            return FlightPath(
                from = from,
                control = control,
                to = to,
                centre = Offset((left + right) / 2f, (top + bottom) / 2f),
                width = (right - left).roundToInt().coerceAtLeast(1),
                height = (bottom - top).roundToInt().coerceAtLeast(1),
                heading = delta / distance,
                millis = ((FLIGHT_BASE_MS + distance * FLIGHT_MS_PER_PX).coerceAtMost(FLIGHT_MAX_MS.toFloat()) * pace).roundToInt(),
            )
        }
    }
}

/** This view's centre in screen pixels, as last laid out. */
internal fun View.centreOnScreen(): Offset {
    val location = IntArray(2)
    getLocationOnScreen(location)
    return Offset(location[0] + width / 2f, location[1] + height / 2f)
}

/**
 * Runs [action] on the main thread once this view's next frame is on screen (API 29+), or two frames on, with a
 * time limit in case no frame comes; so a hand-over never shows a gap where no window draws 小博.
 */
internal fun View.afterNextFrame(action: () -> Unit) {
    var done = false
    val once = Runnable {
        if (!done) {
            done = true
            action()
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        viewTreeObserver.registerFrameCommitCallback { post(once) }
    } else {
        postOnAnimation { postOnAnimation(once) }
    }
    invalidate()
    postDelayed(once, FRAME_WAIT_LIMIT_MS)
}

private const val FLIGHT_ARC = 0.18f
private const val FLIGHT_BASE_MS = 380f
private const val FLIGHT_MS_PER_PX = 0.16f
private const val FLIGHT_MAX_MS = 680
private const val FRAME_WAIT_LIMIT_MS = 250L
private val FLIGHT_EASING = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)
