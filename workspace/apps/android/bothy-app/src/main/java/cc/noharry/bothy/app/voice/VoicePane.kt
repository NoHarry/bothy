package cc.noharry.bothy.app.voice
import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.voice.VoiceEndReason
import cc.noharry.bothy.voice.VoiceHandOverMode
import cc.noharry.bothy.voice.VoiceJudgment
import cc.noharry.bothy.voice.VoiceSegment
import cc.noharry.bothy.voice.VoiceSegmentLabel
import cc.noharry.bothy.voice.VoiceSessionState
import cc.noharry.bothy.voice.VoiceSessionStatus
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.console.ui.IconMic
import kotlinx.coroutines.delay

/**
 * The console's voice strip, shown while listening or with something to show: the status line, the words being
 * heard with Jev's live scores, and each finished stretch marked as for the agent, side talk or uncertain. The
 * session is switched by [VoiceMicButton] next to Send.
 */
@Composable
internal fun VoiceStrip() {
    val state by VoiceController.state.collectAsState()
    val notice by VoiceController.notice.collectAsState()
    val listening = state.status.isOn
    val problem = notice.ifEmpty { state.message.orEmpty() }
    if (!listening && problem.isEmpty() && state.segments.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(bottom = Dimens.spaceSm),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
    ) {
        HandOverNotice()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
            if (listening) VoiceWaveform(state.micLevels, if (state.micActive) ConsoleColors.danger else ConsoleColors.tertiary)
            Text(
                voiceStatusLine(state),
                style = ConsoleType.meta,
                color = ConsoleColors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (problem.isNotEmpty()) Text(problem, style = ConsoleType.bodySmall, color = ConsoleColors.danger)
        if (listening && state.liveText.isNotEmpty()) {
            // Open-mic chatter can run long; the newest words matter.
            val live = state.liveText.let { if (it.length > LIVE_TEXT_CHARS) "…" + it.takeLast(LIVE_TEXT_CHARS) else it }
            Text("$live  ${scoresLine(state.liveJudgment)}", style = ConsoleType.bodySmall, color = ConsoleColors.text)
        }
        if (state.segments.isNotEmpty()) {
            val list = rememberLazyListState()
            LaunchedEffect(state.segments.size) { list.animateScrollToItem(state.segments.lastIndex) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 168.dp), state = list) {
                items(state.segments, key = VoiceSegment::id) { segment -> VoiceSegmentRow(segment) }
            }
        }
    }
}

/**
 * The mic beside Send, as in Codex: tap to wake, tap again to end. Off: a soft mic. Listening: a red stop button
 * whose ring breathes while the mic hears speech; amber while half duplex waits for the agent. Asks for the mic the
 * first time.
 */
@Composable
internal fun VoiceMicButton(size: Dp, iconSize: Dp) {
    val context = LocalContext.current
    val state by VoiceController.state.collectAsState()
    val denied = stringResource(R.string.voice_mic_denied)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) VoiceController.toggle(context) else VoiceController.notice(denied)
    }
    val colors = ConsoleColors
    val on = state.status.isOn
    val description = stringResource(if (on) R.string.voice_mic_stop else R.string.voice_mic_start)
    val fill = when {
        !on -> colors.accentSoft
        state.status == VoiceSessionStatus.BUSY -> colors.warning
        else -> colors.danger
    }
    // The ring grows with loudness, drawn outside the button so the row never shifts.
    val level by animateFloatAsState(if (on) state.micLevels.takeLast(LEVEL_RING_SLICES).maxOrNull() ?: 0f else 0f, tween(LEVEL_EASE_MILLIS), label = "micLevel")
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(size)
                .drawBehind {
                    if (level > 0f) drawCircle(fill.copy(alpha = LEVEL_RING_ALPHA), radius = this.size.minDimension / 2 + LEVEL_RING_MAX.toPx() * level)
                }
                .clip(CircleShape)
                .background(fill)
                .clickable {
                    val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (on || granted) VoiceController.toggle(context) else permission.launch(Manifest.permission.RECORD_AUDIO)
                }
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            if (on) {
                // A stop square: tapping ends listening.
                Box(Modifier.size(iconSize * STOP_SQUARE_SHARE).clip(RoundedCornerShape(2.dp)).background(Color.White))
            } else {
                Icon(IconMic, contentDescription = null, tint = colors.accent, modifier = Modifier.size(iconSize))
            }
        }
    }
}

