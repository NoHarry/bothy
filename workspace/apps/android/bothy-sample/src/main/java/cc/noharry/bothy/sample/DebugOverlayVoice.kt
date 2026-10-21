package cc.noharry.bothy.sample

import android.content.Context
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.voice.VoiceHandOverMode
import cc.noharry.bothy.voice.VoiceSegmentLabel
import cc.noharry.bothy.voice.VoiceSessionState
import cc.noharry.bothy.voice.VoiceSessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The overlay's voice (owner 2026-10-11). With the switch on (Settings → 语音 → 语音唤醒, off by default), the
 * wake word 你好，小博 or a long press on 小博 starts a voice session, 小博 answers at once ([DebugWakeAck]) and
 * [onWoken] opens the card, whose request line then shows what is heard, while [VoiceAura] glows with the loudness. A
 * wake while 小博 works, speaks or hears a turn stops all that and listens anew. The session ends by itself when idle or
 * when the user closes the card; folding for Android Use does not end it. The microphone stays usable behind other
 * apps through [DebugVoiceForegroundService]. Main thread.
 */
internal class DebugOverlayVoice(
    private val service: Service,
    private val onWoken: () -> Unit,
    private val onChanged: () -> Unit,
    /** The speaker dismissed a woken session by voice ("退下吧"): the card folds. */
    private val onDismissed: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Why the last wake could not listen; shown in the voice slot until the card closes or the next wake. */
    var failure: String? = null
        private set
    private var forA2a = false

    init {
        DebugWakeWord.attach(service, ::wake)
        DebugWakeAck.prepare(service)
        DebugOverlayControl.waker = ::wake
        scope.launch { DebugVoiceController.active.collect { onChanged() } }
        scope.launch { DebugVoiceController.dismissals.collect { failure = null; onDismissed() } }
    }

    /** The card's request line (and the Android Use caption) belong to voice. */
    val shown: Boolean get() = DebugVoiceController.listening || failure != null

    /** A long press on 小博 wakes when the switch is on; false keeps the old meaning (小博 goes to sleep). */
    fun longPress(): Boolean {
        if (!DebugWakeWord.enabled.value) return false
        wake(DebugWakeSource.LONG_PRESS)
        return true
    }

    fun wake(source: DebugWakeSource) {
        failure = startWokenVoice(service, source)
        onWoken()
    }

    /** The user closed the card: voice ends with it ([DebugVoiceController.closedByHand]). */
    fun cardClosed() {
        failure = null
        DebugVoiceController.closedByHand()
    }

    /** Started from the background for an A2A caller: it must run in the foreground anyway. */
    fun promoteForA2a() {
        if (forA2a) return
        forA2a = service.startOverlayForeground()
    }

    fun destroy() {
        scope.cancel()
        DebugOverlayControl.waker = null
        DebugWakeWord.detach()
        if (DebugVoiceController.wakeSession) DebugVoiceController.stop()
    }

}

/**
 * A wake (你好，小博, a long press, adb): stops what 小博 is doing, answers, and starts (or restarts) a woken session.
 * The overlay shows it on its card; without the overlay the console's voice strip does. Why it could not listen, or null.
 */
internal fun startWokenVoice(context: Context, source: DebugWakeSource): String? {
    val interrupted = DebugVoiceController.interruptForWake(context)
    // Answered before listening is set up, which takes a few hundred milliseconds; taken back if it cannot listen.
    val answered = DebugWakeAck.play(context, interrupted)
    val settingUp = SystemClock.elapsedRealtime()
    val failure = if (DebugVoiceController.listening) {
        DebugVoiceController.restart(context)
    } else {
        DebugVoiceController.start(context, DebugVoiceOrigin.WAKE)
    }
    if (failure != null && answered) DebugTtsActivity.interrupt()
    if (failure == null && !answered) DebugVoiceFeedback.woke()
    val took = SystemClock.elapsedRealtime() - settingUp
    DebugVoiceLog.i("DebugVoice", "overlay woken by $source${if (interrupted) " (interrupting)" else ""}, listening set up in $took ms${failure?.let { ": $it" }.orEmpty()}")
    return failure
}

