package cc.noharry.bothy.sample

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** How a delegation shows on 小博 while the card is folded: under way, not started, or a result waiting. */
internal enum class OverlayTaskTone { WORKING, STALLED, RESULT }

/**
 * The delegation 小博 shows while the console is closed and the card folded (owner 2026-10-12: progress belongs on
 * 小博 over the map, not on a page): the ring around it and the capsule beside it. [key] changes with anything worth
 * a glance (a new task, a step done, a stall, a result), and the capsule opens again for it.
 */
internal data class OverlayTaskSignal(
    val tone: OverlayTaskTone,
    val stepsDone: Int,
    val stepsTotal: Int,
    val title: String,
    val detail: String,
    val more: Int,
    val key: String,
)

/**
 * The most pressing delegation among [views] at [now]: a result waiting to be seen, then one that has not started,
 * then the latest under way; null when none is.
 */
internal fun overlayTaskSignal(views: List<DebugDelegationView>, now: Long): OverlayTaskSignal? {
    val live = views.filter { !it.stage.settled || it.stage == DebugDelegationStage.REPORTING }
    val view = live.lastOrNull { it.stage == DebugDelegationStage.REPORTING }
        ?: live.lastOrNull { it.stage == DebugDelegationStage.STALLED }
        ?: live.lastOrNull()
        ?: return null
    val done = view.steps.count { it.status == "done" }
    val elapsed = delegationElapsed(now, view.startedAtEpochMillis)
    val (tone, title, detail) = when {
        view.stage == DebugDelegationStage.REPORTING -> Triple(OverlayTaskTone.RESULT, "${view.agentLabel} 有新结果", "点一下查看")
        view.stage == DebugDelegationStage.STALLED ->
            Triple(OverlayTaskTone.STALLED, "${view.agentLabel} · 还没开始", "已送达 $elapsed，可能在等审批")
        view.steps.isNotEmpty() -> Triple(
            OverlayTaskTone.WORKING,
            "${view.agentLabel} · $done/${view.steps.size} 步",
            view.steps.firstOrNull { it.status == "doing" }?.title ?: view.statusText.ifBlank { view.request },
        )
        else -> Triple(
            OverlayTaskTone.WORKING,
            "${view.agentLabel} · ${view.stage.label} $elapsed",
            view.statusText.takeIf { it.isNotBlank() && view.stage == DebugDelegationStage.WORKING } ?: "「${view.request}」",
        )
    }
    return OverlayTaskSignal(tone, done, view.steps.size, title, detail, live.size - 1, "${view.taskId}|$tone|$done|${view.stage}")
}

/**
 * The ring around 小博: one segment per reported step, done ones lit; a turning arc while no steps are known; amber
 * when the task has not started; green with a dot when its result waits.
 */
@Composable
internal fun OverlayTaskRing(signal: OverlayTaskSignal, side: Dp) {
    val spin = rememberInfiniteTransition(label = "task-ring")
    val turn by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing), RepeatMode.Restart), label = "turn")
    Canvas(Modifier.size(side)) {
        val stroke = RING_STROKE.toPx()
        val inset = stroke / 2 + RING_GAP.toPx()
        val box = Size(size.width - inset * 2, size.height - inset * 2)
        val origin = Offset(inset, inset)
        val style = Stroke(width = stroke, cap = StrokeCap.Round)
        when {
            signal.tone == OverlayTaskTone.RESULT -> {
                drawArc(RESULT_COLOR, 0f, 360f, false, origin, box, style = style)
                drawCircle(RESULT_COLOR, radius = stroke * 1.6f, center = Offset(size.width * 0.85f, size.height * 0.15f))
            }
            signal.tone == OverlayTaskTone.STALLED -> drawArc(STALLED_COLOR, 0f, 360f, false, origin, box, style = style)
            signal.stepsTotal > 0 -> {
                val sweep = 360f / signal.stepsTotal
                repeat(signal.stepsTotal) { index ->
                    val color = if (index < signal.stepsDone) OverlayPalette.glow else OverlayPalette.faint
                    drawArc(color, -90f + index * sweep + SEGMENT_GAP_DEG / 2, sweep - SEGMENT_GAP_DEG, false, origin, box, style = style)
                }
            }
            else -> {
                drawArc(OverlayPalette.faint, 0f, 360f, false, origin, box, style = Stroke(width = stroke))
                drawArc(OverlayPalette.glow, turn - 90f, SPIN_SWEEP_DEG, false, origin, box, style = style)
            }
        }
    }
}

