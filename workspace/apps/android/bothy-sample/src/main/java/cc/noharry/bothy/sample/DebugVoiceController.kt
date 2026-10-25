package cc.noharry.bothy.sample

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import cc.noharry.agent.voice.CLOUD_TRANSCRIPT_NOTES
import cc.noharry.agent.voice.DashScopeAsrEngine
import cc.noharry.agent.voice.DashScopeAsrModel
import cc.noharry.agent.voice.DirectTurnJudge
import cc.noharry.agent.voice.JevTurnJudge
import cc.noharry.agent.voice.LeasedAudioSource
import cc.noharry.agent.voice.NearSpeechGate
import cc.noharry.agent.voice.SHERPA_TRANSCRIPT_NOTES
import cc.noharry.agent.voice.SherpaOnnxAsrEngine
import cc.noharry.agent.voice.StreamingAsrEngine
import cc.noharry.agent.voice.VoiceAudioEffects
import cc.noharry.agent.voice.VoiceAudioSource
import cc.noharry.agent.voice.VoiceAudioTee
import cc.noharry.agent.voice.VoiceEndReason
import cc.noharry.agent.voice.VoiceHandOver
import cc.noharry.agent.voice.VoiceHandOverMode
import cc.noharry.agent.voice.VoiceJudgment
import cc.noharry.agent.voice.VoiceSegment
import cc.noharry.agent.voice.VoiceSegmentLabel
import cc.noharry.agent.voice.VoiceSession
import cc.noharry.agent.voice.VoiceSessionState
import cc.noharry.agent.voice.VoiceSessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * How a voice session began: the console's mic button listens until tapped again; a wake ends itself when idle. A
 * follow-up reopens listening for a delegated agent's spoken report or a plan's task, like a wake nobody called.
 */
internal enum class DebugVoiceOrigin {
    BUTTON,
    WAKE,
    FOLLOW_UP,
    ;

    /** Ends itself when idle, and may be dismissed by voice or by the overlay. */
    val handsFree: Boolean get() = this != BUTTON
}

/**
 * The process's one voice session (tap to wake, listen until stopped; or woken on the overlay, until idle).
 * Agent-addressed turns go through the same entry as the ASR port; the session stays deaf while the agent runs or
 * any speech is queued or playing. Main thread only.
 */
internal object DebugVoiceController {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(VoiceSessionState())
    val state: StateFlow<VoiceSessionState> = mutableState

    /** Why the last wake did not start (no permission, Jev not configured, model missing); empty once it did. */
    private val mutableNotice = MutableStateFlow("")
    val notice: StateFlow<String> = mutableNotice

    private val mutableActive = MutableStateFlow(false)
    /** Whether a session is listening; the overlay shows its voice bar while it is. */
    val active: StateFlow<Boolean> = mutableActive

    private val mutableDismissals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** A woken session the speaker dismissed by voice ("退下吧"); the overlay folds its card. */
    val dismissals: SharedFlow<Unit> = mutableDismissals

    private var session: VoiceSession? = null
    /** 端到端演示模式's conversation, in place of [session]. */
    private var realtime: DebugRealtimeVoice? = null
    /** The session's recording, which the wake word also hears. */
    private var tee: VoiceAudioTee? = null
    /** The Jev session's hearing, which adb's `voice_pcm` adds recorded words to ([injectAudio]). */
    private var injected: DebugInjectedAudio? = null
    private var origin = DebugVoiceOrigin.BUTTON
    private var appContext: Context? = null
    private var watchers: Job? = null
    private var detachRuntime: (() -> Unit)? = null
    private var detachClient: (() -> Unit)? = null
    private var runtimeRunning = false
    private var clientRunning = false
    /** The request the agent is working on, for Jev's append-or-replace question. */
    private var runtimeTask = ""
    /** The last reply shown in the local console: an agent's question there means the next words answer it (陪聊). */
    private var lastShownReply: String? = null
    private var clientTask = ""
    /** What the agent has done for its task so far, for Jev; rebuilt at most every [PROGRESS_REFRESH_MILLIS]. */
    private var runtimeProgress = ""
    private var clientProgress = ""
    private var progressAt = 0L

    val listening: Boolean get() = session != null || realtime != null

    /** A session the overlay's wake (or a report's follow-up) started, which the overlay may also end. */
    val wakeSession: Boolean get() = listening && origin.handsFree

    /** 端到端演示模式 is on air: 豆包 speaks for 小博, whose own speech stays off. */
    val realtimeSpeaks: Boolean get() = realtime != null

