package cc.noharry.bothy.app.overlay
import android.content.Context
import android.content.res.Resources
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.PlanKind
import cc.noharry.agent.appserver.protocol.PlanNoticeEntry
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.ui.UiScale
import cc.noharry.bothy.app.words
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The newest first; the same plan firing again replaces its entry; at most [MAX_NOTICE_ENTRIES]. */
internal fun noticeEntries(current: List<PlanNoticeEntry>, entry: PlanNoticeEntry): List<PlanNoticeEntry> =
    (listOf(entry) + current.filter { it.planId != entry.planId }).take(MAX_NOTICE_ENTRIES)

/** The whole seconds left on the countdown, as shown: 5 right after it opens, 1 in its last second. */
internal fun noticeSecondsLeft(leftMillis: Long): Int = ceil(leftMillis.coerceAtLeast(0L) / MILLIS_PER_SECOND).toInt()

/**
 * A plan coming due, as the overlay shows it (owner 2026-10-15: 「执行计划的时候有个提示音然后出现一个小弹窗展示一下，然后倒计时5秒后
 * 消失，也可以直接点击消失」): a small card at the top right, clear of 小博's card in the middle, that counts [PLAN_NOTICE_MS]
 * down and goes; a tap closes it. Plans coming due together share it, the newest on top, and each one starts the
 * countdown again. Without the overlay permission, or while a head unit shows the assistant ([AssistantSurface]), it
 * shows nothing (the server's chime still plays). Any thread; the window lives on main.
 */
internal object PlanNoticePopup {
    private val main = Handler(Looper.getMainLooper())
    private var window: PlanNoticeWindow? = null
    private val timeout = Runnable { dismiss() }

    fun show(context: Context, entry: PlanNoticeEntry) {
        val app = context.applicationContext
        main.post {
            // A head unit showing the assistant shows the plan's run in its own card; no window over it.
            if (AssistantSurface.active) return@post
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(app)) {
                Log.i(TAG, "plan popup not shown: no overlay permission")
                return@post
            }
            val shown = window ?: runCatching { PlanNoticeWindow(app, ::dismiss) }
                .onFailure { Log.w(TAG, "plan popup failed: ${it.javaClass.simpleName}") }
                .getOrNull()?.also { window = it } ?: return@post
            shown.entries = noticeEntries(shown.entries, entry)
            shown.deadline = SystemClock.uptimeMillis() + PLAN_NOTICE_MS
            main.removeCallbacks(timeout)
            main.postDelayed(timeout, PLAN_NOTICE_MS)
        }
    }

    /** [planId]'s controls are done: their [outcome] replaces 「执行：…」 while its entry is still up. */
    fun ran(planId: String, outcome: Finding) {
        main.post {
            val shown = window ?: return@post
            shown.entries = shown.entries.map { if (it.planId == planId) it.copy(outcome = outcome) else it }
        }
    }

    private fun dismiss() {
        main.removeCallbacks(timeout)
        window?.destroy()
        window = null
    }

    private const val TAG = "BothyPlans"
}

/** The popup's own window, as wide as fits a line of a reminder, as tall as its entries; touches beside it pass through. */
private class PlanNoticeWindow(context: Context, onTap: () -> Unit) {
    var entries by mutableStateOf(emptyList<PlanNoticeEntry>())
    var deadline by mutableLongStateOf(0L)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val owner = OverlayComposeOwner()
    private val view = ComposeView(context)

    init {
        val scale = UiScale.overlayStageScale(context)
        val unit = UiScale.designUnitPx(context)
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
private fun PlanNoticeCard(entries: List<PlanNoticeEntry>, deadline: Long, onTap: () -> Unit) {
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
            entries.forEach { PlanNoticeRow(it) }
        }
        PlanNoticeCountdown(deadline)
    }
}

@Composable
private fun PlanNoticeRow(entry: PlanNoticeEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(entry.kind.labelRes()),
                color = OverlayPalette.onSignature,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(KindShape).background(OverlayPalette.signature).padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Text(entry.title, color = OverlayPalette.text, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val resources = LocalResources.current
        resources.planNoticeSaid(entry)?.let { Text(it, color = OverlayPalette.secondary, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        resources.planNoticeDoing(entry)?.let { Text(it, color = OverlayPalette.glow, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/** What a plan coming due handed to 小博 or said, in the screen's language. */
internal fun Resources.planNoticeSaid(entry: PlanNoticeEntry): String? =
    entry.handed?.let { getString(R.string.plans_notice_handed, it) } ?: entry.said

/** How its controls went, once they are done; until then which ones run. */
internal fun Resources.planNoticeDoing(entry: PlanNoticeEntry): String? = entry.outcome?.let { words(it) }
    ?: entry.actions.takeIf { it.isNotEmpty() }?.let { getString(R.string.plans_notice_running, it.joinToString(getString(R.string.plans_list_separator))) }

/** A plan's kind on its popup: 自动, 去办, 提醒. */
internal fun PlanKind.labelRes(): Int = when (this) {
    PlanKind.AUTO -> R.string.plans_kind_auto
    PlanKind.TASK -> R.string.plans_kind_task
    PlanKind.REMINDER -> R.string.plans_kind_reminder
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
