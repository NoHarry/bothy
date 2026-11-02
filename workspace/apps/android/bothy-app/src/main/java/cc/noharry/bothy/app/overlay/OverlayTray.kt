package cc.noharry.bothy.app.overlay
import android.content.res.Resources
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.a2a.DelegationStage
import cc.noharry.bothy.app.a2a.DelegationView
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.status

/**
 * The card's way back to results (owner 2026-10-12): after a wake or tap on a read result, 上次 opens it; after a new
 * request replaced an unread one, 上一个结果 shows it and 回到当前 returns.
 */
@Composable
internal fun OverlayResultChips(ui: OverlayUi, actions: OverlayActions) {
    val last = ui.lastResult
    val previous = ui.previousResult
    if (last == null && previous == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val resources = LocalContext.current.resources
        last?.let {
            TrayChip(stringResource(R.string.overlay_last_result, resources.overlayShownText(it)), actions.onLastResult)
        }
        previous?.let {
            val text = if (ui.showingPrevious) {
                stringResource(R.string.overlay_back_to_current)
            } else {
                stringResource(R.string.overlay_previous_result, resources.overlayShownText(it))
            }
            TrayChip(text, actions.onPreviousResult)
        }
    }
}

/**
 * Delegated tasks under way, one line each: who, where it stands, for how long, how far through its steps and what the
 * agent last said; a settled one waiting to be spoken offers 查看 (owner 2026-10-12: 「不知道到底怎么样了，还有哪些没做」).
 */
@Composable
internal fun OverlayDelegationTray(views: List<DelegationView>, nowMillis: Long, onRelease: () -> Unit) {
    if (views.isEmpty()) return
    val resources = LocalContext.current.resources
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        views.takeLast(MAX_TRAY_ROWS).forEach { view ->
            val waiting = view.stage == DelegationStage.REPORTING
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
                        if (waiting) {
                            resources.getString(R.string.overlay_delegation_new_result, view.agentLabel)
                        } else {
                            resources.trayHeader(view, nowMillis)
                        },
                        color = OverlayPalette.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        resources.trayDetail(view),
                        color = OverlayPalette.secondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (waiting) Text(stringResource(R.string.overlay_view), color = OverlayPalette.glow, fontSize = 14.sp, fontWeight = FontWeight.Medium)
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

/** A tray line's header: who, where it stands, for how long and how far through its steps. */
private fun Resources.trayHeader(view: DelegationView, nowMillis: Long): String {
    val stage = getString(view.stage.overlayLabel())
    val elapsed = delegationElapsed(nowMillis, view.startedAtEpochMillis)
    return if (view.steps.isEmpty()) {
        getString(R.string.overlay_tray_header, view.agentLabel, stage, elapsed)
    } else {
        val done = view.steps.count { it.status == "done" }
        getString(R.string.overlay_tray_header_steps, view.agentLabel, stage, elapsed, done, view.steps.size)
    }
}

/** What a tray line says under its header: the agent's latest words, else what was asked. */
private fun Resources.trayDetail(view: DelegationView): String = when (view.stage) {
    DelegationStage.REPORTING -> view.outcome?.substringAfter('：')?.ifBlank { null } ?: view.request
    DelegationStage.STALLED -> view.statusText.ifBlank { getString(R.string.overlay_delegation_not_started) }
    else -> view.statusText.takeIf { it.isNotBlank() && view.stage == DelegationStage.WORKING }
        ?: getString(R.string.overlay_quoted_request, view.request)
}

private fun DelegationStage.dot(): Color = when (this) {
    DelegationStage.STALLED, DelegationStage.FAILED, DelegationStage.LOST -> Color(0xFFFFB454)
    DelegationStage.REPORTING, DelegationStage.DONE -> Color(0xFF5CE1A0)
    else -> OverlayPalette.glow
}

private val TrayShape = RoundedCornerShape(12.dp)
private const val MAX_TRAY_ROWS = 3