    /** What the session records, for the wake word to listen to while it runs; null without a session. */
    val wakeAudio: VoiceAudioSource? get() = tee?.copies

    /** The mic button: wakes the session, or ends it when already listening. */
    fun toggle(context: Context) {
        if (listening) {
            stop()
            mutableNotice.value = ""
        } else {
            mutableNotice.value = start(context).orEmpty()
        }
    }

    fun notice(message: String) {
        mutableNotice.value = message
    }

    /**
     * A delegated agent's report, or a plan's task, has been answered aloud: with wake on and nobody listening, listening
     * opens again so the reply to it ("哪家最好吃？") is heard. The woken session that asked for it has usually ended by then.
     */
    fun followUp(context: Context) {
        if (listening || !DebugWakeWord.enabled.value) return
        val failure = start(context, DebugVoiceOrigin.FOLLOW_UP)
        DebugVoiceLog.i(TAG, "listening again for a report${failure?.let { ": $it" }.orEmpty()}")
    }

    /** When the card's X last closed voice ([closedByHand]), elapsed-realtime ms; 0 before. */
    @Volatile
    private var closedAt = 0L

    /**
     * The card's X (owner 2026-10-20: 「卡片点击X应该退出语音」): a woken session ends, what 小博 is saying stops, and the
     * run going on finishes without speaking (on a test device: closed at 09:17:53, its answer reopened the card and talked for
     * 25 s). The run itself is not stopped; 「退下吧」 stops it.
     */
    fun closedByHand() {
        closedAt = SystemClock.elapsedRealtime()
        if (wakeSession) stop()
        DebugTtsActivity.interrupt()
        DebugRuntimeSession.silenceRunning()
        DebugVoiceLog.i(TAG, "voice closed by the card's X")
    }

    /** Whether the card's X closed voice at or after [since] (elapsed-realtime ms). */
    fun closedSince(since: Long): Boolean = closedAt >= since

    /** Why listening cannot start, known before a wake is answered (the microphone, the settings); null when it may. */
    fun cannotListen(context: Context): String? {
        val appContext = context.applicationContext
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "需要麦克风权限"
        return if (loadDebugRealtimeConfig(appContext).enabled) null else loadDebugVoiceConfig(appContext).validationError()
    }

    /** Null when listening started, else why it could not. */
    fun start(context: Context, origin: DebugVoiceOrigin = DebugVoiceOrigin.BUTTON): String? {
        if (listening) return null
        val appContext = context.applicationContext
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "需要麦克风权限"
        if (origin == DebugVoiceOrigin.WAKE) startFreshAfterQuiet(appContext)
        loadDebugRealtimeConfig(appContext).takeIf { it.enabled }?.let { return startRealtime(appContext, origin, it) }
        val config = loadDebugVoiceConfig(appContext)
        config.validationError()?.let { return it }
        val jev = loadDebugJevConfig(appContext)
        val jevKey = loadDebugJevKey(appContext).takeIf { config.jevTurns }.orEmpty()
        val engine = createEngine(appContext, config).getOrElse { return it.message }
        val nearGate = nearGate(appContext, config)
        lateinit var created: VoiceSession
        // The mic opens after start(), by which time the session exists, and once the wake word has let it go.
        val recording = sessionRecording(config) { effects -> created.reportAudioEffects(effects) }
        created = VoiceSession(
            engine = engine,
            // Without Jev every sentence the recognizer closes goes to the agent.
            judge = if (jevKey.isNotEmpty()) createJudge(jev, jevKey, engine.punctuates) else DirectTurnJudge(),
            audio = DebugInjectedAudio(recording).also { injected = it },
            thresholds = config.thresholds,
            autoSubmit = config.autoSubmit,
            historyLimit = config.historyMessages,
            fullDuplex = debugFullDuplex(config),
            onInterrupt = { main.post(DebugTtsActivity::interrupt) },
            onAgentTurn = { handOver -> main.post { handOver(appContext, handOver) } },
            // Only a hands-free session may be dismissed by voice; the console's mic is tapped off.
            onDismiss = if (origin.handsFree) ({ main.post(::dismissed) }) else null,
            wakePhrase = XIAOBO_WAKE_PROFILE.heardInTranscript,
            // 「退出」 on its own ends a woken session at once, as in 端到端.
            dismissal = ::dismisses,
            // A wake or a tap is the speaker calling the assistant; a follow-up is the assistant's own doing.
            calledAtStart = origin != DebugVoiceOrigin.FOLLOW_UP,
            nearGate = nearGate,
            // 陪聊: Jev also scores small talk while the companion is on (read for each turn, so the switch applies at once).
            askSmallTalk = { !DebugA2aClientConsole.isEnabled(appContext) && DebugCompanion.scoring(appContext) },
        )
        session = created
        tee = recording
        this.origin = origin
        this.appContext = appContext
        mutableActive.value = true
        DebugSpeechFocus.began(DebugSpeechFocus.LISTENING)
        // The wake word's own recording closes before the session's opens; it listens to the session's instead.
        DebugWakeWord.refresh()
        watch(appContext, created)
        created.start()
        return null
    }