/** Runs the overlay as a foreground service (an A2A caller started it from the background); false when refused. */
internal fun Service.startOverlayForeground(): Boolean = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(OVERLAY_CHANNEL, "小博悬浮窗", NotificationManager.IMPORTANCE_MIN))
        val notification = Notification.Builder(this, OVERLAY_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("小博悬浮窗")
            .setContentText("响应 A2A 请求")
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(OVERLAY_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(OVERLAY_NOTIFICATION_ID, notification)
        }
    }
    true
} catch (refused: RuntimeException) {
    Log.w(TAG, "overlay foreground refused: ${refused.message}")
    false
}

/** What the voice slot says, and in which voice. */
internal enum class OverlayVoiceTone { LIVE, HINT, SENT, IGNORED, WARN }

internal data class OverlayVoiceLine(val text: String, val tone: OverlayVoiceTone)

/**
 * One line for the voice slot: the words being recognized as they come; for a few seconds after a stretch ends,
 * what became of it (sent, appended, replaced, or not taken as an instruction); otherwise a prompt to speak.
 */
internal fun overlayVoiceLine(state: VoiceSessionState, failure: String?, nowMillis: Long): OverlayVoiceLine {
    if (state.status == VoiceSessionStatus.IDLE || state.status == VoiceSessionStatus.ERROR) {
        return failure?.let { OverlayVoiceLine(it, OverlayVoiceTone.WARN) }
            ?: state.message?.takeIf { state.status == VoiceSessionStatus.ERROR }?.let { OverlayVoiceLine(it, OverlayVoiceTone.WARN) }
            ?: OverlayVoiceLine("语音已结束", OverlayVoiceTone.HINT)
    }
    if (state.liveText.isNotBlank()) return OverlayVoiceLine(state.liveText.takeLast(MAX_LIVE_CHARS), OverlayVoiceTone.LIVE)
    val recent = state.segments.lastOrNull()?.takeIf { nowMillis - it.endedAtEpochMillis < RECENT_SEGMENT_MILLIS }
    if (recent != null) {
        val text = recent.text.takeLast(MAX_LIVE_CHARS)
        return when {
            recent.label == VoiceSegmentLabel.AGENT && recent.handOver == VoiceHandOverMode.APPEND -> OverlayVoiceLine("已追加：$text", OverlayVoiceTone.SENT)
            recent.label == VoiceSegmentLabel.AGENT && recent.handOver == VoiceHandOverMode.REPLACE -> OverlayVoiceLine("换成：$text", OverlayVoiceTone.SENT)
            recent.label == VoiceSegmentLabel.AGENT -> OverlayVoiceLine(text, OverlayVoiceTone.SENT)
            recent.label == VoiceSegmentLabel.SIDE -> OverlayVoiceLine("没当成指令：$text", OverlayVoiceTone.IGNORED)
            else -> OverlayVoiceLine("没听出是对我说的：$text", OverlayVoiceTone.IGNORED)
        }
    }
    return when {
        state.status == VoiceSessionStatus.STARTING -> OverlayVoiceLine("正在打开麦克风…", OverlayVoiceTone.HINT)
        state.assistantSpeaking && !state.fullDuplex -> OverlayVoiceLine("我说完再听你说", OverlayVoiceTone.HINT)
        state.agentWorking -> OverlayVoiceLine("在听，可以补充或换个要求", OverlayVoiceTone.HINT)
        else -> OverlayVoiceLine("我在听，请说", OverlayVoiceTone.HINT)
    }
}

/**
 * The card's request line while voice is on, in its usual place and type: the words as they are recognized, a stretch
 * that was not taken as an instruction, why a wake could not listen, else the request being answered (or a prompt).
 * It reads the session directly, so the card itself never re-renders per word; loudness shows as [VoiceAura].
 */
@Composable
internal fun OverlayVoiceAsked(request: String?, failure: String?) {
    val state by DebugVoiceController.state.collectAsState()
    // 端到端演示模式: a turn 豆包 answered itself has no run behind it, so its words and reply show here.
    val exchange by DebugRealtimeShown.exchange.collectAsState()
    val line = voiceAskedLine(state, failure, request ?: exchange?.heard?.takeIf(String::isNotBlank), System.currentTimeMillis())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            line.text,
            color = if (line.tone == OverlayVoiceTone.SENT) OverlayPalette.secondary else line.tone.color(),
            fontSize = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { contentDescription = "语音：${line.text}" },
        )
    }
    val reply = exchange?.reply?.takeIf { it.isNotBlank() && state.liveText.isBlank() } ?: return
    Text(
        reply,
        color = OverlayPalette.text,
        fontSize = 18.sp,
        maxLines = MAX_REPLY_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.semantics { contentDescription = "回复：$reply" },
    )
}

