package cc.noharry.bothy.app.companion
import android.content.Context
import android.os.SystemClock
import android.util.Log
import cc.noharry.bothy.voice.VoiceHandOver
import cc.noharry.bothy.voice.VoiceHandOverMode
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.evidence.BenchHttpTransport
import cc.noharry.bothy.app.evidence.CheckResult
import cc.noharry.bothy.app.evidence.CheckState
import cc.noharry.bothy.app.memory.UserMemoryStore
import cc.noharry.bothy.app.memory.memoryProfileText
import cc.noharry.bothy.app.runtime.RuntimeSession
import cc.noharry.bothy.app.voice.VoiceFeedback
import cc.noharry.bothy.app.voice.VoiceLog
import cc.noharry.bothy.app.voice.loadVoiceConfig
import cc.noharry.bothy.app.voice.realtime.loadRealtimeConfig
import cc.noharry.bothy.app.voice.submitVoiceText
import cc.noharry.bothy.app.voice.tts.validationError
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        (config.validationError(context) ?: config.readinessError(context))?.let { throw IllegalStateException(it) }
        val key = CompanionKeys.read(context, config.service).ifEmpty {
            val name = context.getString(config.service.label)
            throw IllegalStateException(context.getString(R.string.companion_error_no_key, name))
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
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        synchronized(decisions) {
            decisions.addLast(RecentDecision(stamp, decision, handOver.text.take(LOG_CHARS)))
            while (decisions.size > MAX_DECISIONS) decisions.removeFirst()
        }
    }

    private const val LOG_CHARS = 40
    private const val MAX_DECISIONS = 30
}

/** One routing decision for 设置 → 陪聊: when, where the turn went and why, and the start of the words. */
internal data class RecentDecision(val time: String, val decision: CompanionDecision, val words: String)

/** Why asking the service failed, in the screen's language: 设置 → 陪聊's 试一试 and 检查 → 陪聊. */
internal fun companionFailureText(context: Context, error: Throwable): String = when (error) {
    is CompanionHttpException -> {
        val head = error.serviceMessage
            ?.let { context.getString(R.string.companion_http_with_message, error.status, it) }
            ?: "HTTP ${error.status}"
        val hint = when (error.status) {
            401, 403 -> R.string.companion_http_hint_key
            404 -> R.string.companion_http_hint_address
            else -> null
        }
        hint?.let { context.getString(R.string.companion_http_with_hint, head, context.getString(it)) } ?: head
    }
    is CompanionTimeoutException ->
        context.resources.getQuantityString(R.plurals.companion_error_timeout, error.seconds, error.seconds)
    else -> error.message ?: error.javaClass.simpleName
}

/** 检查 → 陪聊: off, or one short chat with the configured service and how long it took. */
internal suspend fun checkCompanion(context: Context): CheckResult {
    val config = loadCompanionConfig(context)
    if (!config.enabled) return CheckResult(CheckState.OFF, context.getString(R.string.companion_check_off))
    // 端到端 has 豆包 chat itself and never hands a turn to 陪聊 (owner 2026-10-14: 「陪聊只在非端到端模式下用」).
    if (loadRealtimeConfig(context).enabled) {
        return CheckResult(CheckState.OFF, context.getString(R.string.companion_check_realtime))
    }
    val service = context.getString(config.service.label)
    val name = "$service · ${config.endpoint().model} · ${context.getString(config.mode.shortLabel)}"
    val started = SystemClock.elapsedRealtime()
    return try {
        val reply = SmallTalkCompanion.ask(context, config, "你好")
        val elapsed = SystemClock.elapsedRealtime() - started
        if (reply.isBlank()) CheckResult(CheckState.WARN, context.getString(R.string.companion_check_empty, name))
        else CheckResult(CheckState.OK, context.getString(R.string.companion_check_ok, name, elapsed))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        CheckResult(CheckState.FAIL, "$name · ${companionFailureText(context, error)}")
    }
}
