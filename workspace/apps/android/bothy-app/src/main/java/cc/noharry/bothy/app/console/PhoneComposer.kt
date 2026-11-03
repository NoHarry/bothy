package cc.noharry.bothy.app.console

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.session.UiState
import cc.noharry.bothy.app.console.ui.PopupMenu
import cc.noharry.bothy.app.console.ui.PopupMenuItem
import cc.noharry.bothy.app.console.ui.PopupSectionLabel
import cc.noharry.bothy.app.console.ui.SelectorChip
import cc.noharry.bothy.app.voice.HandOverNotice
import cc.noharry.bothy.app.voice.VoiceController
import cc.noharry.bothy.app.voice.VoiceMicButton
import cc.noharry.bothy.app.voice.isOn
import cc.noharry.bothy.app.voice.voiceStatusLine
import cc.noharry.bothy.voice.VoiceSessionState
import kotlinx.coroutines.delay

/**
 * A portrait phone's composer, as the phone design draws it: one pill with the conversation's options, the message,
 * the mic held to talk and send (stop while a run goes on and nothing is typed). While the mic is held the pill turns
 * green and shows what is heard, and letting go sends it.
 */
@Composable
internal fun PhoneComposer(
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    state: UiState,
    actions: ConsoleActions,
) {
    val colors = ConsoleColors
    val voice by VoiceController.state.collectAsState()
    // Read on each change of the voice state: a hold starts and ends with one.
    val holding = voice.status.isOn && VoiceController.holding
    Column(Modifier.fillMaxWidth()) {
        PhoneVoiceLine(voice, holding)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(PILL_RADIUS),
            color = if (holding) colors.accentSoft else colors.elevated,
            border = BorderStroke(1.dp, if (holding) colors.accent.copy(alpha = HELD_BORDER_ALPHA) else colors.borderSubtle),
        ) {
            Row(
                Modifier.padding(PILL_PADDING),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (holding) {
                    HeldWords(voice, Modifier.weight(1f))
                } else {
                    ComposerOptions(state, actions)
                    MessageField(
                        text, onTextChange, singleLine = false, modifier = Modifier.weight(1f),
                        placeholder = stringResource(R.string.console_message_placeholder_short), minLines = 1,
                    )
                }
                VoiceMicButton(PHONE_BUTTON_SIZE, PHONE_ICON_SIZE, holdToTalk = true)
                if (state.isRunning && text.isBlank()) {
                    StopButton(actions.stop)
                } else {
                    SubmitButton(text, onSubmit, state.isRunning, PHONE_BUTTON_SIZE, PHONE_ICON_SIZE)
                }
            }
        }
    }
}

/**
 * The chip at the pill's start, named for the model service (the first word of its profile's name): approval, the
 * variant and the composer's actions, which the wide composer shows as separate chips. A warning approval keeps its
 * amber dot on the chip.
 */
@Composable
private fun ComposerOptions(state: UiState, actions: ConsoleActions) {
    var expanded by remember { mutableStateOf(false) }
    val editor = state.configEditor
    val mode = state.confirmationMode
    Box {
        SelectorChip(
            label = editor.profileLabel.substringBefore(' ').ifBlank { stringResource(R.string.console_model) },
            onClick = { expanded = true },
            modifier = Modifier.widthIn(max = OPTIONS_CHIP_MAX_WIDTH),
            leadingDot = ConsoleColors.warning.takeIf { mode.warning },
        )
        PopupMenu(expanded = expanded, onDismissRequest = { expanded = false }, minWidth = OPTIONS_MENU_MIN_WIDTH, maxWidth = OPTIONS_MENU_MAX_WIDTH) {
            val close = { expanded = false }
            ConfirmationModeItems(mode, actions, compact = true, onPicked = close)
            VariantItems(editor, actions, onPicked = close)
            PopupSectionLabel(stringResource(R.string.console_actions))
            PopupMenuItem(label = stringResource(R.string.console_floating_card), selected = false, onClick = { close(); actions.openOverlay() })
            PopupMenuItem(label = stringResource(R.string.console_toggle_stream), selected = false, onClick = { close(); actions.toggleStream() })
        }
    }
}

