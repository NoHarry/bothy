package cc.noharry.bothy.sample

import kotlinx.coroutines.delay
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

/**
 * The console's voice strip, shown while listening or with something to show: the status line, the words being
 * heard with Jev's live scores, and each finished stretch marked as for the agent, side talk or uncertain. The
 * session is switched by [DebugVoiceMicButton] next to Send.
 */
@Composable
internal fun DebugVoiceStrip() {
    val state by DebugVoiceController.state.collectAsState()
    val notice by DebugVoiceController.notice.collectAsState()
    val listening = state.status.isOn
    val problem = notice.ifEmpty { state.message.orEmpty() }
    if (!listening && problem.isEmpty() && state.segments.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(bottom = DebugDimens.spaceSm),
        verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceXs),
    ) {
        HandOverNotice()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm)) {
            if (listening) VoiceWaveform(state.micLevels, if (state.micActive) DebugColors.danger else DebugColors.tertiary)
            Text(
                voiceStatusLine(state),
                style = DebugType.meta,
                color = DebugColors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (problem.isNotEmpty()) Text(problem, style = DebugType.bodySmall, color = DebugColors.danger)
        if (listening && state.liveText.isNotEmpty()) {
            // Open-mic chatter can run long; the newest words matter.
            val live = state.liveText.let { if (it.length > LIVE_TEXT_CHARS) "…" + it.takeLast(LIVE_TEXT_CHARS) else it }
            Text("$live  ${scoresLine(state.liveJudgment)}", style = DebugType.bodySmall, color = DebugColors.text)
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
internal fun DebugVoiceMicButton(size: Dp, iconSize: Dp) {
    val context = LocalContext.current
    val state by DebugVoiceController.state.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) DebugVoiceController.toggle(context) else DebugVoiceController.notice("未授予麦克风权限")
    }
    val colors = DebugColors
    val on = state.status.isOn
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
                    if (on || granted) DebugVoiceController.toggle(context) else permission.launch(Manifest.permission.RECORD_AUDIO)
                }
                .semantics { contentDescription = if (on) "结束语音" else "语音唤醒" },
            contentAlignment = Alignment.Center,
        ) {
            if (on) {
                // A stop square: tapping ends listening.
                Box(Modifier.size(iconSize * STOP_SQUARE_SHARE).clip(RoundedCornerShape(2.dp)).background(Color.White))
            } else {
                Icon(DebugIconMic, contentDescription = null, tint = colors.accent, modifier = Modifier.size(iconSize))
            }
        }
    }
}

/** How the last words reached a working agent, for a few seconds: appended, replacing its task, or too late. */
@Composable
private fun HandOverNotice() {
    val notice by DebugVoiceFeedback.notice.collectAsState()
    var shown by remember { mutableStateOf<DebugVoiceHandOverNotice?>(null) }
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
            current.fellBack -> "当前任务已结束，作为新的一轮" to DebugColors.muted
            current.mode == VoiceHandOverMode.APPEND -> "＋ 已追加到当前任务" to DebugColors.accent
            else -> "↻ 已打断当前任务，按新问题处理" to DebugColors.warning
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(DebugDimens.radiusSm)).background(color.copy(alpha = 0.12f))
                .padding(horizontal = DebugDimens.spaceSm, vertical = DebugDimens.spaceXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm),
        ) {
            Text(title, style = DebugType.label, color = color)
            Text(
                current.ack?.let { "${current.text} · 小博：$it" } ?: current.text,
                style = DebugType.bodySmall,
                color = DebugColors.text,
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
        segment.dismissed -> "退出语音" to DebugColors.muted
        segment.reason == VoiceEndReason.ECHO -> "回声" to DebugColors.tertiary
        else -> segmentBadge(segment.label)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = DebugDimens.spaceXxs), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = DebugType.meta,
            color = color,
            modifier = Modifier
                .clip(RoundedCornerShape(DebugDimens.radiusSm))
                .background(color.copy(alpha = 0.12f))
                .padding(horizontal = DebugDimens.spaceXsMd, vertical = DebugDimens.spaceXxs),
        )
        Column(Modifier.weight(1f).padding(start = DebugDimens.spaceSm)) {
            Text(segment.text, style = DebugType.bodySmall, color = DebugColors.text)
            Text(
                listOfNotNull(
                    scoresLine(segment.judgment).takeIf { it.isNotEmpty() },
                    endReasonLabel(segment.reason),
                    when (segment.handOver) {
                        VoiceHandOverMode.APPEND -> "已追加到当前任务"
                        VoiceHandOverMode.REPLACE -> "已打断当前任务，作为新问题"
                        VoiceHandOverMode.NEW_TURN -> "已交给 Agent"
                        null -> null
                    },
                ).joinToString(" · "),
                style = DebugType.meta,
                color = DebugColors.muted,
            )
        }
    }
}

