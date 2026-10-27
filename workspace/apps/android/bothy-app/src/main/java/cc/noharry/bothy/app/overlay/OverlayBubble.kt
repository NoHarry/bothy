package cc.noharry.bothy.app.overlay
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The overlay folded down to 小博 alone: a small window the user can drag anywhere above the dock and tap to
 * open the card. It draws the card's [MascotPose], so 小博 passes between the bubble, the flight and the card's
 * header without a jump. Positions are offsets of the bubble's centre from the screen centre, as for the card
 * window, so all the overlay's windows share one frame of reference.
 */
@SuppressLint("ClickableViewAccessibility")
internal class OverlayBubble(
    context: Context,
    private val windowManager: WindowManager,
    private val pose: MascotPose,
    windowType: Int,
    private val scale: Float,
    private val listener: Listener,
) {
    interface Listener {
        fun onBubbleTap()
        fun onBubbleLongPress()
        fun onBubbleDropped(x: Int, y: Int)

        /** A drag started (true) or ended (false); what sits beside the bubble steps aside meanwhile. */
        fun onBubbleDragging(dragging: Boolean) {}

        /** Where the bubble's centre may go, as offsets from the screen centre. */
        fun bubbleBounds(sizePx: Int): Rect
    }

    /** The act while resting; being held overrides it. */
    var action by mutableStateOf(MascotAction.IDLE)

    /** Whether the bubble plays the acts on the shared pose; at most one view does at a time. */
    var driving by mutableStateOf(false)

    /** A delegation under way or waiting, drawn as a ring around 小博; null draws none. */
    var ring by mutableStateOf<OverlayTaskSignal?>(null)
    private var held by mutableStateOf(false)
    private var present by mutableStateOf(false)

    private val owner = OverlayComposeOwner()
    private val view = ComposeView(context)
    /** The bubble window's side: 小博's box plus room for hops. */
    val sizePx = ((MASCOT_BOX + BUBBLE_MARGIN * 2).value * context.resources.displayMetrics.density * scale).roundToInt()
    private val params = WindowManager.LayoutParams(
        sizePx,
        sizePx,
        windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.CENTER
        // Untouchable while 小博 takes off from it, under the flight window.
        trustOverlay(context)
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var longPressed = false
    private val longPress = Runnable {
        longPressed = true
        listener.onBubbleLongPress()
    }

    val shown: Boolean get() = view.visibility == View.VISIBLE
    val x: Int get() = params.x
    val y: Int get() = params.y

    init {
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
                // Hidden, the bubble draws nothing, so the pose moving elsewhere costs it no frames.
                if (present) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        ring?.takeIf { !held }?.let { OverlayTaskRing(it, MASCOT_BOX + RING_ROOM) }
                        Mascot(pose, if (held) MascotAction.HELD else action, driving)
                    }
                }
            }
        }
        owner.attachTo(view)
        view.visibility = View.GONE
        view.setOnTouchListener { _, event -> onTouch(event) }
        view.setOnClickListener { listener.onBubbleTap() }
        windowManager.addView(view, params)
    }

    /**
     * Another app shows the assistant ([AssistantSurface]): the bubble keeps its place and its acts but is not drawn
     * and takes no touches, until it is given back.
     */
    var suppressed = false
        set(value) {
            field = value
            view.visibility = if (present && !value) View.VISIBLE else View.GONE
            setTouchable(present && !value)
        }

    /** Shows the bubble at [x], [y]; [onShown] runs once it is on screen. */
    fun showAt(x: Int, y: Int, touchable: Boolean, onShown: () -> Unit = {}) {
        params.x = x
        params.y = y
        setTouchable(touchable && !suppressed)
        present = true
        view.visibility = if (suppressed) View.GONE else View.VISIBLE
        update()
        view.afterNextFrame(onShown)
    }

    /** The bubble's centre in screen pixels; valid while it is shown. */
    fun centreOnScreen(): Offset = view.centreOnScreen()

    /** Where the bubble's centre will be on screen at offsets [x], [y], given the overlay frame's centre. */
    fun centreAt(x: Int, y: Int, frameCentre: Offset): Offset = frameCentre + Offset(x.toFloat(), y.toFloat())

    fun hide() {
        driving = false
        held = false
        present = false
        view.visibility = View.GONE
        setTouchable(false)
        update()
    }

    fun setTouchable(touchable: Boolean) {
        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        update()
    }

    /** Keeps the bubble inside its bounds, e.g. after the screen turns. */
    fun clamp() {
        moveTo(params.x, params.y)
    }

    fun destroy() {
        view.removeCallbacks(longPress)
        runCatching { windowManager.removeView(view) }
        owner.destroy()
    }

    /** Raw screen coordinates: the window moves under the finger, so view-local ones would chase themselves. */
    private fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = params.x
                startY = params.y
                dragging = false
                longPressed = false
                view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && hypot(dx, dy) > touchSlop) {
                    dragging = true
                    held = true
                    view.removeCallbacks(longPress)
                    listener.onBubbleDragging(true)
                }
                if (dragging) moveTo(startX + dx.roundToInt(), startY + dy.roundToInt())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.removeCallbacks(longPress)
                if (dragging) {
                    held = false
                    pose.react(MascotReaction.LAND)
                    listener.onBubbleDropped(params.x, params.y)
                    listener.onBubbleDragging(false)
                } else if (event.actionMasked == MotionEvent.ACTION_UP && !longPressed) {
                    view.performClick()
                }
            }
        }
        return true
    }

    private fun moveTo(x: Int, y: Int) {
        val bounds = listener.bubbleBounds(sizePx)
        params.x = x.coerceIn(bounds.left, bounds.right)
        params.y = y.coerceIn(bounds.top, bounds.bottom)
        update()
    }

    private fun update() {
        runCatching { windowManager.updateViewLayout(view, params) }
    }
}

/** Room around 小博 inside the bubble window for hops and squashes; it also widens the touch target. */
private val BUBBLE_MARGIN = 12.dp
/** The task ring sits just outside 小博, inside the bubble's margin. */
private val RING_ROOM = 14.dp
