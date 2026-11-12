package cc.noharry.bothy.app.phoneassist

import cc.noharry.agent.appserver.protocol.isTurnStart
import android.content.Context
import android.content.res.Resources
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.overlay.OverlayComposeOwner
import cc.noharry.bothy.app.overlay.OverlayTaskSignal
import cc.noharry.bothy.app.overlay.OverlayTaskTone
import cc.noharry.bothy.app.overlay.overlayFoldedSignal
import cc.noharry.bothy.app.overlay.overlayWindowType
import cc.noharry.bothy.app.overlay.stepKind
import cc.noharry.bothy.protocol.view.currentTurnItems
import kotlin.math.roundToInt

/**
 * What the phone's capsule shows while the sheet is down (owner 2026-11-05: 顶部居中的胶囊): the run going on, else a
 * delegation or a subagent ([overlayFoldedSignal]); null when nothing is. While 小博 operates the screen ([usingScreen])
 * only the run shows.
 */
internal fun phoneSignal(resources: Resources, state: ServerState, usingScreen: Boolean): OverlayTaskSignal? =
    phoneRunSignal(resources, state, usingScreen) ?: overlayFoldedSignal(resources, state.delegations).takeIf { !usingScreen }

/**
 * The run going on: the kind of work and how many steps so far ([usingScreen]: it is operating the screen), over the
 * request it works on. Null when no run goes on.
 */
internal fun phoneRunSignal(resources: Resources, state: ServerState, usingScreen: Boolean): OverlayTaskSignal? {
    if (!state.isRunning) return null
    val calls = state.items.currentTurnItems().filterIsInstance<ConversationItem.ToolCallItem>().filter { it.toolName != FINISH_TOOL }
    val running = calls.lastOrNull { it.status == ToolCallStatus.RUNNING }
    val phrase = resources.getString(
        when {
            usingScreen -> R.string.overlay_capsule_screen
            running != null -> stepKind(running.toolName).phrase
            else -> R.string.overlay_step_working
        },
    )
    val title = if (calls.isEmpty()) phrase else resources.getString(R.string.overlay_capsule_step, phrase, calls.size)
    val asked = state.items.lastOrNull { it.isTurnStart } as? ConversationItem.TurnHeader
    return OverlayTaskSignal(OverlayTaskTone.WORKING, 0, 0, title, asked?.preview.orEmpty(), more = 0, key = "run|${calls.size}|$usingScreen")
}

/**
 * The capsule: a window of fixed size at the top of the screen, centred under the status bar. Tapping it calls [onTap].
 * While 小博 operates the screen it does not show: the edge light's window says the same ([PhoneUseLight]).
 */
internal class PhoneCapsule(context: Context, private val windowManager: WindowManager, onTap: () -> Unit) {
    private var signal by mutableStateOf<OverlayTaskSignal?>(null)
    private val owner = OverlayComposeOwner()
    private val view = ComposeView(context)
    private val density = context.resources.displayMetrics.density
    private val params = WindowManager.LayoutParams(
        minOf((CAPSULE_WIDTH * density).roundToInt(), context.resources.displayMetrics.widthPixels - (SIDE_MARGIN * density).roundToInt() * 2),
        (CAPSULE_HEIGHT * density).roundToInt(),
        overlayWindowType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        // An overlay's y counts from under the status bar already.
        y = (TOP_GAP * density).roundToInt()
    }

    init {
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent { signal?.let { CapsuleContent(it, tappable = true) } }
        owner.attachTo(view)
        view.visibility = View.GONE
        view.setOnClickListener { onTap() }
        windowManager.addView(view, params)
    }

    /** Shows [next], or hides the capsule when null. */
    fun show(next: OverlayTaskSignal?) {
        signal = next
        view.visibility = if (next == null) View.GONE else View.VISIBLE
    }

    val shown: Boolean get() = view.visibility == View.VISIBLE

    /** Runs [then] once the capsule's hiding has reached the screen (for a screenshot). */
    fun afterFrame(then: () -> Unit) = view.postOnAnimation { view.postOnAnimation(then) }

    fun destroy() {
        runCatching { windowManager.removeView(view) }
        owner.destroy()
    }
}

/** The capsule's face where it takes no taps: as wide as the capsule's window, under the status bar's gap. */
@Composable
internal fun CapsuleFace(signal: OverlayTaskSignal, modifier: Modifier = Modifier) {
    Box(modifier.padding(top = TOP_GAP.dp, start = SIDE_MARGIN.dp, end = SIDE_MARGIN.dp).widthIn(max = CAPSULE_WIDTH.dp).height(CAPSULE_HEIGHT.dp)) {
        CapsuleContent(signal, tappable = false)
    }
}

/**
 * The capsule's face, ink like a live activity: a breathing dot while working, a tick on a result, two lines, and an
 * arrow when [tappable].
 */
@Composable
private fun CapsuleContent(signal: OverlayTaskSignal, tappable: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().clip(CircleShape).background(INK).padding(start = 18.dp, end = 12.dp),
    ) {
        when (signal.tone) {
            OverlayTaskTone.WORKING -> BreathingDot(MINT)
            OverlayTaskTone.STALLED -> BreathingDot(AMBER)
            OverlayTaskTone.RESULT -> Box(Modifier.size(22.dp).clip(CircleShape).background(MINT), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = INK, modifier = Modifier.size(15.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(signal.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (signal.detail.isNotBlank()) {
                Text(signal.detail, color = Color.White.copy(alpha = SOFT), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (signal.more > 0) Text("+${signal.more}", color = Color.White.copy(alpha = SOFT), fontSize = 12.sp)
        if (tappable) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.White.copy(alpha = SOFT), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun BreathingDot(color: Color) {
    val breath = rememberInfiniteTransition(label = "capsule-dot")
    val alpha by breath.animateFloat(DOT_DIM, 1f, infiniteRepeatable(tween(DOT_BREATH_MS), RepeatMode.Reverse), label = "alpha")
    Box(Modifier.size(10.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
}

private const val FINISH_TOOL = "finish"
private const val CAPSULE_WIDTH = 340
private const val CAPSULE_HEIGHT = 56
private const val SIDE_MARGIN = 16
private const val TOP_GAP = 6
private const val SOFT = 0.7f
private const val DOT_DIM = 0.35f
private const val DOT_BREATH_MS = 900
private val INK = Color(0xEB1B1C19)
private val MINT = Color(0xFF6FD6A4)
private val AMBER = Color(0xFFFFB454)
