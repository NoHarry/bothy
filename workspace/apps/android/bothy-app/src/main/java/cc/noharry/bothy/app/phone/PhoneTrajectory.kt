package cc.noharry.bothy.app.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.businessToolLineLabel
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.consoleLayoutPolicy
import cc.noharry.bothy.app.console.labelRes
import cc.noharry.bothy.app.console.ui.CodeBlock
import cc.noharry.bothy.app.console.ui.StatusDot
import cc.noharry.bothy.app.trajectory.TrajectoryScreen

/**
 * The trajectory on a phone, as the phone design draws it: a turn's tool calls as cards (the kind of work, the tool
 * and how it went), each opening to what it was given and what came back; the turn whose footnote was tapped ([turn]),
 * else the latest. The full trajectory, with every session and run, is one tap further; back from it returns to the cards.
 */
@Composable
internal fun PhoneTrajectory(state: ServerState, actions: ConsoleActions, turn: Long?, onBack: () -> Unit) {
    var full by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = full) { full = false }
    PhonePage(stringResource(R.string.console_view_trajectory), onBack = { if (full) full = false else onBack() }) {
        if (full) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                TrajectoryScreen(state, actions, consoleLayoutPolicy(maxWidth.value, maxHeight.value), modifier = Modifier.fillMaxSize())
            }
        } else {
            TurnCalls(state, turn, { full = true }, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun TurnCalls(state: ServerState, turn: Long?, onFull: () -> Unit, modifier: Modifier) {
    val calls = remember(state.items, turn) { turnCalls(state.items, turn) }
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

/**
 * The tool calls of the turn that starts at the item [turn] (the latest turn when it is null or no longer there), up to
 * the next turn: what the phone's trajectory lists, and what a turn's footnote counts.
 */
internal fun turnCalls(items: List<ConversationItem>, turn: Long?): List<ConversationItem.ToolCallItem> {
    val asked = turn?.let { id -> items.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
    val start = asked ?: items.indexOfLast { it is ConversationItem.TurnHeader }.coerceAtLeast(0)
    val next = items.drop(start + 1).indexOfFirst { it is ConversationItem.TurnHeader }
    val end = if (next < 0) items.size else start + 1 + next
    return items.subList(start, end).filterIsInstance<ConversationItem.ToolCallItem>().filter { it.isPhoneStep() }
}

@Composable
private fun CallCard(call: ConversationItem.ToolCallItem) {
    val colors = ConsoleColors
    var open by rememberSaveable(call.id) { mutableStateOf(false) }
    // Each call's own start and end are not always kept (loaded runs give every call the run's), so no duration here.
    val meta = listOf(call.businessToolLineLabel(), stringResource(call.status.labelRes())).joinToString(" · ")
    val title = stepTitle(call, LocalResources.current)
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
                Text(title, style = ConsoleType.body, color = colors.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
