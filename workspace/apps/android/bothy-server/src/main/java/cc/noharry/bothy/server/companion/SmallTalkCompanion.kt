package cc.noharry.bothy.server.companion
import android.content.Context
import android.os.SystemClock
import cc.noharry.agent.appserver.FoundException
import cc.noharry.agent.appserver.evidence.BenchHttpTransport
import cc.noharry.agent.appserver.finding
import cc.noharry.agent.appserver.memory.UserMemoryStore
import cc.noharry.agent.appserver.memory.memoryProfileText
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.parts
import cc.noharry.agent.appserver.protocol.text
import cc.noharry.agent.appserver.runtime.RuntimeSession
import cc.noharry.bothy.protocol.BothyFindingCode
import cc.noharry.bothy.protocol.CompanionConfig
import cc.noharry.bothy.server.evidence.CheckResult
import cc.noharry.bothy.server.evidence.CheckState
import cc.noharry.bothy.server.voice.VoiceFeedback
import cc.noharry.bothy.server.voice.VoiceLog
import cc.noharry.bothy.server.voice.loadVoiceConfig
import cc.noharry.bothy.server.voice.realtime.loadRealtimeConfig
import cc.noharry.bothy.server.voice.submitVoiceText
import cc.noharry.bothy.server.voice.tts.validationError
import cc.noharry.bothy.voice.VoiceHandOver
import cc.noharry.bothy.voice.VoiceHandOverMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 陪聊 in a voice session. Every new turn while the companion is on is decided and logged ([decideCompanionRoute]);
 * small talk (in route mode) goes to the companion service, whose reply is shown, kept in the session for the agent's
 * context and spoken in 小博's own voice. A reply that hands back (【交回】), an error or a timeout sends the words to
 * the agent instead, so a turn is never lost. Main thread.
 */
internal object SmallTalkCompanion {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var lastReply: String? = null
    private val decisions = ArrayDeque<RecentDecision>()

    /** Whether Jev should score small talk: the companion is on (either mode). Read for each turn. */
    fun scoring(context: Context): Boolean = loadCompanionConfig(context).scoring

    /** The last routing decisions, newest first, for 设置 → 陪聊. */
    fun recentDecisions(): List<RecentDecision> = synchronized(decisions) { decisions.toList().asReversed() }

    /** A pending reply is dropped: the next turn went to the agent, or the session ended. */
    fun cancel() {
        job?.cancel()
        job = null
    }

    /**
     * A voice turn: true when the companion took it. [lastAssistant] is the last reply shown (an agent's question
     * means the words answer it); [onReply] hears the companion's reply for the voice session's history.
     */
    fun take(context: Context, handOver: VoiceHandOver, lastAssistant: String?, onReply: (String) -> Unit): Boolean {
        val config = loadCompanionConfig(context)
        if (!config.enabled || handOver.mode != VoiceHandOverMode.NEW_TURN) return false
        val decision = decideCompanionRoute(config, handOver.text, handOver.smallTalk, lastAssistant, lastAssistant != null && lastAssistant == lastReply)
        remember(decision, handOver)
        if (!decision.toCompanion) return false
        cancel()
        val app = context.applicationContext
        VoiceFeedback.handedOver(handOver.mode, handOver.text, loadVoiceConfig(app).handOverChime)
        job = scope.launch { chat(app, config, handOver, onReply) }
        return true
    }

