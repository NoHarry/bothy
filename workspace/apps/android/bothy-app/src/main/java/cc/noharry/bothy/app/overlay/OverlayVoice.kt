package cc.noharry.bothy.app.overlay
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.os.Build
import android.util.Log
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.WakeOrigin
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.words
import cc.noharry.bothy.protocol.SegmentHandOver
import cc.noharry.bothy.protocol.SegmentLabel
import cc.noharry.bothy.protocol.VoiceRequests
import cc.noharry.bothy.protocol.VoiceStatus
import cc.noharry.bothy.protocol.VoiceView
import cc.noharry.bothy.protocol.VoiceWakeParams
import cc.noharry.bothy.protocol.voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The overlay's voice (owner 2026-10-11). A tap on 小博 ([tap]), or with the switch on (Settings → 语音 → 语音唤醒, off
 * by default) the wake word 你好，小博 or a long press, starts a voice session, 小博 answers at once ([WakeAck]) and
 * [onWoken] opens the card, whose request line then shows what is heard, while [VoiceAura] glows with the loudness. A
 * wake while 小博 works, speaks or hears a turn stops all that and listens anew. The session ends by itself when idle or
 * when the user closes the card; folding for Android Use does not end it. The microphone stays usable behind other
 * apps through [VoiceForegroundService]. Main thread.
 */
internal class OverlayVoice(
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
    private val waker: (WakeOrigin) -> Unit = { wake(it) }

    /** Promoted to the foreground for an A2A caller ([promoteForA2a]). */
    val inForeground: Boolean get() = forA2a

    init {
        OverlayControl.waker = waker
        scope.launch { OverlayConnection.state.map { it.voice.active }.distinctUntilChanged().collect { onChanged() } }
        scope.launch { OverlayConnection.dismissals.collect { failure = null; onDismissed() } }
        HostConnection.report { copy(ready = true) }
    }

    /** The card's request line (and the Android Use caption) belong to voice. */
    val shown: Boolean get() = OverlayConnection.voice.listening || failure != null

    /** A long press on 小博 wakes when the switch is on; false keeps the old meaning (小博 goes to sleep). */
    fun longPress(): Boolean {
        if (!OverlayConnection.voice.wakeWordEnabled) return false
        wake(WakeOrigin.LONG_PRESS)
        return true
    }

    /**
     * A tap on 小博 while it is not listening wakes it, whether or not the wake word is on (owner 2026-10-25: 「没唤醒的时候
     * 点击头像直接唤醒」). False when it listens already: the tap opens the card to type, as [cannotListen] does when it
     * cannot listen (said in a toast).
     */
    fun tap(cannotListen: () -> Unit): Boolean {
        if (OverlayConnection.voice.listening) return false
        OverlayConnection.send(VoiceRequests.WAKE, VoiceWakeParams(WakeOrigin.TAP, onlyIfCanListen = true)) { result ->
            if (result.started) {
                failure = result.failure?.let(service.resources::words)
                onWoken()
            } else {
                val why = result.failure?.let(service.resources::words).orEmpty()
                Toast.makeText(service, service.getString(R.string.overlay_voice_unavailable, why.trimEnd('。', '.')), Toast.LENGTH_SHORT).show()
                cannotListen()
            }
        }
        return true
    }

    fun wake(origin: WakeOrigin) {
        OverlayConnection.send(VoiceRequests.WAKE, VoiceWakeParams(origin)) { result ->
            failure = result.failure?.let(service.resources::words)
            onWoken()
        }
    }

    /** The user closed the card: voice ends with it (the server's `voice/closedByHand`). */
    fun cardClosed() {
        failure = null
        OverlayConnection.send(VoiceRequests.CLOSED_BY_HAND, Empty)
    }

    /** Started from the background for an A2A caller: it must run in the foreground anyway. */
    fun promoteForA2a() {
        if (forA2a) return
        forA2a = service.startOverlayForeground()
    }

    fun destroy() {
        scope.cancel()
        // The other assistant's service may have come up with a voice of its own meanwhile.
        if (OverlayControl.waker === waker) OverlayControl.waker = null
        HostConnection.report { copy(ready = false) }
        OverlayConnection.send(VoiceRequests.END_WAKE_SESSION, Empty)
    }

}