@Composable
private fun segmentBadge(label: VoiceSegmentLabel): Pair<String, Color> = when (label) {
    VoiceSegmentLabel.AGENT -> "面向 Agent" to DebugColors.success
    VoiceSegmentLabel.SIDE -> "人人对话" to DebugColors.muted
    VoiceSegmentLabel.UNCERTAIN -> "不确定" to DebugColors.warning
}

internal fun voiceStatusLine(state: VoiceSessionState): String {
    val phase = when (state.status) {
        VoiceSessionStatus.IDLE -> "未监听"
        VoiceSessionStatus.STARTING -> "正在启动"
        VoiceSessionStatus.LISTENING -> when {
            state.assistantSpeaking -> "播报中 · 可打断"
            state.agentWorking -> "Agent 处理中 · 继续收音"
            else -> "监听中"
        }
        VoiceSessionStatus.BUSY -> "Agent 处理中 · 暂停判断"
        VoiceSessionStatus.ERROR -> "已停止"
    }
    val on = state.status != VoiceSessionStatus.IDLE
    val effects = state.audioEffects
    val duplex = when {
        !on -> null
        state.fullDuplex -> "全双工"
        else -> "半双工"
    }
    return listOfNotNull(
        phase,
        state.engineLabel.takeIf { it.isNotEmpty() && on },
        duplex,
        effects?.let { if (it.echoGate) "参考回声门限" else if (it.echoCanceller) "AEC 开" else "AEC 关" }?.takeIf { on },
        "Jev ${state.judgeCount} 次".takeIf { state.judgeCount > 0 },
        "失败 ${state.judgeFailures}".takeIf { state.judgeFailures > 0 },
        state.lastJudgeLatencyMillis?.let { "${it}ms" },
        "回声 ${state.echoes}".takeIf { state.echoes > 0 },
        "打断 ${state.interruptions}".takeIf { state.interruptions > 0 },
    ).joinToString(" · ")
}

internal fun scoresLine(judgment: VoiceJudgment?): String = judgment?.let {
    listOfNotNull(
        "说完 %.2f".format(it.turnComplete),
        "等待 %.2f".format(it.holdingFloor),
        "对小博 %.2f".format(it.addressedToAgent),
        it.takingFloor?.let { score -> "插话 %.2f".format(score) },
        it.replacesTask?.let { score -> "换任务 %.2f".format(score) },
    ).joinToString(" · ")
}.orEmpty()

private fun endReasonLabel(reason: VoiceEndReason): String = when (reason) {
    VoiceEndReason.JUDGED -> "Jev 判定说完"
    VoiceEndReason.SILENCE_CAP -> "静音超时"
    VoiceEndReason.JUDGE_FAILED -> "Jev 不可用"
    VoiceEndReason.SIDE_TALK -> "旁聊断句"
    VoiceEndReason.LENGTH_CAP -> "单轮超长"
    VoiceEndReason.ECHO -> "播报回声"
    VoiceEndReason.BACKCHANNEL -> "播报中附和，未交给 Agent"
    VoiceEndReason.WAKE_PHRASE -> "听到唤醒词，前面的话不交给 Agent"
    VoiceEndReason.FAR_SPEECH -> "比唤醒时轻得多，像是旁人或视频，未交给 Agent"
    VoiceEndReason.DISMISSED -> "只说了「退出」这类话，直接结束对话"
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