    /** Before the session seeds the previous exchange: a wake after a long quiet starts with a clean conversation. */
    private fun startFreshAfterQuiet(appContext: Context) {
        if (DebugRuntimeSession.freshSessionForWake(appContext)) {
            val policy = DebugSessionSettings.policy.value
            DebugVoiceLog.i(TAG, if (policy.everyWake) "wake: new session (every wake)" else "wake after ${policy.idleMinutes} min without a turn: new session")
        }
    }

    /** 过滤远处人声: 10 dB under the caller's wake level, or null when switched off. */
    private fun nearGate(appContext: Context, config: DebugVoiceConfig): NearSpeechGate? {
        val gate = if (config.nearGate) NearSpeechGate(DebugWakeWord.callerLevelDb(appContext), config.nearGateBelowDb.toDouble()) else null
        DebugVoiceLog.i(TAG, "near gate ${gate?.thresholdDb?.let { "threshold %.1f dBFS".format(it) } ?: if (config.nearGate) "waiting for a wake level" else "off"}")
        return gate
    }

    /**
     * 端到端演示模式: 豆包 Seeduplex hears and answers, 小博 runs what it hands over. It records like a Jev session (the
     * wake word hears the same recording) and feeds the overlay the same state.
     */
    private fun startRealtime(appContext: Context, origin: DebugVoiceOrigin, config: DebugRealtimeConfig): String? {
        val key = realtimeKey(appContext)
        if (key.isEmpty()) return "端到端演示要用豆包语音 key：请先在 设置 → 语音 → 密钥 保存"
        lateinit var created: DebugRealtimeVoice
        val voice = loadDebugVoiceConfig(appContext)
        val recording = sessionRecording(voice) { effects -> created.reportAudioEffects(effects) }
        val gate = realtimeGate(DebugWakeWord.callerLevelDb(appContext), voice.nearGate, voice.nearGateBelowDb.toDouble())
        DebugVoiceLog.i(TAG, "realtime gate threshold %.1f dBFS".format(gate.thresholdDb))
        created = DebugRealtimeVoice(
            context = appContext,
            config = config,
            audio = recording,
            gate = gate,
            agent = localRealtimeAgent(appContext),
            endsWhenIdle = origin.handsFree,
            onDismiss = { if (realtime === created) dismissed() },
            onEnd = { reason ->
                if (realtime === created) {
                    DebugVoiceLog.i(TAG, "realtime conversation ended: $reason")
                    // A refused key or a lost connection stays in view after the voice bar folds.
                    if (created.state.value.status == VoiceSessionStatus.ERROR) mutableNotice.value = reason
                    stop()
                }
            },
            client = doubaoRealtimeClient(appContext),
            key = key,
        )
        realtime = created
        tee = recording
        this.origin = origin
        this.appContext = appContext
        mutableActive.value = true
        DebugSpeechFocus.began(DebugSpeechFocus.LISTENING)
        DebugWakeWord.refresh()
        watchers = scope.launch { created.state.collect { next -> logChanges(mutableState.value, next); mutableState.value = next } }
        created.start()
        return null
    }

    /** The session's recording, which the wake word also hears. */
    private fun sessionRecording(config: DebugVoiceConfig, onEffects: (VoiceAudioEffects) -> Unit): VoiceAudioTee =
        VoiceAudioTee(LeasedAudioSource(DebugWakeWord.micLease, debugMicrophone(config, onEffects)), copySource = true)

    private fun createJudge(jev: DebugJevConfig, key: String, punctuated: Boolean) = JevTurnJudge(
        endpoint = jev.endpoint,
        apiKey = key,
        model = jev.model,
        timeoutMillis = jev.timeoutMillis,
        transcriptNotes = if (punctuated) CLOUD_TRANSCRIPT_NOTES else SHERPA_TRANSCRIPT_NOTES,
        identity = XIAOBO_VOICE_IDENTITY,
        onUsage = { input, output -> DebugUsageStore.record(jev.model, DebugModelUsage(1, input.toLong(), outputTokens = output.toLong())) },
    )

