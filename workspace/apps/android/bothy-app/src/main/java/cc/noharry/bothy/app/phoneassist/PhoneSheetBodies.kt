package cc.noharry.bothy.app.phoneassist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.agent.appserver.protocol.ConfirmationStatus
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.RunOutcome
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.view.OverlayPhase
import cc.noharry.agent.appserver.protocol.view.currentTurnItems
import cc.noharry.agent.appserver.protocol.view.pickableReply
import cc.noharry.agent.appserver.protocol.view.shownPage
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.a2ui.ConsoleWidgets
import cc.noharry.bothy.app.a2ui.LocalPhoneA2ui
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.labelRes
import cc.noharry.bothy.app.console.ui.Markdown
import cc.noharry.bothy.app.console.ui.StatusDot
import cc.noharry.bothy.app.image.GeneratedImageRow
import cc.noharry.bothy.app.page.DrawnPageCard
import cc.noharry.bothy.app.phone.ConsoleTypeOnPhone
import cc.noharry.bothy.app.phone.DetailLine
import cc.noharry.bothy.app.phone.PhoneType
import cc.noharry.bothy.app.phone.PillButton
import cc.noharry.bothy.app.phone.StepsCard
import cc.noharry.bothy.app.phone.isPhoneStep
import cc.noharry.bothy.app.phone.onAccent
import cc.noharry.bothy.app.phone.phoneStepTitle
import cc.noharry.bothy.protocol.view.OverlayHud
import cc.noharry.bothy.protocol.voice

/** An approval the run waits for, with the call that asked for it when the turn holds it. */
internal data class WaitingApproval(val item: ConversationItem.ConfirmationItem, val tool: ConversationItem.ToolCallItem?)

/** The approval the current turn waits for, if any; none once the run is over (an unanswered one stays pending). */
internal fun ServerState.waitingApproval(): WaitingApproval? {
    if (!isRunning) return null
    val turn = items.currentTurnItems()
    val at = turn.indexOfLast { it is ConversationItem.ConfirmationItem && it.status == ConfirmationStatus.PENDING }
    if (at < 0) return null
    val tool = turn.subList(0, at).lastOrNull { it is ConversationItem.ToolCallItem } as? ConversationItem.ToolCallItem
    return WaitingApproval(turn[at] as ConversationItem.ConfirmationItem, tool)
}

/**
 * What the sheet holds, as the design draws it: an approval the run waits for first, then a task going on, then
 * listening after a wake (or with nothing else to show), else the answer.
 */
@Composable
internal fun ColumnScope.SheetBody(model: PhoneSheetModel, actions: PhoneSheetActions) {
    val state = model.state
    val hud = model.hud
    val approval = state.waitingApproval()
    when {
        approval != null -> ApprovalBody(approval, actions)
        hud?.phase == OverlayPhase.LIVE -> WorkingBody(model, actions)
        model.fresh || hud == null || hud.phase == OverlayPhase.IDLE -> ListenBody(model, actions)
        else -> ResultBody(model, hud, actions)
    }
}

/** Listening: what is heard, large, the voice's level, and typing instead, stopping, or closing. */
@Composable
private fun ListenBody(model: PhoneSheetModel, actions: PhoneSheetActions) {
    val colors = ConsoleColors
    val voice = model.state.voice
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar()
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.phone_assist_name), style = PhoneType.cardTitle, color = colors.text, modifier = Modifier.weight(1f))
        if (voice.listening) ListeningChip()
    }
    Spacer(Modifier.height(18.dp))
    val heard = voice.liveText.takeIf { it.isNotBlank() }
    Text(
        heard?.let { "“$it”" } ?: stringResource(if (voice.listening) R.string.phone_assist_listen_hint else R.string.phone_assist_idle_hint),
        style = PhoneType.title.copy(fontSize = 22.sp, lineHeight = 30.sp),
        color = if (heard != null) colors.text else colors.muted,
        fontWeight = if (heard != null) FontWeight.SemiBold else FontWeight.Normal,
    )
    if (voice.listening) {
        Spacer(Modifier.height(14.dp))
        LevelBars(voice.micLevels)
    }
    if (model.typing) {
        Spacer(Modifier.height(14.dp))
        SheetInput(stringResource(R.string.phone_composer_hint), onSend = actions.send, onLongSend = null)
    }
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        RoundButton(painterResource(R.drawable.ic_phone_keyboard), stringResource(R.string.phone_assist_type)) { actions.type(!model.typing) }
        BigMic(listening = voice.listening, onTap = if (voice.listening) actions.stopListening else actions.talk)
        RoundButton(null, stringResource(R.string.console_close_preview), onClick = actions.close)
    }
}

