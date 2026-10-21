package cc.noharry.bothy.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The card's way back to results (owner 2026-10-12): after a wake or tap on a read result, 上次 opens it; after a new
 * request replaced an unread one, 上一个结果 shows it and 回到当前 returns.
 */
@Composable
internal fun OverlayResultChips(ui: DebugOverlayUi, actions: DebugOverlayActions) {
    val last = ui.lastResult
    val previous = ui.previousResult
    if (last == null && previous == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        last?.let { TrayChip("上次：$it", actions.onLastResult) }
        previous?.let { TrayChip(if (ui.showingPrevious) "回到当前" else "上一个结果：$it", actions.onPreviousResult) }
    }
}

/**
 * Delegated tasks under way, one line each: who, where it stands, for how long, how far through its steps and what the
 * agent last said; a settled one waiting to be spoken offers 查看 (owner 2026-10-12: 「不知道到底怎么样了，还有哪些没做」).
 */
@Composable
internal fun OverlayDelegationTray(views: List<DebugDelegationView>, nowMillis: Long, onRelease: () -> Unit) {
    if (views.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        views.takeLast(MAX_TRAY_ROWS).forEach { view ->
            val waiting = view.stage == DebugDelegationStage.REPORTING
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(TrayShape)
                    .background(OverlayPalette.tile)
                    .border(1.dp, OverlayPalette.tileEdge, TrayShape)
                    .then(if (waiting) Modifier.clickable(onClick = onRelease) else Modifier)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(view.stage.dot()))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (waiting) "${view.agentLabel} 有新结果" else "${view.agentLabel} · ${view.stage.label} · ${delegationElapsed(nowMillis, view.startedAtEpochMillis)}${view.stepsLabel()}",
                        color = OverlayPalette.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        view.trayDetail(),
                        color = OverlayPalette.secondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (waiting) Text("查看", color = OverlayPalette.glow, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun TrayChip(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = OverlayPalette.secondary,
        fontSize = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(TrayShape)
            .background(OverlayPalette.tile)
            .border(1.dp, OverlayPalette.tileEdge, TrayShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** What a tray line says under its header: the agent's latest words, else what was asked. */
private fun DebugDelegationView.trayDetail(): String = when (stage) {
    DebugDelegationStage.REPORTING -> outcome?.substringAfter('：')?.ifBlank { null } ?: request
    DebugDelegationStage.STALLED -> statusText.ifBlank { "已送达，它还没开始处理" }
    else -> statusText.takeIf { it.isNotBlank() && stage == DebugDelegationStage.WORKING } ?: "「$request」"
}

private fun DebugDelegationView.stepsLabel(): String =
    if (steps.isEmpty()) "" else " · ${steps.count { it.status == "done" }}/${steps.size} 步"

private fun DebugDelegationStage.dot(): Color = when (this) {
    DebugDelegationStage.STALLED, DebugDelegationStage.FAILED, DebugDelegationStage.LOST -> Color(0xFFFFB454)
    DebugDelegationStage.REPORTING, DebugDelegationStage.DONE -> Color(0xFF5CE1A0)
    else -> OverlayPalette.glow
}

/** "0:42" under an hour, else "1 小时 5 分". */
internal fun delegationElapsed(nowMillis: Long, sinceMillis: Long): String {
    val seconds = ((nowMillis - sinceMillis) / 1000).coerceAtLeast(0)
    return if (seconds < 3600) "%d:%02d".format(seconds / 60, seconds % 60) else "${seconds / 3600} 小时 ${seconds % 3600 / 60} 分"
}

private val TrayShape = RoundedCornerShape(12.dp)
private const val MAX_TRAY_ROWS = 3