    /** The configured recognizer, or why it cannot run. */
    /** The recognizer Settings → 语音 picked; also what 问题反馈's dictation transcribes with. */
    internal fun createEngine(context: Context, config: DebugVoiceConfig): Result<StreamingAsrEngine> = when (config.engine) {
        DebugVoiceEngine.LOCAL -> SherpaOnnxAsrEngine.availabilityError(context.assets)
            ?.let { Result.failure(IllegalStateException(it)) }
            ?: Result.success(SherpaOnnxAsrEngine(context.assets, numThreads = config.localThreads))
        DebugVoiceEngine.DASHSCOPE -> loadDebugDashscopeKey(context).takeIf(String::isNotEmpty)
            ?.let { key ->
                val model = DashScopeAsrModel.of(config.dashscopeModel)
                val vocabulary = {
                    runCatching { debugDashScopeVocabulary(context, model.id, key, config.hotwords) }
                        .onFailure { DebugVoiceLog.w(TAG, "hotwords not applied: ${it.message}") }
                        .getOrNull()
                }
                Result.success(DashScopeAsrEngine(config.dashscopeEndpoint, key, model.id, model.languageHints, vocabularyId = vocabulary))
            }
            ?: Result.failure(IllegalStateException("请先保存百炼 API key"))
    }

    /** Ends listening; [resumeWake] hands the microphone back to the wake word (a restart keeps it for the next session). */
    fun stop(resumeWake: Boolean = true) {
        // A small-talk reply still on its way is not said once the session is over.
        DebugCompanion.cancel()
        realtime?.let { return stopRealtime(it, resumeWake) }
        val active = session ?: return
        session = null
        injected = null
        tee = null
        watchers?.cancel()
        watchers = null
        detachRuntime?.invoke()
        detachClient?.invoke()
        detachRuntime = null
        detachClient = null
        // Words of a turn that never ended go with the session (「退出」 right after a wake, 2026-10-20): logged to read back.
        active.state.value.liveText.takeIf(String::isNotBlank)?.let { DebugVoiceLog.i(TAG, "session ended mid-turn: $it") }
        active.stop()
        mutableState.value = active.state.value
        mutableActive.value = false
        DebugSpeechFocus.ended(DebugSpeechFocus.LISTENING)
        // The session's recording closes first; the wake word's opens after it.
        if (resumeWake) DebugWakeWord.refresh()
    }

    /** adb: [pcm16le] heard by the end-to-end conversation in place of the microphone; false without one. */
    fun injectRealtimeAudio(pcm16le: ByteArray): Boolean = realtime?.inject(pcm16le) != null

    /** adb: [pcm16le] heard by whichever session listens, in the Jev one over the microphone; false without one. */
    fun injectAudio(pcm16le: ByteArray): Boolean {
        if (injectRealtimeAudio(pcm16le)) return true
        val heard = injected ?: return false
        heard.inject(pcm16le)
        DebugVoiceLog.i(TAG, "injecting ${pcm16le.size / 32} ms of recorded words")
        return true
    }

    /** A result for the 端到端 conversation to say ([DebugRealtimeVoice.tellLate]); false when none is on air. */
    fun tellRealtime(text: String, fallback: () -> Unit): Boolean = realtime?.tellLate(text, fallback) != null

        private fun stopRealtime(active: DebugRealtimeVoice, resumeWake: Boolean) {
        realtime = null
        tee = null
        watchers?.cancel()
        watchers = null
        active.stop()
        mutableState.value = active.state.value
        mutableActive.value = false
        DebugSpeechFocus.ended(DebugSpeechFocus.LISTENING)
        if (resumeWake) DebugWakeWord.refresh()
    }

    /**
     * Woken again while listening: a new woken session takes over at once, its first turn expected for the assistant.
     * An end-to-end conversation keeps going (the wake already cut its speech off), so 豆包 remembers what was said.
     */
    fun restart(context: Context): String? {
        if (realtime != null) return null
        stop(resumeWake = false)
        return start(context, DebugVoiceOrigin.WAKE).also { failure -> if (failure != null) DebugWakeWord.refresh() }
    }