/** What the request line says while voice is on (see [OverlayVoiceAsked]); quoted like a request. */
internal fun voiceAskedLine(state: VoiceSessionState, failure: String?, request: String?, nowMillis: Long): OverlayVoiceLine {
    val listening = state.status == VoiceSessionStatus.LISTENING || state.status == VoiceSessionStatus.BUSY
    val recent = state.segments.lastOrNull()?.takeIf { nowMillis - it.endedAtEpochMillis < RECENT_SEGMENT_MILLIS }
    return when {
        !listening && failure != null -> OverlayVoiceLine(failure, OverlayVoiceTone.WARN)
        state.liveText.isNotBlank() -> OverlayVoiceLine("“${state.liveText.takeLast(MAX_LIVE_CHARS)}”", OverlayVoiceTone.LIVE)
        recent != null && recent.label != VoiceSegmentLabel.AGENT ->
            OverlayVoiceLine("没当成指令：${recent.text.takeLast(MAX_LIVE_CHARS)}", OverlayVoiceTone.IGNORED)
        !request.isNullOrBlank() -> OverlayVoiceLine("“$request”", OverlayVoiceTone.SENT)
        else -> OverlayVoiceLine("我在听，请说", OverlayVoiceTone.HINT)
    }
}

/**
 * Android Use: the card is folded and 小博 is the pointer, flying from tap to tap, so what is heard sits still at the
 * top of the screen instead of following it. It lives in the pointer's own window, which takes no touches (taps,
 * injected ones included, pass through) and turns transparent for every screenshot the agent takes.
 */
@Composable
internal fun OverlayVoiceCaption(modifier: Modifier) {
    val state by DebugVoiceController.state.collectAsState()
    val line = overlayVoiceLine(state, null, System.currentTimeMillis())
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .padding(top = CAPTION_TOP)
            .widthIn(max = CAPTION_MAX_WIDTH)
            .background(Color(0xE60D1210), RoundedCornerShape(22.dp))
            .border(1.dp, OverlayPalette.tileEdge, RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        ListeningDot(state.micActive)
        Text(line.text, color = line.tone.color(), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        VoiceLevels(state.micLevels, Modifier.width(40.dp).height(20.dp))
    }
}

@Composable
private fun ListeningDot(hearing: Boolean) {
    val pulse by rememberInfiniteTransition(label = "listening").animateFloat(
        0.45f,
        1f,
        infiniteRepeatable(tween(DOT_PULSE_MS), RepeatMode.Reverse),
        label = "dot",
    )
    Box(
        Modifier
            .size(12.dp)
            .graphicsLayer { alpha = if (hearing) 1f else pulse }
            .background(if (hearing) OverlayPalette.glow else OverlayPalette.sage, CircleShape),
    )
}

/** The newest mic levels as bars from the centre line, so a silent mic reads flat and speech visibly moves. */
@Composable
private fun VoiceLevels(levels: List<Float>, modifier: Modifier) {
    val shown = levels.takeLast(LEVEL_BARS)
    Canvas(modifier) {
        val pitch = size.width / LEVEL_BARS
        val bar = pitch * 0.55f
        shown.forEachIndexed { index, level ->
            val height = (size.height * (0.12f + 0.88f * level)).coerceAtMost(size.height)
            drawRoundRect(
                OverlayPalette.glow.copy(alpha = 0.85f),
                Offset(index * pitch + (pitch - bar) / 2, (size.height - height) / 2),
                Size(bar, height),
                CornerRadius(bar / 2),
            )
        }
    }
}

private fun OverlayVoiceTone.color(): Color = when (this) {
    OverlayVoiceTone.LIVE, OverlayVoiceTone.SENT -> OverlayPalette.text
    OverlayVoiceTone.HINT, OverlayVoiceTone.IGNORED -> OverlayPalette.faint
    OverlayVoiceTone.WARN -> Color(0xFFF5B85C)
}

private const val TAG = "DebugVoice"
private const val OVERLAY_CHANNEL = "overlay"
private const val OVERLAY_NOTIFICATION_ID = 42
private const val MAX_LIVE_CHARS = 60
private const val MAX_REPLY_LINES = 4
private const val RECENT_SEGMENT_MILLIS = 4_000L
private const val LEVEL_BARS = 9
private const val DOT_PULSE_MS = 700
private val CAPTION_TOP = 36.dp
private val CAPTION_MAX_WIDTH = 720.dp
