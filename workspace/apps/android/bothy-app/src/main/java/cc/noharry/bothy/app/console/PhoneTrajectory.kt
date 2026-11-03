package cc.noharry.bothy.app.console

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ui.CodeBlock
import cc.noharry.bothy.app.console.ui.StatusDot
import cc.noharry.bothy.app.session.ConversationItem
import cc.noharry.bothy.app.session.UiState

/**
 * The trajectory on a portrait phone, as the phone design draws it: the latest turn's tool calls as cards (the tool,
 * how it ended and how long it took), each opening to what it was given and what came back. The full trajectory, with
 * every session and run, is one tap further ([onFull]).
 */
@Composable
internal fun PhoneTrajectory(state: UiState, onFull: () -> Unit, modifier: Modifier) {
    val calls = remember(state.items) { latestTurnCalls(state.items) }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
        item(key = "summary") {
            val summary = if (calls.isEmpty()) {
                stringResource(R.string.console_trajectory_none)
            } else {
                pluralStringResource(R.plurals.console_trajectory_calls, calls.size, calls.size)
            }
            Text(summary, style = ConsoleType.meta, color = ConsoleColors.muted, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        }
        items(calls, key = { it.id }) { call -> CallCard(call) }
        item(key = "full") {
            TextButton(onClick = onFull) { Text(stringResource(R.string.console_trajectory_full)) }
        }
    }
}

/** The tool calls since the latest turn began: what the phone's trajectory lists. */
internal fun latestTurnCalls(items: List<ConversationItem>): List<ConversationItem.ToolCallItem> {
    val start = items.indexOfLast { it is ConversationItem.TurnHeader }.coerceAtLeast(0)
    return items.drop(start).filterIsInstance<ConversationItem.ToolCallItem>()
}

@Composable
private fun CallCard(call: ConversationItem.ToolCallItem) {
    val colors = ConsoleColors
    var open by rememberSaveable(call.id) { mutableStateOf(false) }
    val meta = listOfNotNull(stringResource(call.status.labelRes()), toolElapsedLabel(call)).joinToString(" · ")
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.radiusLg))
            .background(colors.elevated)
            .clickable(role = Role.Button) { open = !open }
            .padding(horizontal = Dimens.spaceMd, vertical = Dimens.spaceSm),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = call.status.color(), modifier = Modifier.size(8.dp))
            Spacer(Modifier.width(Dimens.spaceMd))
            Column(Modifier.weight(1f)) {
                Text(call.businessToolLineLabel(), style = ConsoleType.body, color = colors.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(meta, style = ConsoleType.meta, color = colors.muted, maxLines = 1)
            }
            Icon(if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.muted)
        }
        if (open) {
            call.argumentsJson?.takeIf { it.isNotBlank() }?.let { CallPart(stringResource(R.string.console_trajectory_given), it) }
            call.resultJson?.takeIf { it.isNotBlank() }?.let { CallPart(stringResource(R.string.console_trajectory_returned), it) }
        }
    }
}

@Composable
private fun CallPart(title: String, body: String) {
    Text(title, style = ConsoleType.label, color = ConsoleColors.tertiary, modifier = Modifier.padding(top = Dimens.spaceSm, bottom = 4.dp))
    CodeBlock(body.take(PART_MAX_CHARS))
}

private const val PART_MAX_CHARS = 4_000