    /**
     * A wake while 小博 is busy is the speaker's strongest cue (owner 2026-10-12: 「正在做或者识别再次唤醒应该打断前面的任务
     * 重新聆听」): this car's own task stops (its local run, its A2A client task), so does any speech, and the caller
     * restarts listening, dropping a turn being heard. Delegated agents' tasks carry on. True when anything was stopped
     * or dropped.
     */
    fun interruptForWake(context: Context): Boolean {
        val app = context.applicationContext
        val client = DebugA2aClientConsole.isEnabled(app) && DebugA2aClientConsole.state.running
        val run = DebugRuntimeSession.isRunning
        val speaking = DebugTtsActivity.active.value && !DebugWakeAck.speaking
        val hearing = listening && state.value.liveText.isNotBlank()
        if (client) DebugA2aClientConsole.cancel(app)
        if (run) DebugRuntimeSession.stopRun()
        DebugCompanion.cancel()
        DebugTtsActivity.interrupt()
        val stopped = listOfNotNull("client task".takeIf { client }, "run".takeIf { run }, "speech".takeIf { speaking }, "turn".takeIf { hearing })
        if (stopped.isNotEmpty()) DebugVoiceLog.i(TAG, "wake interrupts: ${stopped.joinToString()}")
        return stopped.isNotEmpty()
    }

    private fun watch(context: Context, active: VoiceSession) {
        runtimeRunning = false
        clientRunning = false
        watchers = scope.launch {
            launch {
                active.state.collect { next ->
                    logChanges(mutableState.value, next)
                    mutableState.value = next
                    // The session ends itself on a microphone or recognizer failure.
                    if (next.status == VoiceSessionStatus.ERROR && session === active) stop()
                }
            }
            launch { DebugTtsActivity.active.collect { pushBusy(active) } }
            launch { DebugTtsActivity.speech.collect { pushBusy(active) } }
            if (origin.handsFree) launch { endWhenIdle(active) }
        }
        val forClient = DebugA2aClientConsole.isEnabled(context)
        var seeded = false
        detachRuntime = DebugRuntimeSession.attach(context) { ui ->
            // The exchange before this session, so a follow-up to it reads as one ("哪家最好吃？" after a list).
            if (!seeded && !forClient) ui.lastExchange().forEach { (user, text) -> if (user) active.recordUser(text) else active.recordAssistant(text) }
            seeded = true
            if (runtimeRunning && !ui.isRunning) ui.lastAssistantText()?.let(active::recordAssistant)
            lastShownReply = ui.lastAssistantText()
            runtimeRunning = ui.isRunning
            runtimeTask = ui.currentRequest?.text.orEmpty()
            if (ui.isRunning && progressDue()) runtimeProgress = ui.overlayHud().voiceProgress()
            pushBusy(active)
        }
        var clientSeeded = false
        detachClient = DebugA2aClientConsole.attach(context) { client ->
            if (!clientSeeded && forClient) client.lastExchange().forEach { (user, text) -> if (user) active.recordUser(text) else active.recordAssistant(text) }
            clientSeeded = true
            clientRunning = client.running
            clientTask = client.items.lastOrNull { it is DebugA2aClientItem.User && it.kind == DebugA2aClientItem.User.Kind.MESSAGE }
                ?.let { (it as DebugA2aClientItem.User).text }.orEmpty()
            if (client.running && progressDue()) clientProgress = client.overlayHud().voiceProgress()
            pushBusy(active)
        }
    }

    /**
     * A woken session ends once nothing has happened for [WAKE_IDLE_END_MILLIS]: nobody speaking, no words being
     * recognized, no new stretch of speech, the agent not working and nothing being said. Talk around the speaker keeps words coming, so
     * it also ends after [WAKE_UNENGAGED_END_MILLIS] without the speaker: nothing for the assistant, no call, the agent
     * idle and silent. The wake word then listens again.
     */
    private suspend fun endWhenIdle(active: VoiceSession) {
        var lastBusy = SystemClock.elapsedRealtime()
        var lastEngaged = lastBusy
        var lastSegment = active.state.value.segments.lastOrNull()?.id
        while (session === active) {
            delay(IDLE_CHECK_MILLIS)
            val state = active.state.value
            val now = SystemClock.elapsedRealtime()
            val fresh = state.segments.filter { it.id > (lastSegment ?: 0L) }
            if (fresh.isNotEmpty()) lastBusy = now
            if (fresh.any(::engagesSpeaker)) lastEngaged = now
            lastSegment = state.segments.lastOrNull()?.id ?: lastSegment
            val working = runtimeRunning || clientRunning || DebugTtsActivity.active.value || state.status == VoiceSessionStatus.STARTING
            if (working) lastEngaged = now
            // Speech under way counts before any word is recognized: a session that timed out as the speaker started
            // (「你好小博」 said again at that moment) took their words with it, and the wake word was not listening yet.
            if (working || state.liveText.isNotBlank() || state.micActive) lastBusy = now
            val reason = wokenSessionEnd(now - lastBusy, now - lastEngaged) ?: continue
            DebugVoiceLog.i(TAG, "woken session $reason: ended (gate ${state.nearGate?.let { "passed=${it.passedFrames} blocked=${it.blockedFrames}" } ?: "off"})")
            stop()
        }
    }

