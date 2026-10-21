package cc.noharry.bothy.sample

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.roundToInt

/** One plan in the popup: its kind (自动, 去办, 提醒), its title, what it says, and what its car controls are doing. */
internal data class DebugPlanNoticeEntry(
    val planId: String,
    val kind: String,
    val title: String,
    val said: String?,
    val doing: String?,
)

/**
 * The popup's entry for [plan] coming due, [reply] being what it says out loud: a task as handed to 小博, a reminder's
 * words, and its controls as being run until [DebugPlanNotice.ran] has their outcome.
 */
internal fun planNoticeEntry(plan: DebugPlan, reply: String?): DebugPlanNoticeEntry = DebugPlanNoticeEntry(
    planId = plan.id,
    kind = when {
        plan.actions.isNotEmpty() -> "自动"
        plan.instruction != null -> "去办"
        else -> "提醒"
    },
    title = plan.title,
    said = plan.instruction?.let { "交给小博：$it" } ?: reply?.takeIf(String::isNotBlank),
    doing = plan.actions.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "执行：") { it.label },
)

/** The newest first; the same plan firing again replaces its entry; at most [MAX_NOTICE_ENTRIES]. */
internal fun noticeEntries(current: List<DebugPlanNoticeEntry>, entry: DebugPlanNoticeEntry): List<DebugPlanNoticeEntry> =
    (listOf(entry) + current.filter { it.planId != entry.planId }).take(MAX_NOTICE_ENTRIES)

/** The whole seconds left on the countdown, as shown: 5 right after it opens, 1 in its last second. */
internal fun noticeSecondsLeft(leftMillis: Long): Int = ceil(leftMillis.coerceAtLeast(0L) / MILLIS_PER_SECOND).toInt()

/**
 * A plan coming due (owner 2026-10-15: 「执行计划的时候有个提示音然后出现一个小弹窗展示一下，然后倒计时5秒后消失，也
 * 可以直接点击消失」): the plan chime, and a small card at the top right, clear of 小博's card in the middle, that counts
 * [PLAN_NOTICE_MS] down and goes; a tap closes it. Plans coming due together share it, the newest on top, and each one
 * starts the countdown again. Without the overlay permission, or while a head unit shows the assistant
 * ([DebugAssistantSurface]), only the chime plays. [tried] is another chime to try
 * ([DebugPlanNoticeReceiver]). Any thread; the window lives on main.
 */
internal object DebugPlanNotice {
    private val main = Handler(Looper.getMainLooper())
    private var window: DebugPlanNoticeWindow? = null
    private val timeout = Runnable { dismiss() }

    fun show(context: Context, entry: DebugPlanNoticeEntry, tried: DebugChimeTry? = null) {
        val app = context.applicationContext
        main.post {
            DebugVoiceFeedback.planDue(tried)
            // A head unit showing the assistant shows the plan's run in its own card; no window over it.
            if (DebugAssistantSurface.active) return@post
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(app)) {
                Log.i(PLAN_LOG_TAG, "plan popup not shown: no overlay permission")
                return@post
            }
            val shown = window ?: runCatching { DebugPlanNoticeWindow(app, ::dismiss) }
                .onFailure { Log.w(PLAN_LOG_TAG, "plan popup failed: ${it.javaClass.simpleName}") }
                .getOrNull()?.also { window = it } ?: return@post
            shown.entries = noticeEntries(shown.entries, entry)
            shown.deadline = SystemClock.uptimeMillis() + PLAN_NOTICE_MS
            main.removeCallbacks(timeout)
            main.postDelayed(timeout, PLAN_NOTICE_MS)
        }
    }

    /** Only the chime, for trying one on the device's speakers. */
    fun chime(tried: DebugChimeTry?) {
        main.post { DebugVoiceFeedback.planDue(tried) }
    }

    /** [planId]'s controls are done: their outcome replaces 「执行：…」 while its entry is still up. */
    fun ran(planId: String, lines: List<String>) {
        if (lines.isEmpty()) return
        main.post {
            val shown = window ?: return@post
            shown.entries = shown.entries.map { if (it.planId == planId) it.copy(doing = lines.joinToString("；")) else it }
        }
    }

    private fun dismiss() {
        main.removeCallbacks(timeout)
        window?.destroy()
        window = null
    }
}

