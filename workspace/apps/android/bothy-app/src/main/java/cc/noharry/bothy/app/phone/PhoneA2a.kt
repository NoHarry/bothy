package cc.noharry.bothy.app.phone

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.A2aClientState
import cc.noharry.agent.appserver.protocol.A2aProfile
import cc.noharry.agent.appserver.protocol.DelegationStage
import cc.noharry.agent.appserver.protocol.DelegationView
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.a2a.A2aClientActions
import cc.noharry.bothy.app.a2a.A2aClientPane
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ui.StatusDot
import cc.noharry.bothy.app.overlay.overlayLabel
import cc.noharry.bothy.app.words

/**
 * A2A on a phone, as the design draws it: the agents Bothy can hand tasks to and whether each can take one, then the
 * latest reports of what was handed over, and the way to manage them ([onManage]). Talking to one agent directly is
 * the client mode, on only while its page is open.
 */
@Composable
internal fun PhoneA2a(state: ServerState, client: A2aClientState, actions: A2aClientActions?, onManage: () -> Unit, onBack: () -> Unit) {
    // Client mode left on (by the tablet, or before a restart) opens on talking to the agent.
    var talking by rememberSaveable { mutableStateOf(client.enabled) }
    BackHandler(enabled = talking) { talking = false }
    if (talking && actions != null) {
        DisposableEffect(Unit) {
            // Every time: a switch-off from leaving the page a moment ago may not have reached the state yet.
            actions.setEnabled(true)
            onDispose { actions.setEnabled(false) }
        }
        PhonePage(stringResource(R.string.phone_a2a_talk), onBack = { talking = false }) {
            // The floating card is the tablet's: a phone has no resting 小博 to open it on.
            ConsoleTypeOnPhone { A2aClientPane(state = client, actions = actions.copy(openOverlay = null), header = {}, modifier = Modifier.weight(1f).fillMaxWidth()) }
        }
        return
    }
    val modules = state.a2aClients
    val reports = state.delegations.sortedByDescending { it.updatedAtEpochMillis }.take(REPORTS_SHOWN)
    PhonePage(stringResource(R.string.console_view_a2a), onBack) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "intro") {
                Text(stringResource(R.string.phone_a2a_intro), style = PhoneType.body, color = ConsoleColors.muted, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            }
            item(key = "agents") {
                SectionHead(stringResource(R.string.phone_a2a_agents), pluralStringResource(R.plurals.phone_a2a_agent_count, modules.profiles.size, modules.profiles.size))
            }
            if (modules.profiles.isEmpty()) item(key = "none") { NoteCard(stringResource(R.string.phone_a2a_none)) }
            items(modules.profiles, key = { it.id }) { profile ->
                AgentCard(profile, enabled = profile.id in modules.enabledProfileIds, error = modules.errorsByProfileId[profile.id])
            }
            if (reports.isNotEmpty()) {
                item(key = "reports") { SectionHead(stringResource(R.string.phone_a2a_reports), null) }
                items(reports, key = { it.taskId }) { ReportCard(it) }
            }
            item(key = "actions") {
                Column(Modifier.padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (actions != null) PillButton(stringResource(R.string.phone_a2a_talk), fill = ConsoleColors.accent, text = onAccent(), Modifier.fillMaxWidth()) { talking = true }
                    PillButton(stringResource(R.string.phone_a2a_manage), fill = ConsoleColors.panel, text = ConsoleColors.text, Modifier.fillMaxWidth(), onClick = onManage)
                }
            }
        }
    }
}

@Composable
private fun SectionHead(title: String, count: String?) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = PhoneType.label, color = ConsoleColors.muted, modifier = Modifier.weight(1f))
        if (count != null) Text(count, style = PhoneType.small, color = ConsoleColors.muted)
    }
}

@Composable
private fun NoteCard(text: String) {
    val colors = ConsoleColors
    Surface(shape = RoundedCornerShape(CARD_RADIUS), color = colors.elevated, border = BorderStroke(1.dp, colors.border), modifier = Modifier.fillMaxWidth()) {
        Text(text, style = PhoneType.body, color = colors.muted, modifier = Modifier.padding(20.dp))
    }
}

/** An agent: its name and what it can do, and whether it takes tasks now. */
@Composable
private fun AgentCard(profile: A2aProfile, enabled: Boolean, error: String?) {
    val colors = ConsoleColors
    val (status, dot) = when {
        !enabled -> stringResource(R.string.phone_a2a_off) to colors.muted
        error != null -> stringResource(R.string.console_status_failed) to colors.danger
        else -> stringResource(R.string.phone_a2a_on) to colors.accent
    }
    Surface(shape = RoundedCornerShape(CARD_RADIUS), color = colors.elevated, border = BorderStroke(1.dp, colors.border), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(colors.panel), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Share, contentDescription = null, tint = if (enabled) colors.accent else colors.muted, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.label, style = PhoneType.cardTitle, color = if (enabled) colors.text else colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val about = profile.description.ifBlank { profile.endpoint.host.orEmpty() }
                if (about.isNotBlank()) Text(about, style = PhoneType.body, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            StatusChip(status, dot)
        }
    }
}

/** What was handed to an agent: to whom and what, when, how it stands, and its report once settled. */
@Composable
private fun ReportCard(report: DelegationView) {
    val colors = ConsoleColors
    val failed = report.stage == DelegationStage.FAILED || report.stage == DelegationStage.LOST || report.stage == DelegationStage.CANCELLED
    Surface(shape = RoundedCornerShape(CARD_RADIUS), color = colors.elevated, border = BorderStroke(1.dp, colors.border), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(if (failed) colors.danger.copy(alpha = FAILED_TINT) else colors.accentSoft), contentAlignment = Alignment.Center) {
                    when {
                        !report.stage.settled -> CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        failed -> Icon(Icons.Filled.Close, contentDescription = null, tint = colors.danger, modifier = Modifier.size(16.dp))
                        else -> Icon(Icons.Filled.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.phone_a2a_handed, report.agentLabel, report.request), style = PhoneType.label, color = colors.text, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val ago = DateUtils.getRelativeTimeSpanString(report.updatedAtEpochMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                    Text(listOf(ago, report.statusText).filter { it.isNotBlank() }.joinToString(" · "), style = PhoneType.small, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                StatusChip(stringResource(report.stage.overlayLabel()), if (failed) colors.danger else if (report.stage.settled) colors.accent else colors.activity)
            }
            // The agent's own words, else why it ended without them; a report that came with none says so.
            val resources = LocalResources.current
            val noNote = stringResource(R.string.a2a_no_note)
            (report.said.ifBlank { null } ?: report.ended?.let(resources::words) ?: noNote.takeIf { report.outcome != null })?.let {
                Text(it, style = PhoneType.body, color = colors.inkSoft, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panel).padding(12.dp))
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, dot: Color) {
    val colors = ConsoleColors
    Row(Modifier.clip(CircleShape).background(colors.panel).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color = dot, modifier = Modifier.size(6.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, style = PhoneType.small, color = colors.inkSoft, maxLines = 1)
    }
}

private const val REPORTS_SHOWN = 5
private const val FAILED_TINT = 0.12f