    /**
     * Logcat timeline for tuning echo cancellation and barge-in: speech on/off, each finished stretch with its label
     * and reason, and interruptions. Recognized text is the user's speech; it stays on this device's log.
     */
    private fun logChanges(before: VoiceSessionState, after: VoiceSessionState) {
        if (before.status != after.status) DebugVoiceLog.i(TAG, "status ${after.status}${after.message?.let { ": $it" }.orEmpty()}")
        if (before.micActive != after.micActive) {
            val peak = after.micLevels.takeLast(MIC_PEAK_WINDOW).maxOrNull() ?: 0f
            DebugVoiceLog.i(TAG, if (after.micActive) "speech on (meter %.2f)".format(peak) else "speech off")
        }
        if (before.liveText.isBlank() && after.liveText.isNotBlank()) DebugVoiceLog.i(TAG, "hearing: ${after.liveText}")
        // Each judgment with the words it read, so a turn that never ends shows why.
        after.liveJudgment?.takeIf { it != before.liveJudgment }?.let { DebugVoiceLog.i(TAG, "judged ${it.scores()} in ${it.latencyMillis} ms: ${after.liveText}") }
        if (after.judgeFailures > before.judgeFailures) DebugVoiceLog.w(TAG, "judge failed (${after.judgeFailures} so far): ${after.message}")
        if (before.assistantSpeaking != after.assistantSpeaking) {
            DebugVoiceLog.i(TAG, if (after.assistantSpeaking) "assistant speaking" else "assistant quiet")
        }
        val seen = before.segments.lastOrNull()?.id ?: 0L
        after.segments.filter { it.id > seen }.forEach { segment ->
            val scores = segment.judgment?.scores()
            val peak = segment.peakDb?.let { " peak=%.1f".format(it) }.orEmpty()
            DebugVoiceLog.i(TAG, "segment ${segment.label} reason=${segment.reason} handOver=${segment.handOver} ${scores.orEmpty()}$peak text=${segment.text}")
        }
        val gate = after.nearGate
        if (gate != null && gate.active != before.nearGate?.active) {
            DebugVoiceLog.i(TAG, "near gate ${if (gate.active) "filtering" else "standing aside"} threshold=%.1f background=%s".format(
                gate.thresholdDb, gate.backgroundDb?.let { "%.1f".format(it) } ?: "-",
            ))
        }
        if (after.interruptions > before.interruptions) DebugVoiceLog.i(TAG, "barge-in: speech stopped")
        if (after.audioEffects != null && before.audioEffects != after.audioEffects) {
            DebugVoiceLog.i(TAG, "capture effects aec=${after.audioEffects?.echoCanceller} ns=${after.audioEffects?.noiseSuppressor} fullDuplex=${after.fullDuplex}")
        }
    }

    /**
     * A voice turn reaches the agent. An append is first acknowledged out loud: the chat model says one short
     * sentence (about a second), spoken ahead of the agent's queued speech, and the append then carries that sentence
     * so the agent does not repeat it.
     */
    private fun handOver(context: Context, handOver: VoiceHandOver) {
        // 陪聊: a new turn of small talk goes to the companion service; anything else, and its failures, to the agent.
        if (!DebugA2aClientConsole.isEnabled(context) && DebugCompanion.take(context, handOver, lastShownReply) { reply -> session?.recordAssistant(reply) }) return
        DebugCompanion.cancel()
        if (handOver.mode != VoiceHandOverMode.APPEND) return submitDebugVoiceText(context, handOver)
        val task = if (clientRunning) clientTask else runtimeTask
        DebugVoiceFeedback.handedOver(handOver.mode, handOver.text, loadDebugVoiceConfig(context).handOverChime)
        scope.launch {
            val ack = debugVoiceAck(context, task, handOver.text)
            val spoken = ack != null && if (DebugA2aClientConsole.isEnabled(context)) {
                DebugA2aClientConsole.announceFirst(context, ack)
            } else {
                DebugRuntimeSession.announceFirst(context, ack)
            }
            ack?.let(DebugVoiceFeedback::acknowledged)
            DebugVoiceLog.i(TAG, "append acknowledged: ${ack ?: "-"} spoken=$spoken")
            // Only a spoken acknowledgement spares the agent its own; otherwise it is asked to answer briefly first.
            submitDebugVoiceText(context, handOver, ack?.takeIf { spoken }, feedback = false)
        }
    }