/** Runs the overlay as a foreground service (an A2A caller started it from the background); false when refused. */
internal fun Service.startOverlayForeground(): Boolean = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = getSystemService(NotificationManager::class.java)
        val title = getString(R.string.overlay_notification_title)
        manager.createNotificationChannel(
            NotificationChannel(OVERLAY_CHANNEL, title, NotificationManager.IMPORTANCE_MIN),
        )
        val notification = Notification.Builder(this, OVERLAY_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(getString(R.string.overlay_notification_text))
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
internal fun overlayVoiceLine(
    resources: Resources,
    state: VoiceView,
    failure: String?,
    nowMillis: Long,
): OverlayVoiceLine {
    if (state.status == VoiceStatus.IDLE || state.status == VoiceStatus.ERROR) {
        return failure?.let { OverlayVoiceLine(it, OverlayVoiceTone.WARN) }
            ?: state.problem?.takeIf { state.status == VoiceStatus.ERROR }?.let { OverlayVoiceLine(resources.words(it), OverlayVoiceTone.WARN) }
            ?: OverlayVoiceLine(resources.getString(R.string.overlay_voice_ended), OverlayVoiceTone.HINT)
    }
    if (state.liveText.isNotBlank()) return OverlayVoiceLine(state.liveText.takeLast(MAX_LIVE_CHARS), OverlayVoiceTone.LIVE)
    val recent = state.segments.lastOrNull()?.takeIf { nowMillis - it.endedAtEpochMillis < RECENT_SEGMENT_MILLIS }
    if (recent != null) {
        val text = recent.text.takeLast(MAX_LIVE_CHARS)
        return when {
            recent.label == SegmentLabel.AGENT && recent.handOver == SegmentHandOver.APPEND ->
                OverlayVoiceLine(resources.getString(R.string.overlay_voice_appended, text), OverlayVoiceTone.SENT)
            recent.label == SegmentLabel.AGENT && recent.handOver == SegmentHandOver.REPLACE ->
                OverlayVoiceLine(resources.getString(R.string.overlay_voice_replaced, text), OverlayVoiceTone.SENT)
            recent.label == SegmentLabel.AGENT -> OverlayVoiceLine(text, OverlayVoiceTone.SENT)
            recent.label == SegmentLabel.SIDE -> OverlayVoiceLine(
                resources.getString(R.string.overlay_voice_not_a_request, text),
                OverlayVoiceTone.IGNORED,
            )
            else ->
                OverlayVoiceLine(resources.getString(R.string.overlay_voice_not_for_me, text), OverlayVoiceTone.IGNORED)
        }
    }
    val hint = when {
        state.status == VoiceStatus.STARTING -> R.string.overlay_voice_opening_mic
        state.assistantSpeaking && !state.fullDuplex -> R.string.overlay_voice_after_speaking
        state.agentWorking -> R.string.overlay_voice_add_or_change
        else -> R.string.overlay_voice_listening
    }
    return OverlayVoiceLine(resources.getString(hint), OverlayVoiceTone.HINT)
}

/**
 * The card's request line while voice is on, in its usual place and type: the words as they are recognized, a stretch
 * that was not taken as an instruction, why a wake could not listen, else the request being answered (or a prompt).
 * It reads the session directly, so the card itself never re-renders per word; loudness shows as [VoiceAura].
 */
@Composable
internal fun OverlayVoiceAsked(request: String?, failure: String?) {
    val shown by OverlayConnection.state.collectAsState()
    val state = shown.voice
    // 端到端演示模式: a turn 豆包 answered itself has no run behind it, so its words and reply show here.
    val exchange = state.realtime
    val resources = LocalContext.current.resources
    val heard = request ?: exchange?.heard?.takeIf(String::isNotBlank)
    val line = voiceAskedLine(resources, state, failure, heard, System.currentTimeMillis())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            line.text,
            color = if (line.tone == OverlayVoiceTone.SENT) OverlayPalette.secondary else line.tone.color(),
            fontSize = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics {
                contentDescription = resources.getString(R.string.overlay_voice_description, line.text)
            },
        )
    }
    val reply = exchange?.reply?.takeIf { it.isNotBlank() && state.liveText.isBlank() } ?: return
    Text(
        reply,
        color = OverlayPalette.text,
        fontSize = 18.sp,
        maxLines = MAX_REPLY_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.semantics {
            contentDescription = resources.getString(R.string.overlay_reply_description, reply)
        },
    )
}

/** What the request line says while voice is on (see [OverlayVoiceAsked]); quoted like a request. */
internal fun voiceAskedLine(
    resources: Resources,
    state: VoiceView,
    failure: String?,
    request: String?,
    nowMillis: Long,
): OverlayVoiceLine {
    val listening = state.status == VoiceStatus.LISTENING || state.status == VoiceStatus.BUSY
    val recent = state.segments.lastOrNull()?.takeIf { nowMillis - it.endedAtEpochMillis < RECENT_SEGMENT_MILLIS }
    return when {
        !listening && failure != null -> OverlayVoiceLine(failure, OverlayVoiceTone.WARN)
        state.liveText.isNotBlank() -> OverlayVoiceLine("“${state.liveText.takeLast(MAX_LIVE_CHARS)}”", OverlayVoiceTone.LIVE)
        recent != null && recent.label != SegmentLabel.AGENT ->
            OverlayVoiceLine(
                resources.getString(R.string.overlay_voice_not_a_request, recent.text.takeLast(MAX_LIVE_CHARS)),
                OverlayVoiceTone.IGNORED,
            )
        !request.isNullOrBlank() -> OverlayVoiceLine("“$request”", OverlayVoiceTone.SENT)
        else -> OverlayVoiceLine(resources.getString(R.string.overlay_voice_listening), OverlayVoiceTone.HINT)
    }
}

/**
 * Android Use: the card is folded and 小博 is the pointer, flying from tap to tap, so what is heard sits still at the
 * top of the screen instead of following it. It lives in the pointer's own window, which takes no touches (taps,
 * injected ones included, pass through) and turns transparent for every screenshot the agent takes.
 */
@Composable
internal fun OverlayVoiceCaption(modifier: Modifier) {
    val shown by OverlayConnection.state.collectAsState()
    val state = shown.voice
    val line = overlayVoiceLine(LocalContext.current.resources, state, null, System.currentTimeMillis())
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