/** The popup's own window, as wide as fits a line of a reminder, as tall as its entries; touches beside it pass through. */
private class DebugPlanNoticeWindow(context: Context, onTap: () -> Unit) {
    var entries by mutableStateOf(emptyList<DebugPlanNoticeEntry>())
    var deadline by mutableLongStateOf(0L)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val owner = DebugOverlayComposeOwner()
    private val view = ComposeView(context)

    init {
        val scale = DebugUiScale.overlayStageScale(context)
        val unit = DebugUiScale.designUnitPx(context)
        val params = WindowManager.LayoutParams(
            (NOTICE_WIDTH.value * context.resources.displayMetrics.density * scale).roundToInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (NOTICE_SIDE_DESIGN_PX * unit).roundToInt()
            y = (NOTICE_TOP_DESIGN_PX * unit).roundToInt()
        }
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
                PlanNoticeCard(entries, deadline, onTap)
            }
        }
        owner.attachTo(view)
        windowManager.addView(view, params)
    }

    fun destroy() {
        runCatching { windowManager.removeView(view) }
        owner.destroy()
    }
}

@Composable
private fun PlanNoticeCard(entries: List<DebugPlanNoticeEntry>, deadline: Long, onTap: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(NoticeShape)
            .background(OverlayPalette.glass)
            .border(1.dp, OverlayPalette.edge, NoticeShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            entries.forEach { PlanNoticeEntry(it) }
        }
        PlanNoticeCountdown(deadline)
    }
}

@Composable
private fun PlanNoticeEntry(entry: DebugPlanNoticeEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                entry.kind,
                color = OverlayPalette.onSignature,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(KindShape).background(OverlayPalette.signature).padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Text(entry.title, color = OverlayPalette.text, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        entry.said?.let { Text(it, color = OverlayPalette.secondary, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        entry.doing?.let { Text(it, color = OverlayPalette.glow, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/** The seconds left inside a ring that empties with them; it follows the frame clock, not the animation scale. */
@Composable
private fun PlanNoticeCountdown(deadline: Long) {
    var left by remember { mutableLongStateOf(PLAN_NOTICE_MS) }
    LaunchedEffect(deadline) {
        do {
            withFrameMillis { }
            left = (deadline - SystemClock.uptimeMillis()).coerceIn(0L, PLAN_NOTICE_MS)
        } while (left > 0L)
    }
    Box(Modifier.size(COUNTDOWN_SIDE), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(COUNTDOWN_SIDE)) {
            val stroke = COUNTDOWN_STROKE.toPx()
            drawCircle(OverlayPalette.hairline, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
            drawArc(
                OverlayPalette.signature, -90f, 360f * left / PLAN_NOTICE_MS, false,
                topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Text("${noticeSecondsLeft(left)}", color = OverlayPalette.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

private val NoticeShape = RoundedCornerShape(20.dp)
private val KindShape = RoundedCornerShape(8.dp)
private val NOTICE_WIDTH = 380.dp
private val COUNTDOWN_SIDE = 38.dp
private val COUNTDOWN_STROKE = 3.dp
/** Bench pixels from the screen's top and right edges: the card's top margin, so the two line up. */
private const val NOTICE_TOP_DESIGN_PX = 36f
private const val NOTICE_SIDE_DESIGN_PX = 24f
private const val MILLIS_PER_SECOND = 1_000.0
internal const val PLAN_NOTICE_MS = 5_000L
internal const val MAX_NOTICE_ENTRIES = 3