    private suspend fun chat(context: Context, config: CompanionConfig, handOver: VoiceHandOver, onReply: (String) -> Unit) {
        val started = SystemClock.elapsedRealtime()
        val label = "${context.getString(config.service.label)} · ${config.endpoint().model}"
        val reply = try {
            ask(context, config, handOver.text, RuntimeSession.recentExchanges(context))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            VoiceLog.w(COMPANION_TAG, "$label failed after ${SystemClock.elapsedRealtime() - started} ms: ${error.message}; the agent takes the turn")
            return submitVoiceText(context, handOver, feedback = false)
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        if (reply.isBlank() || reply.startsWith(COMPANION_HANDOFF)) {
            VoiceLog.i(COMPANION_TAG, "$label handed back in $elapsed ms; the agent takes the turn")
            return submitVoiceText(context, handOver, feedback = false)
        }
        VoiceLog.i(COMPANION_TAG, "$label replied in $elapsed ms: ${reply.take(LOG_CHARS)}")
        lastReply = reply
        RuntimeSession.recordCompanionTurn(context, handOver.text, reply, label)
        onReply(reply)
    }

    /** One chat with the configured service; the settings page's 试一试 and the health check use it too. */
    suspend fun ask(context: Context, config: CompanionConfig, words: String, recent: List<Pair<String, String>> = emptyList()): String {
        (config.validationError(context) ?: config.readinessError(context))?.let { throw FoundException(it) }
        val key = CompanionKeys.read(context, config.service).ifEmpty {
            throw FoundException(Finding(BothyFindingCode.COMPANION_ERROR_NO_KEY, more = listOf(finding(config.service.code))))
        }
        return withContext(Dispatchers.IO) {
            val profile = memoryProfileText(UserMemoryStore.inUse(context))
            CompanionChat(transport(context)).reply(config.endpoint(), key, companionMessages(config.persona, recent, words, profile))
        }
    }

    @Volatile private var http: BenchHttpTransport? = null

    private fun transport(context: Context): BenchHttpTransport = http ?: BenchHttpTransport(context.applicationContext).also { http = it }

    private fun remember(decision: CompanionDecision, handOver: VoiceHandOver) {
        VoiceLog.i(COMPANION_TAG, "route ${if (decision.toCompanion) "companion" else "agent"} (${decision.reason}): ${handOver.text.take(LOG_CHARS)}")
        synchronized(decisions) {
            decisions.addLast(RecentDecision(System.currentTimeMillis(), decision, handOver.text.take(LOG_CHARS)))
            while (decisions.size > MAX_DECISIONS) decisions.removeFirst()
        }
    }

    private const val LOG_CHARS = 40
    private const val MAX_DECISIONS = 30
}

/** One routing decision for 设置 → 陪聊: when, where the turn went and why, and the start of the words. */
internal data class RecentDecision(val atEpochMillis: Long, val decision: CompanionDecision, val words: String)

/** Why asking the service failed: 设置 → 陪聊's 试一试 and 检查 → 陪聊. */
internal fun companionFailure(error: Throwable): Finding = when (error) {
    is CompanionHttpException -> {
        val head = error.serviceMessage
            ?.let { finding(BothyFindingCode.COMPANION_HTTP_WITH_MESSAGE, error.status, it) }
            ?: finding(FindingCode.HTTP_STATUS, error.status)
        val hint = when (error.status) {
            401, 403 -> BothyFindingCode.COMPANION_HTTP_HINT_KEY
            404 -> BothyFindingCode.COMPANION_HTTP_HINT_ADDRESS
            else -> null
        }
        hint?.let { Finding(BothyFindingCode.COMPANION_HTTP_WITH_HINT, more = listOf(head, finding(it))) } ?: head
    }
    is CompanionTimeoutException -> Finding(BothyFindingCode.COMPANION_ERROR_TIMEOUT, listOf(error.seconds.toString()), count = error.seconds)
    else -> error.finding()
}

/** 检查 → 陪聊: off, or one short chat with the configured service and how long it took. */
internal suspend fun checkCompanion(context: Context): CheckResult {
    val config = loadCompanionConfig(context)
    if (!config.enabled) return CheckResult(CheckState.OFF, finding(BothyFindingCode.COMPANION_CHECK_OFF))
    // 端到端 has 豆包 chat itself and never hands a turn to 陪聊 (owner 2026-10-14: 「陪聊只在非端到端模式下用」).
    if (loadRealtimeConfig(context).enabled) return CheckResult(CheckState.OFF, finding(BothyFindingCode.COMPANION_CHECK_REALTIME))
    val name = parts(finding(config.service.code), text(config.endpoint().model), finding(config.mode.shortCode))
    val started = SystemClock.elapsedRealtime()
    return try {
        val reply = SmallTalkCompanion.ask(context, config, "你好")
        val elapsed = SystemClock.elapsedRealtime() - started
        if (reply.isBlank()) CheckResult(CheckState.WARN, Finding(BothyFindingCode.COMPANION_CHECK_EMPTY, more = listOf(name)))
        else CheckResult(CheckState.OK, Finding(BothyFindingCode.COMPANION_CHECK_OK, more = listOf(name, text(elapsed))))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        CheckResult(CheckState.FAIL, parts(name, companionFailure(error)))
    }
}
