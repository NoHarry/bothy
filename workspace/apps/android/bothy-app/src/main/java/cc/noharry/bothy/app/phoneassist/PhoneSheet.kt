package cc.noharry.bothy.app.phoneassist

import android.content.Context
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.overlay.OverlayConnection
import cc.noharry.bothy.app.overlay.OverlayComposeOwner
import cc.noharry.bothy.app.overlay.overlayWindowType
import cc.noharry.bothy.app.phone.PhoneTheme
import cc.noharry.bothy.protocol.view.OverlayHud
import kotlin.math.roundToInt

/** What the phone's sheet shows; [PhoneAssistantService] writes it on the main thread. */
internal class PhoneSheetModel {
    var state by mutableStateOf(ServerState(connected = false))
    var hud by mutableStateOf<OverlayHud?>(null)

    /** The input box is open: typing instead of speaking, or adding to the task going on. */
    var typing by mutableStateOf(false)

    /** Woken just now: the sheet listens, whatever it showed before, until the next request starts. */
    var fresh by mutableStateOf(false)
}

/** What the sheet asks of the service. */
internal class PhoneSheetActions(
    val fold: () -> Unit,
    val close: () -> Unit,
    /** A new request, typed. */
    val send: (String) -> Unit,
    /** Added to the task going on, which takes it in when it can. */
    val steer: (String) -> Unit,
    /** Stops the task going on and asks this instead. */
    val interruptWith: (String) -> Unit,
    val stop: () -> Unit,
    val stopListening: () -> Unit,
    val talk: () -> Unit,
    val type: (Boolean) -> Unit,
    /** A pick or a panel's values, as the person would say them. */
    val reply: (String) -> Unit,
    val approve: (String) -> Unit,
    val deny: (String) -> Unit,
    val allowSession: (String) -> Unit,
    val openApp: () -> Unit,
    val copy: (String) -> Unit,
)

/**
 * The sheet's window: the whole screen while the sheet is up, dimmed above it (a tap there folds the sheet), and
 * untouchable and gone once it has slid away. Focusable only while its input box is open, so the keyboard comes to it
 * then and stays with the app below otherwise.
 */
internal class PhoneSheet(context: Context, private val windowManager: WindowManager, model: PhoneSheetModel, actions: PhoneSheetActions) {
    private var up by mutableStateOf(false)
    private val owner = OverlayComposeOwner()
    private val view = ComposeView(context)
    private val params = sheetWindowParams()

    init {
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent { SheetRoot(up, model, actions, onGone = ::gone) }
        owner.attachTo(view)
        view.visibility = View.GONE
        windowManager.addView(view, params)
    }

    val shown: Boolean get() = up

    fun show() {
        view.visibility = View.VISIBLE
        setFlag(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, on = false)
        up = true
    }

    /** Slides the sheet away; the window takes no touches at once and goes when the slide ends. */
    fun hide() {
        up = false
        setFlag(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, on = true)
    }

    /** Gone at once, without the slide: 小博 is about to operate the screen, and a window over it would stop its taps. */
    fun hideNow() = hide().also { view.visibility = View.GONE }

    fun focusable(focusable: Boolean) = setFlag(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, on = !focusable)

    private fun gone() {
        if (!up) view.visibility = View.GONE
    }

    private fun setFlag(flag: Int, on: Boolean) {
        val flags = if (on) params.flags or flag else params.flags and flag.inv()
        if (flags == params.flags) return
        params.flags = flags
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    fun destroy() {
        runCatching { windowManager.removeView(view) }
        owner.destroy()
    }
}

/** The whole screen, untouchable and unfocusable until the sheet comes up; the keyboard resizes it. */
private fun sheetWindowParams() = WindowManager.LayoutParams(
    WindowManager.LayoutParams.MATCH_PARENT,
    WindowManager.LayoutParams.MATCH_PARENT,
    overlayWindowType(),
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
    PixelFormat.TRANSLUCENT,
).apply { softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }

/** A drawn page in the answer reads itself through the connection ([LocalServer]). */
@Composable
private fun SheetRoot(up: Boolean, model: PhoneSheetModel, actions: PhoneSheetActions, onGone: () -> Unit) {
    CompositionLocalProvider(LocalServer provides OverlayConnection.access(model.state)) {
        PhoneTheme { SheetFrame(up, model, actions, onGone) }
    }
}

@Composable
private fun SheetFrame(up: Boolean, model: PhoneSheetModel, actions: PhoneSheetActions, onGone: () -> Unit) {
    val sheet = remember { MutableTransitionState(false) }
    sheet.targetState = up
    if (sheet.isIdle && !sheet.currentState) LaunchedEffect(Unit) { onGone() }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(up, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = SCRIM)).clickable(interactionSource = null, indication = null, onClick = actions.fold))
        }
        AnimatedVisibility(
            sheet,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            SheetSurface(model, actions)
        }
    }
}

/** The sheet itself: paper with a handle, which a pull down folds. */
@Composable
private fun SheetSurface(model: PhoneSheetModel, actions: PhoneSheetActions) {
    val colors = ConsoleColors
    var pulled by remember { mutableFloatStateOf(0f) }
    val foldPx = with(LocalDensity.current) { FOLD_PULL.toPx() }
    val drag = rememberDraggableState { delta -> pulled = (pulled + delta).coerceAtLeast(0f) }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * MAX_SHARE
    Surface(
        shape = RoundedCornerShape(topStart = CORNER, topEnd = CORNER),
        color = colors.bg,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, pulled.roundToInt()) }
            .imePadding()
            .heightIn(max = maxHeight)
            .draggable(drag, Orientation.Vertical, onDragStopped = {
                if (pulled > foldPx) actions.fold()
                pulled = 0f
            }),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp)) {
            Box(Modifier.fillMaxWidth().padding(bottom = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).background(colors.border, CircleShape))
            }
            SheetBody(model, actions)
            Box(Modifier.height(4.dp))
        }
    }
}

private val CORNER = 28.dp
private val FOLD_PULL = 96.dp
private const val SCRIM = 0.32f
private const val MAX_SHARE = 0.86f
