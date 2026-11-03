package cc.noharry.bothy.app.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.statusDetail
import cc.noharry.bothy.app.console.statusHeader
import cc.noharry.bothy.app.console.ui.StatusDot
import cc.noharry.bothy.app.overlay.Mascot
import cc.noharry.bothy.app.overlay.MascotAction
import cc.noharry.bothy.app.overlay.MascotPose
import cc.noharry.bothy.app.session.ConfirmationStatus
import cc.noharry.bothy.app.session.ConversationItem
import cc.noharry.bothy.app.session.ToolCallStatus
import cc.noharry.bothy.app.session.UiState
import kotlinx.coroutines.delay

/**
 * Over the composer while a run goes on: what it is doing now and how long the run has taken, as a running clock.
 * Nothing shows between runs.
 */
@Composable
internal fun PhoneActivityStrip(state: UiState) {
    if (!state.isRunning) return
    val colors = ConsoleColors
    val startedAt = state.runStartedAtEpochMillis ?: remember { System.currentTimeMillis() }
    var now by remember(startedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            delay(CLOCK_TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    val doing = listOfNotNull(statusHeader(state), statusDetail(state)).joinToString(" · ")
    Surface(shape = RoundedCornerShape(16.dp), color = colors.elevated, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = colors.activity, pulsing = true, modifier = Modifier.size(7.dp))
            Spacer(Modifier.width(10.dp))
            Text(doing, style = ConsoleType.meta, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(clock((now - startedAt).coerceAtLeast(0L)), style = ConsoleType.meta, color = colors.tertiary)
        }
    }
}

/** A run's clock: 0:07, 12:45, 1:02:05. */
internal fun clock(millis: Long): String {
    val seconds = millis / 1000
    val (h, m, s) = Triple(seconds / 3600, seconds / 60 % 60, seconds % 60)
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * 小博 on the composer's edge, as the overlay draws it: thinking, searching or at work while a run goes on, asking
 * while an approval waits, pleased for a moment once it is done (worried if it failed), then idle.
 */
@Composable
internal fun PhoneMascot(state: UiState, modifier: Modifier = Modifier) {
    val pose = remember { MascotPose() }
    val running = state.isRunning
    var finished by remember { mutableStateOf<MascotAction?>(null) }
    var wasRunning by remember { mutableStateOf(running) }
    LaunchedEffect(running) {
        if (wasRunning && !running) {
            finished = if (lastResultFailed(state.items)) MascotAction.FAILED else MascotAction.DONE
            delay(FINISHED_MS)
            finished = null
        }
        wasRunning = running
    }
    Box(modifier.size(MASCOT_SIZE)) {
        Mascot(pose, phoneMascotAction(state, finished), driving = true, modifier = Modifier.size(MASCOT_SIZE))
    }
}

internal fun phoneMascotAction(state: UiState, finished: MascotAction?): MascotAction {
    val waiting = state.items.any { it is ConversationItem.ConfirmationItem && it.status == ConfirmationStatus.PENDING }
    if (waiting) return MascotAction.ASKING
    if (!state.isRunning) return finished ?: MascotAction.IDLE
    val tool = state.items.lastOrNull { it.isPhoneStep() } as? ConversationItem.ToolCallItem
    if (tool == null || tool.status != ToolCallStatus.RUNNING) return MascotAction.THINKING
    val name = tool.toolName.lowercase()
    return when {
        DRAWING_WORDS.any { it in name } -> MascotAction.DRAWING
        SEARCHING_WORDS.any { it in name } -> MascotAction.SEARCHING
        else -> MascotAction.WORKING
    }
}

private fun lastResultFailed(items: List<ConversationItem>): Boolean {
    val result = items.filterIsInstance<ConversationItem.RuntimePhaseItem>().lastOrNull()?.title ?: return false
    return result.startsWith("Runtime result ") && !result.contains("COMPLETED")
}

private val MASCOT_SIZE = 56.dp
private const val CLOCK_TICK_MS = 1_000L
private const val FINISHED_MS = 6_000L
private val DRAWING_WORDS = listOf("image", "draw")
private val SEARCHING_WORDS = listOf("search", "web", "read", "grep", "list_dir", "poi", "lookup")