/** A task going on: what was asked, what it does now and its steps, then adding to it or stopping it. */
@Composable
private fun WorkingBody(model: PhoneSheetModel, actions: PhoneSheetActions) {
    val colors = ConsoleColors
    val state = model.state
    val resources = LocalResources.current
    val asked = state.items.lastOrNull { it is ConversationItem.TurnHeader } as? ConversationItem.TurnHeader
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            asked?.let { "“${it.preview}”" }.orEmpty(),
            style = PhoneType.cardTitle,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Surface(onClick = actions.fold, shape = CircleShape, color = colors.elevated, border = BorderStroke(1.dp, colors.border)) {
            Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.phone_assist_fold), style = PhoneType.label, color = colors.inkSoft)
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color = colors.accent, pulsing = true, modifier = Modifier.size(8.dp))
        Spacer(Modifier.width(8.dp))
        Text(phoneRunSignal(resources, state, usingScreen = false)?.title.orEmpty(), style = PhoneType.label, color = colors.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        RunningChip()
    }
    val steps = state.items.currentTurnItems().filter { it.isPhoneStep() }
    if (steps.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        StepsCard(steps, live = true)
    }
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (model.typing) {
            SheetInput(stringResource(R.string.phone_composer_running), onSend = actions.steer, onLongSend = actions.interruptWith, modifier = Modifier.weight(1f))
        } else {
            InputPlaceholder(stringResource(R.string.phone_composer_running), Modifier.weight(1f)) { actions.type(true) }
        }
        Spacer(Modifier.width(10.dp))
        StopButton(actions.stop)
    }
}

/** The answer: how it went and how long it took, the reply with its cards and picks, then copy, the app, or talk on. */
@Composable
private fun ColumnScope.ResultBody(model: PhoneSheetModel, hud: OverlayHud, actions: PhoneSheetActions) {
    val colors = ConsoleColors
    val state = model.state
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar()
        Spacer(Modifier.width(10.dp))
        Text(stringResource(outcomeTitle(hud.phase, state.lastRun?.outcome)), style = PhoneType.cardTitle, color = colors.text)
        state.lastRun?.durationMs?.let { took ->
            Spacer(Modifier.width(8.dp))
            Text(tookLabel(took), style = PhoneType.label, color = colors.muted)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = actions.close) { Icon(Icons.Filled.Close, stringResource(R.string.console_close_preview), tint = colors.muted) }
    }
    Spacer(Modifier.height(8.dp))
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        if (hud.resultText.isNotBlank()) ConsoleTypeOnPhone { Markdown(hud.resultText) }
        hud.picture?.let { picture ->
            Spacer(Modifier.height(10.dp))
            GeneratedImageRow(picture)
        }
        // Pages the turn drew (it began with the app in front): shown as in the conversation, a tap on one answers.
        state.items.currentTurnItems().filterIsInstance<ConversationItem.ToolCallItem>().mapNotNull { it.shownPage() }.forEach { page ->
            Spacer(Modifier.height(12.dp))
            DrawnPageCard(page, actions.reply, onWhole = actions.openApp)
        }
        state.pickableReply()?.let { pickable ->
            Spacer(Modifier.height(12.dp))
            CompositionLocalProvider(LocalPhoneA2ui provides true) { ConsoleTypeOnPhone { ConsoleWidgets(pickable, actions.reply) } }
        }
    }
    Spacer(Modifier.height(12.dp))
    ResultFooter(model, hud, actions)
}