    /** adb (DEBUG_SEND_TEXT voice_turn): a new voice turn as Jev hands it over, with its small-talk score, for 陪聊. */
    fun simulateTurn(context: Context, text: String, smallTalk: Double?) =
        handOver(context.applicationContext, VoiceHandOver(text, VoiceHandOverMode.NEW_TURN, smallTalk))

    /**
     * "退下吧" in a woken session: the task it asked for stops and so does the speech, a parting chime plays, the
     * session ends and the overlay folds. Only this car's own task stops (a local run, or its A2A client task); other
     * clients' A2A tasks run in their own sessions and are left alone.
     */
    private fun dismissed() {
        if (!wakeSession) return
        val context = appContext
        // 端到端 watches no run of its own: who is working now comes from the run and the client themselves.
        val clientWorking = if (realtime != null) context != null && DebugA2aClientConsole.isEnabled(context) && DebugA2aClientConsole.state.running else clientRunning
        val runWorking = if (realtime != null) DebugRuntimeSession.isRunning else runtimeRunning
        DebugVoiceLog.i(TAG, "woken session dismissed by voice; stopping ${if (clientWorking) "client task" else if (runWorking) "run" else "nothing"}")
        DebugCompanion.cancel()
        when {
            context != null && clientWorking -> DebugA2aClientConsole.cancel(context)
            runWorking -> DebugRuntimeSession.stopRun()
        }
        DebugTtsActivity.interrupt()
        DebugVoiceFeedback.dismissed()
        stop()
        mutableDismissals.tryEmit(Unit)
    }

    private fun pushBusy(active: VoiceSession) {
        active.setHostState(
            working = runtimeRunning || clientRunning,
            speaking = DebugTtsActivity.active.value,
            speech = DebugTtsActivity.speech.value,
            task = if (clientRunning) clientTask else runtimeTask,
            progress = if (clientRunning) clientProgress else runtimeProgress,
        )
    }

    private fun progressDue(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - progressAt < PROGRESS_REFRESH_MILLIS) return false
        progressAt = now
        return true
    }
}

/**
 * Where a voice turn goes, as Jev judged it. While the agent works, words that belong to its task are appended to
 * its next model request (local steering, or the A2A client's steer) and nothing stops; words that drop the task
 * replace it, as the ASR port does. When the agent is idle they start a new turn. An append that finds the run
 * already ended starts a new turn instead. Main thread.
 */
internal fun submitDebugVoiceText(context: Context, handOver: VoiceHandOver, ack: String? = null, feedback: Boolean = true) {
    val text = handOver.text
    if (feedback) DebugVoiceFeedback.handedOver(handOver.mode, text, loadDebugVoiceConfig(context).handOverChime)
    if (DebugA2aClientConsole.isEnabled(context)) {
        val client = DebugA2aClientConsole.state
        val active = client.running && client.activeTaskId != null
        when {
            handOver.mode == VoiceHandOverMode.REPLACE -> DebugA2aClientConsole.replace(context, text, DebugA2aInputSource.ASR)
            active -> DebugA2aClientConsole.steer(context, text, DebugA2aInputSource.ASR, voiceAppendInput(text, ack))
            else -> DebugA2aClientConsole.send(context, text, DebugA2aInputSource.ASR)
        }
    } else if (handOver.mode == VoiceHandOverMode.REPLACE) {
        DebugRuntimeSession.submitTextReplacingRun(context, text, BACKEND_LIVE)
    } else {
        DebugRuntimeSession.postSpokenInput(context, text, voiceAppendInput(text, ack)) {
            if (handOver.mode == VoiceHandOverMode.APPEND) DebugVoiceFeedback.appendBecameNewTurn(text)
        }
    }
}

/**
 * What the working agent receives for an append. With [ack] already spoken it is told so, and goes on without
 * repeating it; without one it is asked to acknowledge in one short sentence first (spoken as progress).
 */
internal fun voiceAppendInput(text: String, ack: String? = null): String =
    if (ack != null) {
        "（语音追加）$text\n这是用户在你执行当前任务时追加的话，已经口头回应过用户「$ack」，不要再重复确认；" +
            "继续当前任务，并把这条一起完成。"
    } else {
        "（语音追加）$text\n这是用户在你执行当前任务时追加的话。请先用一句不超过15个字的话回应它" +
            "（例如「好，空调也一起打开」），再继续当前任务，并把这条一起完成。"
    }