/** While the mic is held: the level, what is heard so far (its newest words), and that letting go sends it. */
@Composable
private fun HeldWords(voice: VoiceSessionState, modifier: Modifier) {
    val colors = ConsoleColors
    Row(modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LevelBars(voice.micLevels, colors.accent)
        val heard = heldText(voice)
        Text(
            text = heard.ifBlank { stringResource(R.string.voice_hold_listening) },
            style = ConsoleType.body,
            color = if (heard.isBlank()) colors.muted else colors.text,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.weight(1f),
        )
        Text(stringResource(R.string.voice_hold_release), style = ConsoleType.meta, color = colors.accent, fontWeight = FontWeight.SemiBold)
    }
}

/** What a hold has heard: its closed sentences, then the one still open. */
internal fun heldText(voice: VoiceSessionState): String =
    (voice.segments.map { it.text } + voice.liveText).map(String::trim).filter(String::isNotEmpty).joinToString(" ")

/** The newest loudness as a row of bars. */
@Composable
private fun LevelBars(levels: List<Float>, color: Color) {
    val recent = levels.takeLast(LEVEL_BARS)
    Canvas(Modifier.width(LEVEL_BARS_WIDTH).height(LEVEL_BARS_HEIGHT)) {
        val step = size.width / LEVEL_BARS
        val bar = step * BAR_SHARE
        recent.forEachIndexed { index, level ->
            val h = size.height * (MIN_BAR + (1f - MIN_BAR) * level.coerceIn(0f, 1f))
            drawRoundRect(
                color = color,
                topLeft = Offset(index * step + (step - bar) / 2, (size.height - h) / 2),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}

/**
 * One quiet line over the pill: a notice (a hint, a refused start), a hand-over, or how a session that is not a hold is
 * listening. A notice clears itself after a few seconds.
 */
@Composable
private fun PhoneVoiceLine(voice: VoiceSessionState, holding: Boolean) {
    val colors = ConsoleColors
    val notice by VoiceController.notice.collectAsState()
    LaunchedEffect(notice) {
        if (notice.isEmpty()) return@LaunchedEffect
        delay(NOTICE_MILLIS)
        if (VoiceController.notice.value == notice) VoiceController.notice("")
    }
    HandOverNotice()
    val problem = voice.message.orEmpty()
    val status = if (voice.status.isOn && !holding) voiceStatusLine(voice) else ""
    val line = notice.ifEmpty { problem.ifEmpty { status } }
    if (line.isEmpty()) return
    Text(
        text = line,
        style = ConsoleType.bodySmall,
        color = if (notice.isEmpty() && problem.isNotEmpty()) colors.danger else colors.muted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** Send's place while a run goes on and nothing is typed: stops the run. */
@Composable
private fun StopButton(onStop: () -> Unit) {
    val colors = ConsoleColors
    val label = stringResource(R.string.console_stop)
    Box(
        Modifier
            .size(PHONE_BUTTON_SIZE)
            .clip(CircleShape)
            .background(colors.accent)
            .clickable(onClick = onStop)
            .semantics {
                role = Role.Button
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(STOP_SQUARE).clip(RoundedCornerShape(3.dp)).background(if (colors.isDark) colors.bg else Color.White))
    }
}

private val PILL_RADIUS = 28.dp
private val PILL_PADDING = 6.dp
private val PHONE_BUTTON_SIZE = 44.dp
private val PHONE_ICON_SIZE = 22.dp
private val STOP_SQUARE = 14.dp
private val OPTIONS_CHIP_MAX_WIDTH = 140.dp
private val OPTIONS_MENU_MIN_WIDTH = 240.dp
private val OPTIONS_MENU_MAX_WIDTH = 320.dp
private val LEVEL_BARS_WIDTH = 56.dp
private val LEVEL_BARS_HEIGHT = 24.dp
private const val LEVEL_BARS = 14
private const val BAR_SHARE = 0.5f
private const val MIN_BAR = 0.15f
private const val HELD_BORDER_ALPHA = 0.35f
private const val NOTICE_MILLIS = 5_000L
