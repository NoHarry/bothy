package cc.noharry.bothy.app.phone

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ui.StatusDot
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.voice.HandOverNotice
import cc.noharry.bothy.app.voice.VoiceMicButton
import cc.noharry.bothy.app.voice.voiceStatusLine
import cc.noharry.bothy.app.words
import cc.noharry.bothy.protocol.VoiceRequests
import cc.noharry.bothy.protocol.VoiceView
import cc.noharry.bothy.protocol.voice
import kotlinx.coroutines.delay

/**
 * A phone's composer, as the design draws it: one white pill with the model on the left ([ModelChip], which brings up
 * the run's choices), the message, the mic held to talk, and send. While a run goes on, what is typed steers it and
 * send stops the run when nothing is typed. While the mic is held, the hold's panel covers it ([PhoneHold]).
 */
@Composable
internal fun PhoneComposer(
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    state: ServerState,
    actions: ConsoleActions,
) {
    val colors = ConsoleColors
    val voice = state.voice
    val holding = voice.status.isOn && voice.holding
    Column(Modifier.fillMaxWidth()) {
        PhoneVoiceLine(voice, holding)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = CircleShape,
            color = colors.elevated,
            border = BorderStroke(1.dp, colors.border),
            shadowElevation = PILL_SHADOW,
        ) {
            Row(
                Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModelChip(state, actions)
                PillField(text, onTextChange, state.isRunning, Modifier.weight(1f))
                // While held, the mic is under the hold's panel ([PhoneHold]), still taking the finger.
                VoiceMicButton(MIC_SIZE, MIC_ICON_SIZE, holdToTalk = true, plain = true)
                Spacer(Modifier.width(4.dp))
                if (state.isRunning && text.isBlank()) {
                    StopButton(actions.stop)
                } else {
                    SendButton(enabled = text.isNotBlank(), steering = state.isRunning, onSend = onSubmit)
                }
            }
        }
    }
}

/**
 * The model the next turn runs on, as the profile's name ("DeepSeek", "本机模型"); it brings up the run's choices from
 * the bottom ([PhoneModelSheet]): the model, its variant and approval. A warning approval turns its dot amber.
 */
@Composable
private fun ModelChip(state: ServerState, actions: ConsoleActions) {
    val colors = ConsoleColors
    var open by remember { mutableStateOf(false) }
    val label = state.providerLabel.ifBlank { state.configEditor.model }.ifBlank { stringResource(R.string.console_choose_model) }
    Row(
        Modifier
            .widthIn(max = CHIP_MAX_WIDTH)
            .clip(CircleShape)
            .background(colors.panel)
            .clickable(role = Role.Button) { open = true }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = if (state.confirmationMode.warning) colors.warning else colors.accent, modifier = Modifier.size(6.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = PhoneType.small, color = colors.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (open) PhoneModelSheet(state, actions) { open = false }
}

/** The message, as one line that grows to a few while typing. */
@Composable
private fun PillField(text: String, onTextChange: (String) -> Unit, running: Boolean, modifier: Modifier) {
    val colors = ConsoleColors
    // While a run goes on, what is typed steers it.
    val placeholder = stringResource(if (running) R.string.phone_composer_running else R.string.phone_composer_hint)
    BasicTextField(
        value = text,
        onValueChange = onTextChange,
        maxLines = FIELD_MAX_LINES,
        textStyle = PhoneType.body.copy(color = colors.text),
        cursorBrush = SolidColor(colors.accent),
        modifier = modifier,
        decorationBox = { inner ->
            Box(Modifier.heightIn(min = SEND_SIZE).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text(placeholder, style = PhoneType.body, color = colors.muted)
                inner()
            }
        },
    )
}

/** What a hold has heard: its closed sentences, then the one still open. */
internal fun heldText(voice: VoiceView): String =
    (voice.segments.map { it.text } + voice.liveText).map(String::trim).filter(String::isNotEmpty).joinToString(" ")

/**
 * One quiet line over the pill: a notice (a hint, a refused start), a hand-over, or how a session that is not a hold is
 * listening. A notice clears itself after a few seconds.
 */
@Composable
private fun PhoneVoiceLine(voice: VoiceView, holding: Boolean) {
    val colors = ConsoleColors
    val server = LocalServer.current
    val shown = voice.notice
    LaunchedEffect(shown) {
        if (shown == null) return@LaunchedEffect
        delay(NOTICE_MILLIS)
        server.send(VoiceRequests.CLEAR_NOTICE, shown)
    }
    HandOverNotice()
    val notice = shown?.let { words(it) }.orEmpty()
    val problem = voice.problem?.let { words(it) }.orEmpty()
    val status = if (voice.status.isOn && !holding) voiceStatusLine(voice) else ""
    val line = notice.ifEmpty { problem.ifEmpty { status } }
    if (line.isEmpty()) return
    Text(
        text = line,
        style = PhoneType.label,
        color = if (notice.isEmpty() && problem.isNotEmpty()) colors.danger else colors.muted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** Send, an arrow up on pine green, faint until something is typed; it steers a run going on. The Run/Steer description is what automation looks for. */
@Composable
private fun SendButton(enabled: Boolean, steering: Boolean, onSend: () -> Unit) {
    val colors = ConsoleColors
    Box(
        Modifier
            .size(SEND_SIZE)
            .clip(CircleShape)
            .background(colors.accent.copy(alpha = if (enabled) 1f else IDLE_SEND_ALPHA))
            .clickable(enabled = enabled, onClick = onSend)
            .semantics {
                role = Role.Button
                contentDescription = if (steering) "Steer" else "Run"
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_phone_send), contentDescription = null, tint = onAccent(), modifier = Modifier.size(SEND_ICON_SIZE))
    }
}

/** Send's place while a run goes on and nothing is typed: stops the run. */
@Composable
private fun StopButton(onStop: () -> Unit) {
    val colors = ConsoleColors
    val label = stringResource(R.string.console_stop)
    Box(
        Modifier
            .size(SEND_SIZE)
            .clip(CircleShape)
            .background(colors.accent)
            .clickable(onClick = onStop)
            .semantics {
                role = Role.Button
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(STOP_SQUARE).clip(RoundedCornerShape(3.dp)).background(onAccent()))
    }
}

/** What sits on the accent: white on the light paper, the ground in the dark. */
@Composable
internal fun onAccent(): Color = if (ConsoleColors.isDark) ConsoleColors.bg else Color.White

private val PILL_SHADOW = 3.dp
private val SEND_SIZE = 40.dp
private val SEND_ICON_SIZE = 20.dp
private val MIC_SIZE = 36.dp
private val MIC_ICON_SIZE = 20.dp
private val CHIP_MAX_WIDTH = 120.dp
private val STOP_SQUARE = 13.dp
private const val IDLE_SEND_ALPHA = 0.35f
private const val NOTICE_MILLIS = 5_000L
private const val FIELD_MAX_LINES = 5