/** The last turn's request and its last reply in the selected session, oldest first; true marks the user's. */
private fun DebugUiState.lastExchange(): List<Pair<Boolean, String>> {
    val header = items.indexOfLast { it is DebugConversationItem.TurnHeader }.takeIf { it >= 0 } ?: return emptyList()
    val request = (items[header] as DebugConversationItem.TurnHeader).fullText
    val reply = items.drop(header + 1).filterIsInstance<DebugConversationItem.MessageItem>()
        .lastOrNull { it.role == DebugTranscriptRole.ASSISTANT }?.content
    return listOfNotNull(true to request, reply?.let { false to it })
}

/** The last message sent and the last reply spoken to the A2A agent, oldest first; true marks the user's. */
private fun DebugA2aClientState.lastExchange(): List<Pair<Boolean, String>> =
    items.mapNotNull { item ->
        when (item) {
            is DebugA2aClientItem.User -> (true to item.text).takeIf { item.kind == DebugA2aClientItem.User.Kind.MESSAGE }
            is DebugA2aClientItem.Speech -> (false to item.text).takeIf { item.final }
            else -> null
        }
    }.takeLast(SEEDED_MESSAGES)

private fun DebugUiState.lastAssistantText(): String? =
    items.lastOrNull { it is DebugConversationItem.MessageItem && it.role == DebugTranscriptRole.ASSISTANT }
        ?.let { (it as DebugConversationItem.MessageItem).content }

private const val TAG = "DebugVoice"
/** How long a woken session stays open with nothing happening, like a smart speaker's follow-up window. */
internal const val WAKE_IDLE_END_MILLIS = 10_000L
/** How long a woken session keeps listening to talk that is not for the assistant (owner 2026-10-12: 「停不下来」). */
internal const val WAKE_UNENGAGED_END_MILLIS = 30_000L

/** Why a woken session ends now, after [idleMillis] with nothing happening and [unengagedMillis] without the speaker; null keeps it. */
internal fun wokenSessionEnd(idleMillis: Long, unengagedMillis: Long): String? = when {
    idleMillis >= WAKE_IDLE_END_MILLIS -> "idle for ${WAKE_IDLE_END_MILLIS / 1000} s"
    unengagedMillis >= WAKE_UNENGAGED_END_MILLIS -> "heard only talk not for the assistant for ${WAKE_UNENGAGED_END_MILLIS / 1000} s"
    else -> null
}

/** A finished stretch that shows the speaker is still talking to the assistant: words for it, or its name said again. */
internal fun engagesSpeaker(segment: VoiceSegment): Boolean =
    segment.label == VoiceSegmentLabel.AGENT || segment.reason == VoiceEndReason.WAKE_PHRASE
/** Jev's scores as the log shows them; `-` for a question it was not asked. */
private fun VoiceJudgment.scores(): String = "complete=%.2f holding=%.2f addressed=%.2f taking=%s replace=%s ends=%s".format(
    turnComplete, holdingFloor, addressedToAgent,
    takingFloor?.let { "%.2f".format(it) } ?: "-", replacesTask?.let { "%.2f".format(it) } ?: "-", endsSession?.let { "%.2f".format(it) } ?: "-",
)
/** The last half second of the 50-per-second level meter. */
private const val MIC_PEAK_WINDOW = 25
private const val IDLE_CHECK_MILLIS = 500L
/** One request and its reply. */
private const val SEEDED_MESSAGES = 2
private const val PROGRESS_REFRESH_MILLIS = 500L

/** What the agent has done and is doing, for Jev: the current step, the latest tools and the reply so far. */
internal fun DebugOverlayHud.voiceProgress(): String = buildList {
    currentStep?.takeIf(String::isNotBlank)?.let { add("当前步骤：$it") }
    toolLines.takeLast(MAX_PROGRESS_TOOLS).takeIf { it.isNotEmpty() }?.let { lines ->
        add("工具：" + lines.joinToString("；") { "${it.name}（${it.statusLabel}）" })
    }
    resultText.takeIf(String::isNotBlank)?.let { add("已说：" + it.takeLast(MAX_PROGRESS_CHARS)) }
}.joinToString("\n")

private const val MAX_PROGRESS_TOOLS = 5
private const val MAX_PROGRESS_CHARS = 160
