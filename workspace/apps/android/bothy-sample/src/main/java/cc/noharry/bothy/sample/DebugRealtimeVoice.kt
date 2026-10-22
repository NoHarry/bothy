package cc.noharry.bothy.sample

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import cc.noharry.agent.voice.NearSpeechGate
import cc.noharry.agent.voice.VoiceAudioEffects
import cc.noharry.agent.voice.VoiceAudioSource
import cc.noharry.agent.voice.VoiceEndReason
import cc.noharry.agent.voice.VoiceHandOverMode
import cc.noharry.agent.voice.VoiceSegment
import cc.noharry.agent.voice.VoiceSegmentLabel
import cc.noharry.agent.voice.VoiceSessionState
import cc.noharry.agent.voice.VoiceSessionStatus
import cc.noharry.agent.voice.looksLikeEcho
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/** What the end-to-end conversation last heard and said back, for the overlay card. */
internal data class DebugRealtimeExchange(val heard: String, val reply: String = "")

/**
 * The card gives way only to a new run, so a turn 豆包 answers itself shows here instead: null while there is none,
 * when 小博 has the task (the card shows its run) and once the conversation ends.
 */
internal object DebugRealtimeShown {
    val exchange = MutableStateFlow<DebugRealtimeExchange?>(null)
}

/** 小博 as the realtime model's executor: runs a request and returns what it answered. */
internal fun interface DebugRealtimeAgent {
    /** 小博's answer to [request], or a still-working note after [replyWithinMillis] (the answer then comes by tellLate). */
    suspend fun run(request: String, replyWithinMillis: Long): String
}

/**
 * One 端到端 conversation: the microphone streams to 豆包 Seeduplex, which hears, answers aloud and hands tasks to 小博
 * through ask_xiaobo; 小博 runs them in the console's session without speaking, and its reply goes back for the model
 * to say. The overlay reads the same [VoiceSessionState] a Jev session gives it: the words as heard, levels, speaking,
 * working. Without [bargeIn] the model hears silence while anything speaks, so a device without good echo cancelling
 * does not hear itself; a wake still cuts it off. Server events and local interrupts run one at a time on [actions].
 */