/** The capsule's two lines in the card's glass, with a dot when not started, a count of other tasks, and › on a result. */
@Composable
private fun OverlayTaskCapsuleContent(signal: OverlayTaskSignal) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxSize()
            .clip(CapsuleShape)
            .background(OverlayPalette.glass)
            .border(1.dp, OverlayPalette.edge, CapsuleShape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (signal.tone == OverlayTaskTone.STALLED) Box(Modifier.size(8.dp).clip(CircleShape).background(STALLED_COLOR))
        Column(Modifier.weight(1f)) {
            Text(signal.title, color = OverlayPalette.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(signal.detail, color = OverlayPalette.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (signal.more > 0) Text("+${signal.more}", color = OverlayPalette.faint, fontSize = 13.sp)
        if (signal.tone == OverlayTaskTone.RESULT) Text("›", color = OverlayPalette.secondary, fontSize = 22.sp)
    }
}

/**
 * The capsule beside the folded 小博: a small window of fixed size on the screen's inner side of the bubble, so its
 * place follows from the bubble's without measuring. Tapping it opens the card. Positions are offsets from the
 * screen centre, like the bubble's.
 */
internal class DebugOverlayTaskCapsule(
    context: Context,
    private val windowManager: WindowManager,
    windowType: Int,
    private val scale: Float,
    onTap: () -> Unit,
) {
    var signal by mutableStateOf<OverlayTaskSignal?>(null)
    private val density = context.resources.displayMetrics.density
    private val widthPx = (CAPSULE_WIDTH.value * density * scale).roundToInt()
    private val heightPx = (CAPSULE_HEIGHT.value * density * scale).roundToInt()
    private val gapPx = (CAPSULE_GAP.value * density * scale).roundToInt()
    private val owner = DebugOverlayComposeOwner()
    private val view = ComposeView(context)
    private val params = WindowManager.LayoutParams(
        widthPx, heightPx, windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.CENTER }

    init {
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
                signal?.let { OverlayTaskCapsuleContent(it) }
            }
        }
        owner.attachTo(view)
        view.visibility = View.GONE
        view.setOnClickListener { onTap() }
        windowManager.addView(view, params)
    }

    /** Shows the capsule beside a bubble of [bubbleSizePx] centred at [bubbleX], [bubbleY]; inward of it on screen. */
    fun showBeside(bubbleX: Int, bubbleY: Int, bubbleSizePx: Int) {
        val offset = bubbleSizePx / 2 + gapPx + widthPx / 2
        params.x = if (bubbleX > 0) bubbleX - offset else bubbleX + offset
        params.y = bubbleY
        view.visibility = View.VISIBLE
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    fun hide() {
        if (view.visibility == View.GONE) return
        view.visibility = View.GONE
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    fun destroy() {
        runCatching { windowManager.removeView(view) }
        owner.destroy()
    }
}

/**
 * The folded 小博's ring and the capsule beside it, for the most pressing delegation (owner 2026-10-12): the capsule
 * opens for [CAPSULE_SHOWN_MS] on each change worth a glance, then only the ring stays; a waiting result keeps it
 * open. [current] gives the signal and whether 小博 is folded and free. Main thread only.
 */
internal class OverlayTaskGlance(
    private val bubble: DebugOverlayBubble,
    private val capsule: DebugOverlayTaskCapsule,
    private val host: View,
    private val clock: () -> Long = System::currentTimeMillis,
    private val current: () -> Pair<OverlayTaskSignal?, Boolean>,
) {
    private var key: String? = null
    private var until = 0L
    private var held = false
    private val timeout = Runnable { sync() }

    fun dragging(dragging: Boolean) {
        held = dragging
        sync()
    }

    fun sync() {
        val (signal, folded) = current()
        val now = clock()
        bubble.ring = signal
        // The glance starts once 小博 can show it: a change made while the card was open is shown when it folds.
        if (signal != null && folded && signal.key != key) {
            key = signal.key
            until = now + CAPSULE_SHOWN_MS
            host.removeCallbacks(timeout)
            host.postDelayed(timeout, CAPSULE_SHOWN_MS)
        }
        val glance = signal != null && (signal.tone == OverlayTaskTone.RESULT || now < until)
        if (glance && folded && !held) {
            capsule.signal = signal
            capsule.showBeside(bubble.x, bubble.y, bubble.sizePx)
        } else {
            capsule.hide()
        }
    }

    fun destroy() {
        host.removeCallbacks(timeout)
        capsule.destroy()
    }
}

private val CapsuleShape = RoundedCornerShape(20.dp)
private val CAPSULE_WIDTH = 300.dp
private val CAPSULE_HEIGHT = 64.dp
private val CAPSULE_GAP = 4.dp
private val RING_STROKE = 3.dp
private val RING_GAP = 1.dp
private val RESULT_COLOR = Color(0xFF5CE1A0)
private val STALLED_COLOR = Color(0xFFFFB454)
private const val SEGMENT_GAP_DEG = 10f
private const val SPIN_SWEEP_DEG = 90f
private const val SPIN_MS = 1_400
/** A change shows the capsule this long; a waiting result keeps it until seen. */
internal const val CAPSULE_SHOWN_MS = 8_000L