@Composable
private fun ResultFooter(model: PhoneSheetModel, hud: OverlayHud, actions: PhoneSheetActions) {
    val colors = ConsoleColors
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundButton(painterResource(R.drawable.ic_phone_copy), stringResource(R.string.phone_assist_copy)) { actions.copy(hud.resultText) }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = actions.openApp) { Text(stringResource(R.string.phone_assist_open_app), style = PhoneType.label, color = colors.text) }
        Spacer(Modifier.weight(1f))
        if (model.state.voice.listening) {
            ListeningChip()
        } else {
            PillButton(stringResource(R.string.phone_assist_talk), fill = colors.accent, text = onAccent(), Modifier.width(112.dp), onClick = actions.talk)
        }
    }
}

/** An approval the run waits for: what it wants to reach, the tool and how approvals go, then deny or approve. */
@Composable
private fun ColumnScope.ApprovalBody(approval: WaitingApproval, actions: PhoneSheetActions) {
    val colors = ConsoleColors
    val item = approval.item
    val resources = LocalResources.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(colors.accentSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.phone_assist_pending), style = PhoneType.small, color = colors.muted)
            val title = item.domain.takeIf { it.isNotBlank() }?.let { stringResource(R.string.phone_approval_title, it) }
                ?: stringResource(R.string.phone_approval_title_online)
            Text(title, style = PhoneType.cardTitle, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            approval.tool?.let { Text(phoneStepTitle(it, resources), style = PhoneType.body, color = colors.muted, maxLines = 1) }
        }
    }
    Spacer(Modifier.height(14.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.elevated).padding(horizontal = 16.dp, vertical = 10.dp)) {
        if (item.domain.isNotBlank()) DetailLine(stringResource(R.string.phone_approval_site), item.domain)
        approval.tool?.let { DetailLine(stringResource(R.string.phone_approval_tool), it.toolName.substringAfterLast("__")) }
        DetailLine(stringResource(R.string.console_approval), stringResource(item.mode.labelRes()))
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth()) {
        PillButton(stringResource(R.string.console_deny), fill = colors.elevated, text = colors.text, Modifier.weight(1f)) { actions.deny(item.pendingId) }
        Spacer(Modifier.width(12.dp))
        PillButton(stringResource(R.string.console_approve), fill = colors.accent, text = onAccent(), Modifier.weight(1f)) { actions.approve(item.pendingId) }
    }
    TextButton(onClick = { actions.allowSession(item.pendingId) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        Text(stringResource(R.string.phone_assist_allow_session), style = PhoneType.label, color = colors.muted)
    }
}

private fun outcomeTitle(phase: OverlayPhase, outcome: RunOutcome?): Int = when {
    phase == OverlayPhase.FAIL -> R.string.phone_assist_failed
    phase == OverlayPhase.STOP -> R.string.phone_assist_stopped
    outcome == RunOutcome.PARTIAL -> R.string.phone_assist_partly
    else -> R.string.phone_assist_done
}

@Composable
private fun tookLabel(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(1)
    return if (seconds < 60) stringResource(R.string.phone_assist_took_seconds, seconds.toInt())
    else stringResource(R.string.phone_assist_took_minutes, (seconds / 60).toInt(), (seconds % 60).toInt())
}

@Composable
private fun RunningChip() {
    val colors = ConsoleColors
    Row(Modifier.clip(CircleShape).background(colors.accentSoft).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = colors.accent, strokeWidth = 1.5.dp, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.phone_assist_running), style = PhoneType.small, color = colors.accent)
    }
}