internal class DebugRealtimeVoice(
    private val context: Context,
    private val config: DebugRealtimeConfig,
    private val audio: VoiceAudioSource,
    /** Turns far talk and the residue of music into silence before the model hears it. */
    private val gate: NearSpeechGate,
    private val agent: DebugRealtimeAgent,
    private val endsWhenIdle: Boolean,
    private val onDismiss: () -> Unit,
    private val onEnd: (String) -> Unit,
    private val client: OkHttpClient,
    private val key: String,
    private val url: String = DOUBAO_REALTIME_URL,
) {
    private val main = Handler(Looper.getMainLooper())
    /** Held while an event or local action runs, and while [stop] cleans up. */
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val actions = Channel<() -> Unit>(Channel.UNLIMITED)
    /** Events of a connection that has been replaced are dropped. */
    @Volatile private var generation = 0
    @Volatile private var connection = connect()
    private var reconnects = 0
    private val converter = Float32ToPcm16(REALTIME_OUTPUT_GAIN)
    private val mutableState = MutableStateFlow(
        VoiceSessionState(status = VoiceSessionStatus.STARTING, engineLabel = REALTIME_LABEL, fullDuplex = config.bargeIn),
    )
    val state: StateFlow<VoiceSessionState> = mutableState

    @Volatile private var created = false
    @Volatile private var closing = false
    @Volatile private var quietSince = 0L
    @Volatile private var lastActivity = SystemClock.elapsedRealtime()
    @Volatile private var pendingTools = 0
    /** ask_xiaobo calls waiting for 小博, oldest first. */
    private val pendingCalls = ArrayList<String>()
    /** Calls asked on a connection that has since been replaced: 小博 still works on them; nobody hears the answer. */
    private val orphanedCalls = HashSet<String>()
    /** The stretch of speech taking audio now; closed when the model stops talking or calls 小博. */
    @Volatile private var speech: Channel<DebugTtsAudioChunk>? = null
    /** Every stretch still playing or waiting its turn, oldest first; each plays after the one before it. */
    private val stretches = ArrayList<SpeechStretch>()
    @Volatile private var speaking = false
    /** Speech of a reply cut off locally, dropped until the speaker's next words or the model's next reply. */
    private var dropSpeech = false
    private var heard = ""
    private var heardAt = 0L
    /** When [heard] came, kept after the reply starts (unlike [heardAt]): its words go with an ask_xiaobo call. */
    private var heardWordsAt = 0L
    private var toolTurn = false
    /** The model said something in the response now open: a call in it must be answered before the service's voice times out. */
    private var spokeThisResponse = false
    /**
     * Words the service heard while 小博 said a late result: not the speaker's, who was heard as silence then. A test device
     * (2026-10-21, after two trip plans) heard 「好规划。」 at meter 0.00 and 「3 点了。」 with the loudspeaker's cues,
     * and the model answered aloud over 小博's plan, once with an itinerary of its own and once through a second
     * ask_xiaobo. The reply to them is dropped and their calls answered as ignored ([IGNORED_CALL_RESULT]), until the
     * speaker's next words or until the model has answered them.
     */
    private var heardOverXiaobo = false
    /** An ignored call's result went back: the model's answer to it, still to come, is dropped too. */
    private var ignoredCallAnswered = false
    /** The model's reply dropped in the response now open, logged once. */
    private var droppedThisResponse = false
    /**
     * Since when the model owes a reply (elapsed-realtime ms; 0 when not): the speaker's words heard or a tool result
     * sent, until its response is done. A late result waits for it, so 小博 never starts in the second before the
     * model's 「请稍等」 is heard.
     */
    @Volatile private var replyOwedSince = 0L
    private var segmentId = 0L
    /** What reached the model, logged every [STATS_LOG_MILLIS] so a stalled microphone or socket shows in the log. */
    @Volatile private var framesRead = 0
    @Volatile private var slicesSent = 0
    @Volatile private var sendsRefused = 0
    @Volatile private var lastEventAt = SystemClock.elapsedRealtime()
    /** adb (DEBUG_SEND_TEXT realtime_pcm): recorded words heard in place of the microphone, slice by slice. */
    private val injected = ConcurrentLinkedQueue<ShortArray>()

    fun start() {
        scope.launch { for (action in actions) synchronized(lock) { if (!closing) runCatching(action).onFailure { DebugVoiceLog.w(TAG, "event failed: ${it.message}") } } }
        scope.launch { streamMicrophone() }
        scope.launch { logStats() }
        if (endsWhenIdle) scope.launch { endWhenIdle() }
        open(connection)
        DebugVoiceLog.i(TAG, "connecting: voice=${config.voice} bargeIn=${config.bargeIn}")
    }

    private fun connect(): DoubaoRealtimeConnection {
        val mine = ++generation
        return DoubaoRealtimeConnection(client, key, { event -> actions.trySend { if (mine == generation) handle(event) } }, url)
    }

    private fun open(next: DoubaoRealtimeConnection) {
        next.open()
        // OkHttp holds it until the handshake is done. What 小博 remembers, and what this session said before this
        // conversation (each wake opens a new one, which otherwise starts from nothing), are read again on a reconnect.
        val memories = if (debugUserMemoryEnabled(context)) DebugUserMemoryStore.inUse(context) else null
        scope.launch {
            val recent = runCatching { DebugRuntimeSession.recentExchanges(context, REALTIME_CONTEXT_TURNS) }.getOrDefault(emptyList())
            if (recent.isNotEmpty()) DebugVoiceLog.i(TAG, "told ${recent.size} earlier exchange(s) of this session")
            next.send(doubaoRealtimeSessionCreate(config, memories, recent))
        }
    }

    /**
     * A connection lost mid-conversation (the bench's Wi-Fi reset one, 2026-10-13) is opened again once, so a demo does
     * not end mid-sentence; 豆包 starts a new session without what was said, and 小博's pending tasks finish on the card.
     */
    private fun reconnect(reason: String): Boolean {
        if (!created || reconnects >= MAX_RECONNECTS) return false
        reconnects += 1
        DebugVoiceLog.w(TAG, "connection lost ($reason): connecting again")
        created = false
        stopSpeech()
        // 小博 keeps working on what was asked: its answer cannot reach the new session (the card shows it), but the
        // conversation stays open until it is done (2026-10-14: a timeout mid-task, then an idle end 20 s later).
        orphanedCalls += pendingCalls
        pendingCalls.clear()
        heard = ""
        mutableState.update { it.copy(micActive = false, liveText = "") }
        // A `session.closed` leaves the old socket open, its pings keeping it alive: closed here.
        connection.close()
        connection = connect().also(::open)
        return true
    }

    /**
     * Main thread. Under [lock], so an event being handled finishes first and none runs after: nothing it starts (a
     * speech holder, a new connection) outlives the session.
     */
    fun stop() {
        synchronized(lock) {
            if (closing) return
            closing = true
            connection.close()
            scope.cancel()
            DebugRealtimeShown.exchange.value = null
            synchronized(stretches) { stretches.toList() }.forEach { it.player.stop() }
            DebugTtsActivity.end(this)
            mutableState.update {
                it.copy(status = VoiceSessionStatus.IDLE, micActive = false, liveText = "", assistantSpeaking = false, agentWorking = false)
            }
        }
    }

    fun reportAudioEffects(effects: VoiceAudioEffects) = mutableState.update { it.copy(audioEffects = effects) }

    /** Plays [pcm16le] (16 kHz mono) to the model as if spoken, at the microphone's pace; for checking a device by adb. */
    fun inject(pcm16le: ByteArray) {
        val samples = ByteBuffer.wrap(pcm16le).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        while (samples.hasRemaining()) {
            injected += ShortArray(min(SLICE_SAMPLES, samples.remaining())).also { samples.get(it) }
        }
        DebugVoiceLog.i(TAG, "injecting ${pcm16le.size / 2 / (REALTIME_INPUT_RATE / 1000)} ms of recorded words")
    }

    /** 小博's own words for late results, in order, waiting for the model to stop speaking ([tellLate]): text and how. */
    private val lateSpeeches = ConcurrentLinkedQueue<Pair<String, () -> Unit>>()
    /** What 小博 says of late results now: words heard that match it are its own coming back. */
    @Volatile private var xiaoboSaying = ""
    /** When the service last started hearing, and when a slice above -60 dBFS last went to it (elapsed-realtime ms). */
    @Volatile private var hearingSince = 0L
    @Volatile private var soundSentAt = 0L
    /** Since when 小博 says a late result (elapsed-realtime ms; 0 when not): the model hears silence meanwhile. */
    @Volatile private var xiaoboSpeaksSince = 0L

    /**
     * Something 小博 finished with no ask_xiaobo waiting (a reply past the wait, a subagent's report, a plan) while the
     * model is on air, where 小博's own speech is off (owner 2026-10-20: 「中间出现子agent或者执行时间长一点，后面的播报就
     * 接不上了」). The model speaks only after the speaker or a tool result: `response.create` is refused and drops the
     * connection, and a text item alone is not answered (bench 2026-10-20). So with 补播报 on ([DebugRealtimeConfig.lateSpeech])
     * [speak] has 小博 say it once the model is quiet; either way the model gets it as a text item for what is asked next.
     */
    fun tellLate(text: String, speak: () -> Unit) {
        actions.trySend {
            if (created) connection.send(doubaoRealtimeNotice(text))
            // 补播报 off: the screen shows it and the model knows it, nobody says it ([DebugRealtimeConfig.lateSpeech]).
            if (!config.lateSpeech) return@trySend DebugVoiceLog.i(TAG, "late result (${text.length} chars): told to the model only, 补播报 off")
            lastActivity = SystemClock.elapsedRealtime()
            val waits = speaking || replyOwed()
            DebugVoiceLog.i(TAG, "late result (${text.length} chars): 小博 says it${if (waits) " once the model is quiet" else ""}")
            lateSpeeches += text to speak
            sayLate()
        }
    }

    /** 小博 says the late results waiting, in order (its announcer queues them), once the model is quiet and owes nothing. */
    private fun sayLate() {
        if (lateSpeeches.isEmpty() || speaking || replyOwed()) return
        if (xiaoboSpeaksSince == 0L) xiaoboSaying = ""
        xiaoboSpeaksSince = SystemClock.elapsedRealtime()
        while (true) {
            val (text, speak) = lateSpeeches.poll() ?: break
            xiaoboSaying += text
            main.post(speak)
        }
    }

    /**
     * Words heard while 小博 says a late result are the speaker cutting in (with [DebugRealtimeConfig.bargeIn]): sound
     * reached the service while it heard them ([soundSentAt]; 「好规划。」 came at meter 0.00) and they are not 小博's own
     * coming back ([looksLikeEcho]). Two letters at least: one is residue. Words with no start of their own are not.
     */
    private fun cutsInOnXiaobo(words: String): Boolean =
        config.bargeIn && words.count(Char::isLetterOrDigit) >= 2 && hearingSince > 0L && soundSentAt > 0L &&
            soundSentAt >= hearingSince - HEARING_LEAD_MILLIS && !looksLikeEcho(words, xiaoboSaying)

    /** The speaker cut in on 小博's late result: it stops, and the words are the model's to answer as any. */
    private fun bargeInOnXiaobo(words: String) {
        xiaoboSpeaksSince = 0L
        lateSpeeches.clear()
        heardOverXiaobo = false
        main.post { DebugTtsActivity.interrupt() }
        DebugVoiceLog.i(TAG, "barge-in over 小博's late result: $words")
    }

    /** Whether the model owes a reply ([replyOwedSince]); one not begun within [REPLY_OWED_MILLIS] is not coming. */
    private fun replyOwed(): Boolean {
        val since = replyOwedSince
        if (since == 0L) return false
        if (SystemClock.elapsedRealtime() - since < REPLY_OWED_MILLIS) return true
        replyOwedSince = 0L
        return false
    }

    /**
     * Whether 小博 is saying a late result: from [sayLate] until its announcer, which starts within a few seconds, is
     * done ([DebugRuntimeSession.speaking]; not [DebugTtsActivity], which the model's own speech holds too).
     */
    private fun xiaoboSpeaks(): Boolean {
        val since = xiaoboSpeaksSince
        if (since == 0L) return false
        if (DebugRuntimeSession.speaking || SystemClock.elapsedRealtime() - since < LATE_SPEECH_START_MILLIS) return true
        xiaoboSpeaksSince = 0L
        return false
    }

    private fun handle(event: DoubaoRealtimeEvent) {
        if (closing) return
        lastEventAt = SystemClock.elapsedRealtime()
        when (event) {
            is DoubaoRealtimeEvent.SessionCreated -> {
                created = true
                mutableState.update { it.copy(status = VoiceSessionStatus.LISTENING, message = null) }
                DebugVoiceLog.i(TAG, "session created ${event.id}")
            }
            DoubaoRealtimeEvent.HearingStarted -> hearingStarted()
            // Each delta is the whole sentence heard so far (帮, 帮我, 帮我看…), revised as it goes: it replaces, never appends.
            is DoubaoRealtimeEvent.Hearing -> {
                if (xiaoboSpeaks() && cutsInOnXiaobo(event.delta)) bargeInOnXiaobo(event.delta)
                mutableState.update { it.copy(liveText = event.delta) }
            }
            is DoubaoRealtimeEvent.Heard -> heard(event.text)
            is DoubaoRealtimeEvent.ReplyText -> replied(event.text)
            DoubaoRealtimeEvent.SpeechStarted -> dropSpeech = false
            is DoubaoRealtimeEvent.Speech -> {
                spokeThisResponse = true
                speak(converter.convert(event.float32))
            }
            DoubaoRealtimeEvent.SpeechDone -> closeSpeech()
            // end_conversation last: "导航回家然后退下吧" hands the task over before the goodbye is considered.
            is DoubaoRealtimeEvent.ToolCalls -> event.calls.sortedBy { it.name == END_CONVERSATION }.forEach(::callTool)
            is DoubaoRealtimeEvent.ResponseDone -> {
                spokeThisResponse = false
                droppedThisResponse = false
                replyOwedSince = 0L
                // The answer to words heard over 小博 is done, unless an ignored call's answer is still to come.
                if (ignoredCallAnswered) ignoredCallAnswered = false else heardOverXiaobo = false
                // A reply's speech is over with it: audio after a tool call opened a stretch no SpeechDone closed, and
                // 「speaking」 stayed on for 20 s of silence (2026-10-16). What is queued still plays.
                closeSpeech()
                sayLate()
                if (event.inputTokens + event.outputTokens > 0) {
                    DebugUsageStore.record(REALTIME_USAGE_MODEL, DebugModelUsage(1, event.inputTokens.toLong(), outputTokens = event.outputTokens.toLong()))
                }
            }
            is DoubaoRealtimeEvent.ServerError -> {
                DebugVoiceLog.w(TAG, "server error: ${event.message}")
                mutableState.update { it.copy(message = event.message) }
            }
            is DoubaoRealtimeEvent.Failure -> if (!reconnect(event.message)) end(event.message, failed = true)
            // Only the service closes while the session runs (stop() ignores what follows its own close).
            DoubaoRealtimeEvent.Closed -> if (!reconnect("closed by the service")) end("closed by the service", failed = !created)
        }
    }

    private fun hearingStarted() {
        lastActivity = SystemClock.elapsedRealtime()
        hearingSince = lastActivity
        mutableState.update { it.copy(micActive = true, liveText = "") }
        // Barge-in: what is buffered locally stops at once; the model hears the speaker and answers that. Only for a
        // voice we sent: the service once started hearing while the meter read 0.00 and cut an answer.
        if (config.bargeIn && speaking) {
            if (mutableState.value.micLevels.takeLast(BARGE_IN_SLICES).any { it > 0f }) {
                stopSpeech()
                DebugVoiceLog.i(TAG, "barge-in: speech stopped")
            } else {
                DebugVoiceLog.i(TAG, "barge-in ignored: nothing above -60 dBFS sent in the last second")
            }
        }
    }

    private fun heard(text: String) {
        val overXiaobo = xiaoboSpeaks()
        val cutsIn = overXiaobo && cutsInOnXiaobo(text)
        hearingSince = 0L
        if (overXiaobo) {
            if (!cutsIn) return heardOverXiaobo(text)
            bargeInOnXiaobo(text)
        }
        heardOverXiaobo = false
        spokeThisResponse = false
        val now = SystemClock.elapsedRealtime()
        replyOwedSince = now
        lastActivity = now
        heard = text
        heardAt = now
        heardWordsAt = now
        toolTurn = false
        dropSpeech = false
        DebugVoiceLog.i(TAG, "heard: $text")
        DebugRealtimeShown.exchange.value = DebugRealtimeExchange(text)
        val segment = VoiceSegment(
            id = ++segmentId,
            text = text,
            label = VoiceSegmentLabel.AGENT,
            judgment = null,
            reason = VoiceEndReason.JUDGED,
            endedAtEpochMillis = System.currentTimeMillis(),
            submitted = true,
            handOver = VoiceHandOverMode.NEW_TURN,
        )
        mutableState.update { it.copy(micActive = false, liveText = "", segments = (it.segments + segment).takeLast(MAX_SEGMENTS)) }
        if (endsWhenIdle && dismisses(text)) main.post(onDismiss)
    }

    /** Words heard while 小博 said a late result ([heardOverXiaobo]): logged, not acted on. */
    private fun heardOverXiaobo(text: String) {
        heardOverXiaobo = true
        lastActivity = SystemClock.elapsedRealtime()
        mutableState.update { it.copy(micActive = false, liveText = "") }
        DebugVoiceLog.i(TAG, "heard while 小博 says a late result, not the speaker's: $text (the model's answer is dropped)")
    }

    /** A reply that handed nothing to 小博 goes into the console's session, like 陪聊's; a task's reply is 小博's own. */
    private fun replied(text: String) {
        if (heardOverXiaobo) return DebugVoiceLog.i(TAG, "replied to words heard over 小博, dropped: $text")
        DebugVoiceLog.i(TAG, "replied${if (toolTurn) " after ask_xiaobo" else ""}: $text")
        val words = heard
        if (toolTurn || text.isBlank()) return
        DebugRealtimeShown.exchange.value = DebugRealtimeExchange(words, text)
        if (words.isBlank()) return
        heard = ""
        main.post { DebugRuntimeSession.recordCompanionTurn(context, words, text, REALTIME_LABEL, speak = false) }
    }

    private fun speak(pcm16: ByteArray) {
        if (dropSpeech || pcm16.isEmpty()) return
        // Never over 小博 saying a late result: what the model says then is a stale 「请稍等」 or an answer to words heard over it.
        if (heardOverXiaobo || xiaoboSpeaks()) {
            if (!droppedThisResponse) DebugVoiceLog.i(TAG, "the model's speech dropped: 小博 says a late result")
            droppedThisResponse = true
            return
        }
        val channel = speech ?: startSpeech()
        channel.trySend(DebugTtsAudioChunk(pcm16, PCM16_FORMAT))
    }

    /**
     * A stretch of the model's speech plays through its own player once the stretch before it has played, so audio
     * that arrives while the last one drains waits its turn, and stopping one never touches the next.
     */
    private fun startSpeech(): Channel<DebugTtsAudioChunk> {
        val channel = Channel<DebugTtsAudioChunk>(Channel.UNLIMITED)
        val player = AndroidDebugTtsPlayer(context)
        val previous = synchronized(stretches) { stretches.lastOrNull()?.job }
        speech = channel
        if (heardAt > 0) DebugVoiceLog.i(TAG, "speaking ${SystemClock.elapsedRealtime() - heardAt} ms after the words ended")
        heardAt = 0
        if (!speaking) {
            speaking = true
            DebugTtsActivity.begin(this) { actions.trySend { stopSpeech() } }
            mutableState.update { it.copy(assistantSpeaking = true) }
        }
        val job = scope.launch {
            try {
                previous?.join()
                player.playStream(channel.consumeAsFlow())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DebugVoiceLog.w(TAG, "playback failed: ${error.message}")
                // The rest of this reply has nowhere to play: dropped, so speaking ends and the microphone opens again.
                actions.trySend {
                    if (speech === channel) {
                        channel.cancel()
                        speech = null
                        dropSpeech = true
                    }
                }
            } finally {
                actions.trySend { stretchEnded(player) }
            }
        }
        synchronized(stretches) { stretches += SpeechStretch(player, job) }
        return channel
    }

    private fun closeSpeech() {
        speech?.close()
        speech = null
        if (synchronized(stretches) { stretches.isEmpty() }) quiet()
    }

    private fun stretchEnded(player: AndroidDebugTtsPlayer) {
        val left = synchronized(stretches) {
            if (!stretches.removeAll { it.player === player }) return
            stretches.size
        }
        if (left == 0 && speech == null) quiet()
    }

    private fun quiet() {
        if (!speaking) return
        speaking = false
        quietSince = SystemClock.elapsedRealtime()
        lastActivity = quietSince
        DebugTtsActivity.end(this)
        mutableState.update { it.copy(assistantSpeaking = false) }
        sayLate()
    }

    /**
     * Stops all speech now and drops the rest of the reply until the speaker's next words or the model's next reply.
     * The model is not sent `response.cancel`: on a test device (2026-10-13), after a wake cut a reply off with it,
     * the service opened a transcription by itself and then heard the next words late (26 s) or never; without it
     * the speaker's next words cut the reply off on the service's side, as every barge-in did.
     */
    private fun stopSpeech() {
        if (!speaking) return
        dropSpeech = true
        speech?.cancel()
        speech = null
        val stopped = synchronized(stretches) { stretches.toList().also { stretches.clear() } }
        stopped.forEach { it.job.cancel() }
        main.post { stopped.forEach { it.player.stop() } }
        quiet()
        mutableState.update { it.copy(interruptions = it.interruptions + 1) }
    }

    /** The speaker said goodbye while 小博 still had their task: the conversation ends once it is answered. */
    private var endAfterTasks = false

    private fun callTool(call: DoubaoToolCall) {
        toolTurn = true
        // Asked on words heard over 小博's late result: nothing is handed over, and nothing ends.
        if (heardOverXiaobo) {
            DebugVoiceLog.i(TAG, "${call.name} on words heard over 小博: ignored")
            if (call.name != END_CONVERSATION) {
                connection.send(doubaoRealtimeToolResult(call.callId, IGNORED_CALL_RESULT))
                ignoredCallAnswered = true
            }
            return
        }
        DebugRealtimeShown.exchange.value = null
        // Speech said before the call ("好，我来办") plays out; what follows the result is a new stretch.
        closeSpeech()
        if (call.name == END_CONVERSATION) {
            // Ending now would cancel the task just handed to 小博 (and the run it started); it ends with the answer.
            if (pendingCalls.isNotEmpty() || orphanedCalls.isNotEmpty()) {
                DebugVoiceLog.i(TAG, "the model ends the conversation once 小博's ${pendingCalls.size + orphanedCalls.size} task(s) are answered")
                endAfterTasks = true
                return
            }
            endByVoice()
            return
        }
        if (call.name != ASK_XIAOBO) {
            connection.send(doubaoRealtimeToolResult(call.callId, "没有这个工具：${call.name}"))
            return
        }
        // The user's own words go with 豆包's rewording, which may add details the user did not say.
        val words = heard.takeIf { it.isNotBlank() && heardWordsAt > 0 && SystemClock.elapsedRealtime() - heardWordsAt < HEARD_FRESH_MILLIS }
        val request = withUserWords(call.request(), words)
        DebugVoiceLog.i(TAG, "ask_xiaobo: $request")
        pendingCalls += call.callId
        pendingTools = pendingCalls.size + orphanedCalls.size
        mutableState.update { it.copy(agentWorking = true) }
        val started = SystemClock.elapsedRealtime()
        // After words of its own ("好，我来规划") the service's voice waits for this result and gives up near 30 s
        // (AudioTTSIdleTimeoutError, bench 2026-10-20: a 34 s plan was lost with the connection); answered sooner.
        val within = if (spokeThisResponse) SPOKEN_CALL_REPLY_MILLIS else AGENT_REPLY_MILLIS
        scope.launch {
            val reply = runCatching { agent.run(request, within) }.getOrElse { error ->
                if (error is CancellationException) throw error
                "小博执行失败：${error.message ?: error.javaClass.simpleName}"
            }
            actions.trySend { toolAnswered(call, reply, started) }
        }
    }

    private fun endByVoice() {
        DebugVoiceLog.i(TAG, "the model ends the conversation at the speaker's word")
        if (endsWhenIdle) main.post(onDismiss) else end("ended by voice", failed = false)
    }

    private fun toolAnswered(call: DoubaoToolCall, reply: String, started: Long) {
        // Asked on a connection that has since been replaced: done, its answer stays on the card.
        if (orphanedCalls.remove(call.callId)) {
            pendingTools = pendingCalls.size + orphanedCalls.size
            lastActivity = SystemClock.elapsedRealtime()
            mutableState.update { it.copy(agentWorking = pendingTools > 0) }
            endIfTasksDone()
            return
        }
        // Already answered as merged.
        val index = pendingCalls.indexOf(call.callId)
        if (index < 0) return
        // Calls made before this one joined the same run (their waits end with it, in any order): answered as merged.
        val earlier = pendingCalls.subList(0, index).toList()
        earlier.forEach { connection.send(doubaoRealtimeToolResult(it, REALTIME_MERGED_RESULT)) }
        pendingCalls.removeAll(earlier)
        val result = realtimeToolAnswer(call.callId, pendingCalls, reply)
        pendingCalls -= call.callId
        pendingTools = pendingCalls.size + orphanedCalls.size
        lastActivity = SystemClock.elapsedRealtime()
        mutableState.update { it.copy(agentWorking = pendingTools > 0) }
        DebugVoiceLog.i(TAG, "ask_xiaobo answered in ${SystemClock.elapsedRealtime() - started} ms: $result")
        dropSpeech = false
        heardAt = SystemClock.elapsedRealtime()
        replyOwedSince = heardAt
        connection.send(doubaoRealtimeToolResult(call.callId, result))
        endIfTasksDone()
    }

    private fun endIfTasksDone() {
        if (endAfterTasks && pendingCalls.isEmpty() && orphanedCalls.isEmpty()) {
            endAfterTasks = false
            endByVoice()
        }
    }

    /**
     * 16 kHz PCM16 to the model in 20 ms slices, with the level meter fed from what is sent; what is heard before the
     * session opens (the words after a wake) waits, up to [MAX_PENDING_SLICES], and goes first.
     */
    private suspend fun streamMicrophone() {
        val pending = ArrayDeque<ByteArray>()
        try {
            audio.frames().collect { recorded ->
                framesRead += 1
                val frame = gate.process(recorded)
                // Half duplex hears silence while anything speaks; full duplex hears the speaker over 小博's late result
                // too, and tells them from 小博's own words ([cutsInOnXiaobo]).
                val deaf = !config.bargeIn && (DebugTtsActivity.active.value || SystemClock.elapsedRealtime() - quietSince < ECHO_TAIL_MILLIS || xiaoboSpeaks())
                val levels = ArrayList<Float>(frame.size / SLICE_SAMPLES + 1)
                var start = 0
                while (start < frame.size) {
                    val heard = frame.copyOfRange(start, min(start + SLICE_SAMPLES, frame.size))
                    start += SLICE_SAMPLES
                    if (deaf) heard.fill(0)
                    val slice = injected.poll() ?: heard
                    val level = levelOf(slice)
                    if (level > 0f) soundSentAt = SystemClock.elapsedRealtime()
                    levels += level
                    pending.addLast(slice.littleEndianBytes())
                    if (pending.size > MAX_PENDING_SLICES) pending.removeFirst()
                    while (created && pending.isNotEmpty()) {
                        if (connection.send(doubaoRealtimeAudio(pending.removeFirst()))) slicesSent += 1 else sendsRefused += 1
                    }
                }
                mutableState.update { it.copy(micLevels = (it.micLevels + levels).takeLast(MAX_LEVELS), nearGate = gate.stats()) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            actions.trySend { end("麦克风出错：${error.message ?: error.javaClass.simpleName}", failed = true) }
        }
    }

    private suspend fun logStats() {
        while (true) {
            delay(STATS_LOG_MILLIS)
            // A late result held for a reply that never came is said once the wait for it is over.
            if (lateSpeeches.isNotEmpty()) actions.trySend { sayLate() }
            val state = mutableState.value
            Log.i(
                TAG,
                "mic: $framesRead frames, $slicesSent slices sent, $sendsRefused refused, ${injected.size} injected left; " +
                    "last event ${SystemClock.elapsedRealtime() - lastEventAt} ms ago; hearing=${state.micActive} speaking=$speaking",
            )
        }
    }

    /** A woken conversation ends after [REALTIME_IDLE_END_MILLIS] with nobody talking, nothing said and 小博 idle. */
    private suspend fun endWhenIdle() {
        while (true) {
            delay(IDLE_CHECK_MILLIS)
            val now = SystemClock.elapsedRealtime()
            // With 补播报, 小博 still working (past ask_xiaobo's wait) keeps the conversation open for its answer ([tellLate]).
            val busy = speaking || pendingTools > 0 || DebugTtsActivity.active.value || mutableState.value.liveText.isNotBlank() ||
                config.lateSpeech && (lateSpeeches.isNotEmpty() || xiaoboSpeaksSince != 0L || DebugRuntimeSession.isRunning)
            if (busy) lastActivity = now
            if (now - lastActivity >= REALTIME_IDLE_END_MILLIS) {
                actions.trySend { end("idle for ${REALTIME_IDLE_END_MILLIS / 1000} s", failed = false) }
                return
            }
        }
    }

    private fun end(reason: String, failed: Boolean) {
        if (closing) return
        DebugVoiceLog.i(TAG, "ending: $reason")
        if (failed) mutableState.update { it.copy(status = VoiceSessionStatus.ERROR, message = reason) }
        main.post { onEnd(reason) }
    }
}

/**
 * ask_xiaobo calls waiting for a run's reply now: that reply goes back as their tool result. A result while none waits
 * (past the wait, a report, a plan) is told to the conversation instead ([DebugRealtimeVoice.tellLate]).
 */
internal object DebugRealtimeWaits {
    private val count = AtomicInteger()
    val waiting: Boolean get() = count.get() > 0
    /** A run's result that came while a call waited: its own reply, unless the wait ran out first. */
    @Volatile private var held: String? = null

    fun begin() {
        count.incrementAndGet()
    }

    fun end() {
        if (count.decrementAndGet() == 0) held = null
    }

    /** A result for the waiting call ([DebugRunSpeech]): taken by it if its wait runs out before the reply reaches it. */
    fun hold(reply: String) {
        held = reply
    }

    /**
     * The result that came while the call waited, once: the run finished just as the wait ran out, and its result,
     * no longer told to the conversation, would otherwise be lost behind 「请稍等」.
     */
    fun takeHeld(): String? = held.also { held = null }
}

/** 小博 in the console's session: [request] goes in as a spoken turn (or joins the run in progress), its reply comes back. */
internal fun localRealtimeAgent(context: Context) = DebugRealtimeAgent { request, replyWithinMillis ->
    // Main, not Main.immediate: the wait resumes after the listeners have been told, not inside that loop.
    withContext(Dispatchers.Main) {
        val reply = CompletableDeferred<String>()
        val started = CompletableDeferred<Unit>()
        var seen: DebugUiState? = null
        val detach = DebugRuntimeSession.attach(context) { ui ->
            seen = ui
            if (ui.isRunning) started.complete(Unit) else if (started.isCompleted) reply.complete(ui.latestReply())
        }
        DebugRealtimeWaits.begin()
        try {
            // The card and transcript show 豆包's request; the model also reads the user's own words with it.
            DebugRuntimeSession.postSpokenInput(context, withoutUserWords(request), wireText = request, modelText = request)
            when {
                withTimeoutOrNull(AGENT_START_MILLIS) { started.await() } == null -> {
                    // 2026-10-16: a request timed out here with nothing else in the log; say what the console showed.
                    seen?.let { DebugVoiceLog.i(TAG, "ask_xiaobo not taken up: ${it.notTakenUp()}") }
                    "小博没有接到这件事，请让用户稍后再说一次。"
                }
                // Past the wait the answer comes later, through tellLate; unless the run's result came just as it ended.
                else -> withTimeoutOrNull(replyWithinMillis) { reply.await() } ?: DebugRealtimeWaits.takeHeld()
                    ?: if (loadDebugRealtimeConfig(context).lateSpeech) REALTIME_STILL_WORKING else REALTIME_STILL_WORKING_SHOWN
            }
        } finally {
            DebugRealtimeWaits.end()
            detach()
        }
    }
}

/** The console as an ask_xiaobo that never started saw it last: its session, status and the request it was on. */
internal fun DebugUiState.notTakenUp(): String =
    "session=$activeSessionId running=$isRunning status=${status.take(80)} summary=${lastRunSummary.orEmpty()} " +
        "request=${currentRequest?.text.orEmpty().take(40)}"

/** The run's reply, else its summary or status: the last assistant message since the latest request. */
private fun DebugUiState.latestReply(): String {
    val header = items.indexOfLast { it is DebugConversationItem.TurnHeader }
    val reply = items.drop(header + 1).filterIsInstance<DebugConversationItem.MessageItem>()
        .lastOrNull { it.role == DebugTranscriptRole.ASSISTANT }?.content
    return reply?.takeIf(String::isNotBlank) ?: lastRunSummary?.takeIf(String::isNotBlank) ?: status
}

/**
 * The result for [callId] among [pending] (oldest first). A later call made while it waited joined the same run (an
 * addition steered into it, owner's bench 2026-10-13: 「导航到深圳北站…」 then 「最后目的地找一个肯德基」), so both end
 * together: the earlier one only says so and the latest carries 小博's reply, which the model then says once.
 */
internal fun realtimeToolAnswer(callId: String, pending: List<String>, reply: String): String =
    if (pending.indexOf(callId) in 0 until pending.lastIndex) REALTIME_MERGED_RESULT else realtimeToolResult(reply)

/** What the model gets back from 小博: plain text without markdown marks, at most [MAX_TOOL_RESULT_CHARS]. */
internal fun realtimeToolResult(reply: String): String =
    reply.replace(MARKDOWN_MARKS, "").replace(BLANK_RUNS, " ").trim().ifEmpty { "小博办完了，没有说明。" }.take(MAX_TOOL_RESULT_CHARS)

/**
 * The end-to-end conversation's gate: 10 dB under the caller's wake level as in a Jev session (过滤远处人声), else
 * [REALTIME_FLOOR_DB]. Seeduplex answers even faint sound: on a test device it heard words in music the microphone's
 * echo gate had brought under -60 dBFS (meter 0.00) and replied to them, while speech there sits near -25 dBFS.
 */
internal fun realtimeGate(
    callerLevelDb: Double?,
    nearGate: Boolean,
    belowDb: Double = NearSpeechGate.DEFAULT_BELOW_REFERENCE_DB,
): NearSpeechGate = NearSpeechGate(callerLevelDb?.takeIf { nearGate } ?: (REALTIME_FLOOR_DB + belowDb), belowDb)

/** 退下吧 and its kin end a woken conversation, said on their own or closing a short sentence. */
/**
 * A goodbye said on its own, after at most a filler ("好了，再见", "那就退下吧"). Anything more is the model's to judge
 * (end_conversation): "回家然后退下吧" hands the task over first, "导航关闭语音" turns 导航's voice off, "别退下" stays.
 * A woken Jev session ends on it too, sentence by sentence (VoiceSession's `dismissal`).
 */
internal fun dismisses(text: String): Boolean = withoutFillers(text.replace(PUNCTUATION, "")) in DISMISS_PHRASES

private fun withoutFillers(words: String): String {
    var rest = words
    while (true) {
        val filler = DISMISS_FILLERS.firstOrNull { rest.startsWith(it) && rest.length > it.length } ?: return rest
        rest = rest.removePrefix(filler)
    }
}

private class SpeechStretch(val player: AndroidDebugTtsPlayer, val job: Job)

private fun ShortArray.littleEndianBytes(): ByteArray {
    val out = ByteArray(size * 2)
    forEachIndexed { i, sample ->
        out[2 * i] = sample.toInt().toByte()
        out[2 * i + 1] = (sample.toInt() shr 8).toByte()
    }
    return out
}

private const val TAG = "DebugRealtime"
internal const val REALTIME_LABEL = "豆包端到端"
private const val REALTIME_USAGE_MODEL = "doubao-seeduplex"
private const val PCM16_FORMAT = "pcm16"
/** 20 ms at 16 kHz, the slice the realtime API asks for. */
private const val SLICE_SAMPLES = 320
/** Two seconds of words before the session opens. */
private const val MAX_PENDING_SLICES = 100
/** Two seconds of the 50-per-second meter. */
private const val MAX_LEVELS = 100
/** The last second of 20 ms slices: a barge-in needs a sent slice above -60 dBFS (level 0) among them. */
private const val BARGE_IN_SLICES = 50
private const val MAX_SEGMENTS = 20
/** Deaf a little past the end of speech, for the room's echo. */
private const val ECHO_TAIL_MILLIS = 300L
internal const val REALTIME_IDLE_END_MILLIS = 20_000L
/** Sound under this never reaches the model without a wake level to go by. */
internal const val REALTIME_FLOOR_DB = -50.0
private const val IDLE_CHECK_MILLIS = 500L
private const val STATS_LOG_MILLIS = 5_000L
private const val MAX_RECONNECTS = 1
private const val AGENT_START_MILLIS = 8_000L
private const val AGENT_REPLY_MILLIS = 120_000L
/** The user's last words go with an ask_xiaobo call made within this of them. */
private const val HEARD_FRESH_MILLIS = 15_000L
/** A call after the model already spoke is answered within this, before the service's voice times out (~30 s). */
private const val SPOKEN_CALL_REPLY_MILLIS = 20_000L
/** What the model says when 小博 needs longer; 小博 tells the result itself when done ([DebugRealtimeVoice.tellLate]). */
private const val REALTIME_STILL_WORKING = "小博还在办，办好了会自己告诉用户。现在用一句话请用户稍等，不要说结果。"
/** The same without 补播报: the result only shows on the screen (as before 2026-10-20). */
private const val REALTIME_STILL_WORKING_SHOWN = "小博还在办，结果稍后会显示在屏幕上。现在用一句话告诉用户，不要说结果。"
/** 小博's speech of a late result starts within this (its TTS request); the model hears silence until it ends. */
private const val LATE_SPEECH_START_MILLIS = 4_000L
/** The service starts hearing up to this after the sound that set it off. */
private const val HEARING_LEAD_MILLIS = 1_500L
/** A reply the model owes and has not finished within this is not coming; a late result waits no longer. */
private const val REPLY_OWED_MILLIS = 8_000L
/** What a call made on words heard over 小博's late result gets back. */
internal const val IGNORED_CALL_RESULT = "这句话是在小博播报结果时听到的，不是用户的新要求，已忽略。不要回复，不要调用工具。"
private const val MAX_TOOL_RESULT_CHARS = 800
internal const val REALTIME_MERGED_RESULT = "这件事和用户后来补充的要求已经合并成一件交给小博办，结果在下一条工具结果里，这条不用单独回复。"
private val DISMISS_PHRASES = setOf(
    "退下吧", "退下", "再见", "拜拜", "不聊了", "你走吧", "结束对话", "退出对话", "退出语音", "关闭语音", "关掉语音",
    // Said alone right after a wake (owner 2026-10-20: 「唤醒小博后，马上说退出他不会退出」); Jev reads it so too.
    "退出", "退出吧",
)
// 「那个就是退出」 came back as one sentence on the bench (2026-10-20): what a speaker says before getting the word out.
private val DISMISS_FILLERS = listOf("好的", "好了", "那就", "那我", "那个", "就是", "小博", "好", "那", "嗯", "呃", "啊", "行", "你", "我", "先", "就")
private val PUNCTUATION = Regex("[\\p{P}\\s]")
private val MARKDOWN_MARKS = Regex("[*#`>|]")
private val BLANK_RUNS = Regex("\\s+")

/** [request] as 豆包 worded it, with the user's own [words] when they differ: 豆包 may fill in what the user did not say. */
internal fun withUserWords(request: String, words: String?): String {
    val said = words?.trim()?.takeIf(String::isNotEmpty) ?: return request
    return if (said.filter(Char::isLetterOrDigit) == request.filter(Char::isLetterOrDigit)) request else "$request（用户原话：「$said」）"
}

/** [text] without the user's words [withUserWords] added, as the card and transcript show it. */
internal fun withoutUserWords(text: String): String = text.replace(USER_WORDS, "")

private val USER_WORDS = Regex("（用户原话：「(.*?)」）")
