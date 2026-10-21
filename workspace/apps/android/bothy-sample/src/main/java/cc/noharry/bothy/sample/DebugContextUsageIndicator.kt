package cc.noharry.bothy.sample

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun DebugContextUsageIndicator(state: DebugUiState) {
    val context = LocalContext.current
    val profile = state.configEditor
    val identity = listOf(profile.profileId, profile.provider, profile.endpoint, profile.baseUrl, profile.model)
    var window by remember(identity) { mutableStateOf(loadDebugContextWindow(context, profile)) }
    var expanded by remember(identity, state.activeSessionId) { mutableStateOf(false) }
    DisposableEffect(context, identity) {
        val prefs = context.getSharedPreferences(CONTEXT_WINDOW_PREFS, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            window = loadDebugContextWindow(context, profile)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val usage = remember(state.trajectory, state.isRunning, state.currentRequest?.requestId, profile.model, profile.variant, window) {
        debugContextUsage(state, window)
    }
    Box {
        ContextUsageRing(usage) { expanded = true }
        DebugPopupMenu(expanded, { expanded = false }, minWidth = 260.dp, maxWidth = 300.dp) {
            ContextUsageDetails(usage, profile.model)
        }
    }

}

@Composable
private fun ContextUsageRing(usage: DebugContextUsage, onClick: () -> Unit) {
    val colors = DebugColors
    val fraction = usage.fraction
    val tint = when {
        fraction == null -> colors.muted
        fraction >= 1f -> colors.danger
        fraction >= CONTEXT_WARNING_FRACTION -> colors.warning
        else -> colors.muted
    }
    Box(
        Modifier.size(CONTEXT_TOUCH_SIZE).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "上下文占用：${usage.percentText ?: "尚未获取"}，点击查看详情" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(CONTEXT_RING_SIZE)) {
            val stroke = CONTEXT_RING_STROKE.toPx()
            drawCircle(colors.border, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
            if (fraction != null && fraction > 0) drawArc(
                tint, startAngle = -90f, sweepAngle = FULL_CIRCLE * fraction, useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun ContextUsageDetails(usage: DebugContextUsage, model: String) {
    val colors = DebugColors
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("上下文窗口", style = DebugType.label, color = colors.muted)
        Spacer(Modifier.height(8.dp))
        Text(usage.percentText?.let { "已用 $it" } ?: "尚未获取占用比例", style = DebugType.body, color = colors.text)
        Text(
            "${usage.inputTokens?.let(::formatContextTokens) ?: "—"} / ${usage.windowTokens?.let(::formatContextTokens) ?: "未知"} tokens",
            style = DebugType.body, color = colors.text,
        )
        usage.remainingTokens?.let { Text("剩余 ${formatContextTokens(it)} tokens", style = DebugType.meta, color = colors.muted) }
        Spacer(Modifier.height(8.dp))
        Text(model, style = DebugType.meta, color = colors.muted)
        Text(
            if (usage.awaitingAfterCompaction) "已压缩，等待下一次请求用量" else "最近一次请求的输入用量（含缓存）",
            style = DebugType.meta, color = colors.muted,
        )
    }
}

private val CONTEXT_TOUCH_SIZE = 48.dp
private val CONTEXT_RING_SIZE = 18.dp
private val CONTEXT_RING_STROKE = 2.dp
private const val CONTEXT_WARNING_FRACTION = 0.8f
private const val FULL_CIRCLE = 360f
