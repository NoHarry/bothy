package cc.noharry.bothy.server.server

import android.content.Context
import android.os.SystemClock
import cc.noharry.agent.appserver.protocol.text
import cc.noharry.bothy.protocol.HandOverNoticeView
import cc.noharry.bothy.protocol.RealtimeExchangeView
import cc.noharry.bothy.protocol.SegmentHandOver
import cc.noharry.bothy.protocol.SegmentLabel
import cc.noharry.bothy.protocol.VoiceEffectsView
import cc.noharry.bothy.protocol.VoiceJudgmentView
import cc.noharry.bothy.protocol.VoiceSegmentView
import cc.noharry.bothy.protocol.VoiceStatus
import cc.noharry.bothy.protocol.VoiceView
import cc.noharry.bothy.protocol.VoiceWakeParams
import cc.noharry.bothy.protocol.VoiceWakeResult
import cc.noharry.bothy.server.voice.VoiceController
import cc.noharry.bothy.server.voice.VoiceFeedback
import cc.noharry.bothy.server.voice.VoiceHandOverNotice
import cc.noharry.bothy.server.voice.WakeSource
import cc.noharry.bothy.server.voice.WakeWord
import cc.noharry.bothy.server.voice.realtime.RealtimeShown
import cc.noharry.bothy.server.voice.startWokenVoice
import cc.noharry.bothy.server.voice.tts.TtsActivity
import cc.noharry.bothy.server.voice.voiceProblemFinding
import cc.noharry.bothy.server.voice.wakeMigrationNotice
import cc.noharry.bothy.voice.VoiceJudgment
import cc.noharry.bothy.voice.VoiceSegment
import kotlinx.coroutines.flow.MutableStateFlow

/** How many finished stretches a front end is sent: the voice slot shows the last, the voice pane a few. */
private const val SEGMENTS_SENT = 20

/** The voice session and what surrounds it, as the protocol sends it ([VoiceView]). Main thread. */
/** Bumped when something [voiceView] reads from storage changed (the update's wake notice), so it is read again. */
internal val wakeNoticeShown = MutableStateFlow(0)

internal fun voiceView(context: Context): VoiceView {
    val session = VoiceController.state.value
    val wake = WakeWord.status.value
    return VoiceView(
        status = VoiceStatus.valueOf(session.status.name),
        engine = VoiceController.sessionEngine,
        asrModel = VoiceController.sessionAsrModel,
        liveText = session.liveText,
        liveJudgment = session.liveJudgment?.view(),
        micActive = session.micActive,
        micLevels = session.micLevels,
        segments = session.segments.takeLast(SEGMENTS_SENT).map { it.view() },
        problem = session.problem?.let(::voiceProblemFinding) ?: session.message?.let(::text),
        fullDuplex = session.fullDuplex,
        effects = session.audioEffects?.let { VoiceEffectsView(it.echoCanceller, it.echoGate) },
        judgeCount = session.judgeCount,
        judgeFailures = session.judgeFailures,
        lastJudgeLatencyMillis = session.lastJudgeLatencyMillis,
        echoes = session.echoes,
        interruptions = session.interruptions,
        agentWorking = session.agentWorking,
        assistantSpeaking = session.assistantSpeaking,
        listening = VoiceController.listening,
        active = VoiceController.active.value,
        holding = VoiceController.holding,
        finishingHold = VoiceController.finishingHold,
        holdsToTalk = VoiceController.holdsToTalk(context),
        handOver = VoiceFeedback.notice.value?.view(),
        wakeSession = VoiceController.wakeSession,
        notice = VoiceController.notice.value,
        speaking = TtsActivity.active.value,
        wakeWordEnabled = WakeWord.enabled.value,
        wakeStatus = wake.finding,
        wakeListening = wake.listening,
        wakeMigrated = wakeMigrationNotice(context),
        realtime = RealtimeShown.exchange.value?.let { RealtimeExchangeView(it.heard, it.reply) },
    )
}

private fun VoiceSegment.view() = VoiceSegmentView(
    id = id,
    text = text,
    label = SegmentLabel.valueOf(label.name),
    reason = reason.name,
    endedAtEpochMillis = endedAtEpochMillis,
    submitted = submitted,
    handOver = handOver?.let { SegmentHandOver.valueOf(it.name) },
    dismissed = dismissed,
    judgment = judgment?.view(),
)

private fun VoiceJudgment.view() = VoiceJudgmentView(turnComplete, holdingFloor, addressedToAgent, takingFloor, replacesTask)

/** The notice's time on the wall clock: it was taken on the elapsed-time clock, which front ends do not share. */
private fun VoiceHandOverNotice.view() = HandOverNoticeView(
    handOver = SegmentHandOver.valueOf(mode.name),
    text = text,
    atEpochMillis = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - atMillis),
    fellBack = fellBack,
    ack = ack,
)

/** A wake a front end asked for (a tap or long press on the assistant, a head unit's surface). */
internal fun wakeFor(context: Context, params: VoiceWakeParams): VoiceWakeResult {
    if (params.onlyIfCanListen && !VoiceController.listening) {
        VoiceController.cannotListen(context)?.let { why -> return VoiceWakeResult(started = false, failure = why) }
    }
    val failure = startWokenVoice(context, WakeSource.valueOf(params.source.name))
    return VoiceWakeResult(started = true, failure = failure)
}
