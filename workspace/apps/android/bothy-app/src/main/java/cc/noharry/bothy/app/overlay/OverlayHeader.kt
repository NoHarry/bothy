package cc.noharry.bothy.app.overlay
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.status

@Composable
internal fun Header(hud: OverlayHud, ui: OverlayUi, mood: Mood, actions: OverlayActions, mascot: MascotPose) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    actions.onDrag(drag.x, drag.y)
                }
            },
    ) {
        // 小博's seat stays laid out (and reported) even while 小博 itself is drawn by another window.
        Box(
            Modifier
                .size(MASCOT_BOX)
                .onGloballyPositioned { placed ->
                    actions.onMascotPlaced(placed.localToWindow(Offset(placed.size.width / 2f, placed.size.height / 2f)))
                }
                // A tap, not a press: a drag that starts on 小博 still moves the card.
                .pointerInput(mascot) { detectTapGestures { mascot.react(MascotReaction.GIGGLE) } },
        ) {
            if (ui.cardDrawn) Mascot(mascot, mood.action, driving = ui.mascotDriving)
        }
        HeaderStatus(hud, ui, actions.onToggleDetails)
        Spacer(Modifier.weight(1f))
        if (ui.stopVisible) StopButton(actions.onStop)
        GlassIcon(Icons.Filled.Edit, stringResource(R.string.overlay_input), tint = if (ui.pendingQuestion || ui.showInput) OverlayPalette.glow else OverlayPalette.secondary, onClick = actions.onToggleInput)
        OverlayCloseButton(ui.collapseAt, ui.collapseMillis, actions.onClose)
    }
}

/** The status line, which opens the plan and tool calls below (owner 2026-10-25: fewer header buttons, 详情 among them). */
@Composable
private fun HeaderStatus(hud: OverlayHud, ui: OverlayUi, onToggleDetails: () -> Unit) {
    val details = stringResource(R.string.overlay_details)
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .pointerInput(Unit) { detectTapGestures { onToggleDetails() } }
            .semantics { contentDescription = details },
    ) {
        Text(
            stringResource(R.string.overlay_xiao_bo),
            color = OverlayPalette.faint,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp,
        )
        // State changes roll in from below rather than snapping.
        AnimatedContent(
            targetState = hud.statusLine(ui),
            transitionSpec = {
                (fadeIn(tween(240)) + slideInVertically(tween(240)) { it / 2 }) togetherWith
                    (fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 2 })
            },
            label = "status",
        ) { status ->
            Text(stringResource(status), color = OverlayPalette.text, fontSize = 19.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@StringRes
private fun OverlayHud.statusLine(ui: OverlayUi): Int = when {
    choices.isNotEmpty() && ui.choicesAnswered -> R.string.overlay_status_got_it
    choices.isNotEmpty() -> R.string.overlay_status_choose
    controls.isNotEmpty() && ui.panelAnswered -> R.string.overlay_status_got_it
    controls.isNotEmpty() -> R.string.overlay_status_adjust
    // The A2A client's headlines (等待回答：…, 等待你的回复) are matched as it writes them.
    phase == OverlayPhase.LIVE && headline.startsWith("等待回答") -> R.string.overlay_status_waiting_answer
    phase == OverlayPhase.LIVE -> R.string.overlay_step_working
    phase == OverlayPhase.DONE && headline == "等待你的回复" -> R.string.overlay_status_waiting_reply
    phase == OverlayPhase.DONE && headline == PARTLY_DONE_HEADLINE -> R.string.overlay_status_partly_done
    phase == OverlayPhase.DONE -> R.string.overlay_status_done
    phase == OverlayPhase.FAIL -> R.string.overlay_status_not_done
    phase == OverlayPhase.STOP -> R.string.overlay_status_stopped
    ui.voiceShown -> R.string.overlay_status_listening
    else -> R.string.overlay_status_ready
}