/** How the last words reached a working agent, for a few seconds: appended, replacing its task, or too late. */
@Composable
private fun HandOverNotice() {
    val notice by VoiceFeedback.notice.collectAsState()
    var shown by remember { mutableStateOf<VoiceHandOverNotice?>(null) }
    LaunchedEffect(notice) {
        // Only fresh notices: the strip may reappear long after the last hand-over.
        val age = notice?.let { SystemClock.elapsedRealtime() - it.atMillis } ?: return@LaunchedEffect
        if (age >= HAND_OVER_NOTICE_MILLIS) return@LaunchedEffect
        shown = notice
        delay(HAND_OVER_NOTICE_MILLIS - age)
        shown = null
    }
    AnimatedVisibility(visible = shown != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        val current = shown ?: notice ?: return@AnimatedVisibility
        val (title, color) = when {
            current.fellBack -> R.string.voice_handover_new_turn_notice to ConsoleColors.muted
            current.mode == VoiceHandOverMode.APPEND -> R.string.voice_handover_append_notice to ConsoleColors.accent
            else -> R.string.voice_handover_replace_notice to ConsoleColors.warning
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.radiusSm)).background(color.copy(alpha = 0.12f))
                .padding(horizontal = Dimens.spaceSm, vertical = Dimens.spaceXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
        ) {
            Text(stringResource(title), style = ConsoleType.label, color = color)
            Text(
                current.ack?.let { stringResource(R.string.voice_handover_with_ack, current.text, it) } ?: current.text,
                style = ConsoleType.bodySmall,
                color = ConsoleColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Live loudness as bars mirrored about the middle, newest on the right: speech visibly moves them, silence leaves a
 * flat line, so it is clear whether the mic is picking anything up.
 */
@Composable
private fun VoiceWaveform(levels: List<Float>, color: Color) {
    Canvas(Modifier.width(WAVE_WIDTH).height(WAVE_HEIGHT)) {
        val step = this.size.width / WAVE_BARS
        val barWidth = step * WAVE_BAR_SHARE
        val shown = levels.takeLast(WAVE_BARS)
        val offset = WAVE_BARS - shown.size
        shown.forEachIndexed { index, level ->
            val height = (this.size.height * level).coerceAtLeast(WAVE_MIN_BAR.toPx())
            drawRoundRect(
                color = color,
                topLeft = Offset((offset + index) * step + (step - barWidth) / 2, (this.size.height - height) / 2),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

private val VoiceSessionStatus.isOn: Boolean
    get() = this == VoiceSessionStatus.STARTING || this == VoiceSessionStatus.LISTENING || this == VoiceSessionStatus.BUSY

@Composable
private fun VoiceSegmentRow(segment: VoiceSegment) {
    val (label, color) = when {
        segment.dismissed -> stringResource(R.string.voice_segment_dismissed) to ConsoleColors.muted
        segment.reason == VoiceEndReason.ECHO -> stringResource(R.string.voice_segment_echo) to ConsoleColors.tertiary
        else -> segmentBadge(segment.label)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = Dimens.spaceXxs), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = ConsoleType.meta,
            color = color,
            modifier = Modifier
                .clip(RoundedCornerShape(Dimens.radiusSm))
                .background(color.copy(alpha = 0.12f))
                .padding(horizontal = Dimens.spaceXsMd, vertical = Dimens.spaceXxs),
        )
        Column(Modifier.weight(1f).padding(start = Dimens.spaceSm)) {
            Text(segment.text, style = ConsoleType.bodySmall, color = ConsoleColors.text)
            Text(
                listOfNotNull(
                    scoresLine(segment.judgment).takeIf { it.isNotEmpty() },
                    stringResource(endReasonLabel(segment.reason)),
                    when (segment.handOver) {
                        VoiceHandOverMode.APPEND -> stringResource(R.string.voice_segment_appended)
                        VoiceHandOverMode.REPLACE -> stringResource(R.string.voice_segment_replaced)
                        VoiceHandOverMode.NEW_TURN -> stringResource(R.string.voice_segment_handed)
                        null -> null
                    },
                ).joinToString(" · "),
                style = ConsoleType.meta,
                color = ConsoleColors.muted,
            )
        }
    }
}

@Composable
private fun segmentBadge(label: VoiceSegmentLabel): Pair<String, Color> = when (label) {
    VoiceSegmentLabel.AGENT -> stringResource(R.string.voice_badge_agent) to ConsoleColors.success
    VoiceSegmentLabel.SIDE -> stringResource(R.string.voice_badge_side) to ConsoleColors.muted
    VoiceSegmentLabel.UNCERTAIN -> stringResource(R.string.voice_badge_uncertain) to ConsoleColors.warning
}

@Composable
internal fun voiceStatusLine(state: VoiceSessionState): String {
    val phase = when (state.status) {
        VoiceSessionStatus.IDLE -> R.string.voice_status_idle
        VoiceSessionStatus.STARTING -> R.string.voice_status_starting
        VoiceSessionStatus.LISTENING -> when {
            state.assistantSpeaking -> R.string.voice_status_speaking
            state.agentWorking -> R.string.voice_status_agent_listening
            else -> R.string.voice_status_listening
        }
        VoiceSessionStatus.BUSY -> R.string.voice_status_agent_busy
        VoiceSessionStatus.ERROR -> R.string.voice_status_stopped
    }
    val on = state.status != VoiceSessionStatus.IDLE
    val effects = state.audioEffects
    val duplex = when {
        !on -> null
        state.fullDuplex -> R.string.voice_full_duplex
        else -> R.string.voice_half_duplex
    }
    val effect = effects?.let {
        when {
            it.echoGate -> R.string.voice_echo_gate
            it.echoCanceller -> R.string.voice_aec_on
            else -> R.string.voice_aec_off
        }
    }?.takeIf { on }
    return listOfNotNull(
        stringResource(phase),
        state.engineLabel.takeIf { it.isNotEmpty() && on },
        duplex?.let { stringResource(it) },
        effect?.let { stringResource(it) },
        pluralStringResource(R.plurals.voice_jev_calls, state.judgeCount, state.judgeCount)
            .takeIf { state.judgeCount > 0 },
        stringResource(R.string.voice_judge_failures, state.judgeFailures).takeIf { state.judgeFailures > 0 },
        state.lastJudgeLatencyMillis?.let { "${it}ms" },
        pluralStringResource(R.plurals.voice_echoes, state.echoes, state.echoes).takeIf { state.echoes > 0 },
        pluralStringResource(R.plurals.voice_interruptions, state.interruptions, state.interruptions)
            .takeIf { state.interruptions > 0 },
    ).joinToString(" · ")
}

@Composable
internal fun scoresLine(judgment: VoiceJudgment?): String = judgment?.let {
    listOfNotNull(
        stringResource(R.string.voice_score_complete, it.turnComplete),
        stringResource(R.string.voice_score_holding, it.holdingFloor),
        stringResource(R.string.voice_score_addressed, it.addressedToAgent),
        it.takingFloor?.let { score -> stringResource(R.string.voice_score_taking, score) },
        it.replacesTask?.let { score -> stringResource(R.string.voice_score_replaces, score) },
    ).joinToString(" · ")
}.orEmpty()

@StringRes
private fun endReasonLabel(reason: VoiceEndReason): Int = when (reason) {
    VoiceEndReason.JUDGED -> R.string.voice_end_judged
    VoiceEndReason.SILENCE_CAP -> R.string.voice_end_silence_cap
    VoiceEndReason.JUDGE_FAILED -> R.string.voice_end_judge_failed
    VoiceEndReason.SIDE_TALK -> R.string.voice_end_side_talk
    VoiceEndReason.LENGTH_CAP -> R.string.voice_end_length_cap
    VoiceEndReason.ECHO -> R.string.voice_end_echo
    VoiceEndReason.BACKCHANNEL -> R.string.voice_end_backchannel
    VoiceEndReason.WAKE_PHRASE -> R.string.voice_end_wake_phrase
    VoiceEndReason.FAR_SPEECH -> R.string.voice_end_far_speech
    VoiceEndReason.DISMISSED -> R.string.voice_end_dismissed
}

private const val LIVE_TEXT_CHARS = 120
private const val STOP_SQUARE_SHARE = 0.6f
private const val HAND_OVER_NOTICE_MILLIS = 3_000L
/** The last 100 ms of meter drive the ring. */
private const val LEVEL_RING_SLICES = 5
private const val LEVEL_EASE_MILLIS = 90
private const val LEVEL_RING_ALPHA = 0.3f
private val LEVEL_RING_MAX = 8.dp
/** 0.8 s of meter at 50 slices per second. */
private const val WAVE_BARS = 40
private const val WAVE_BAR_SHARE = 0.6f
private val WAVE_WIDTH = 120.dp
private val WAVE_HEIGHT = 20.dp
private val WAVE_MIN_BAR = 2.dp
