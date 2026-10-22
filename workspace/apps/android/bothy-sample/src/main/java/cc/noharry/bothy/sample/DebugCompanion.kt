package cc.noharry.bothy.sample

import android.content.Context
import android.os.SystemClock
import android.util.Log
import cc.noharry.agent.voice.VoiceHandOver
import cc.noharry.agent.voice.VoiceHandOverMode
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
internal object DebugCompanion {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var lastReply: String? = null
    private val decisions = ArrayDeque<String>()

    /** Whether Jev should score small talk: the companion is on (either mode). Read for each turn. */
    fun scoring(context: Context): Boolean = loadDebugCompanionConfig(context).scoring

    /** The last routing decisions, newest first, for 设置 → 陪聊. */
    fun recentDecisions(): List<String> = synchronized(decisions) { decisions.toList().asReversed() }

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
        val config = loadDebugCompanionConfig(context)
        if (!config.enabled || handOver.mode != VoiceHandOverMode.NEW_TURN) return false
        val decision = decideCompanionRoute(config, handOver.text, handOver.smallTalk, lastAssistant, lastAssistant != null && lastAssistant == lastReply)
        remember(decision, handOver)
        if (!decision.toCompanion) return false
        cancel()
        val app = context.applicationContext
        DebugVoiceFeedback.handedOver(handOver.mode, handOver.text, loadDebugVoiceConfig(app).handOverChime)
        job = scope.launch { chat(app, config, handOver, onReply) }
        return true
    }

    private suspend fun chat(context: Context, config: DebugCompanionConfig, handOver: VoiceHandOver, onReply: (String) -> Unit) {
        val started = SystemClock.elapsedRealtime()
        val label = "${config.service.label} · ${config.endpoint().model}"
        val reply = try {
            ask(context, config, handOver.text, DebugRuntimeSession.recentExchanges(context))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DebugVoiceLog.w(COMPANION_TAG, "$label failed after ${SystemClock.elapsedRealtime() - started} ms: ${error.message}; the agent takes the turn")
            return submitDebugVoiceText(context, handOver, feedback = false)
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        if (reply.isBlank() || reply.startsWith(COMPANION_HANDOFF)) {
            DebugVoiceLog.i(COMPANION_TAG, "$label handed back in $elapsed ms; the agent takes the turn")
            return submitDebugVoiceText(context, handOver, feedback = false)
        }
        DebugVoiceLog.i(COMPANION_TAG, "$label replied in $elapsed ms: ${reply.take(LOG_CHARS)}")
        lastReply = reply
        DebugRuntimeSession.recordCompanionTurn(context, handOver.text, reply, label)
        onReply(reply)
    }

    /** One chat with the configured service; the settings page's 试一试 and the health check use it too. */
    suspend fun ask(context: Context, config: DebugCompanionConfig, words: String, recent: List<Pair<String, String>> = emptyList()): String {
        (config.validationError() ?: config.readinessError())?.let { throw IllegalStateException(it) }
        val key = DebugCompanionKeys.read(context, config.service).ifEmpty { throw IllegalStateException("${config.service.label} 没有 API key") }
        return withContext(Dispatchers.IO) {
            val profile = memoryProfileText(DebugUserMemoryStore.inUse(context))
            DebugCompanionChat(transport(context)).reply(config.endpoint(), key, companionMessages(config.persona, recent, words, profile))
        }
    }

    @Volatile private var http: BenchHttpTransport? = null

    private fun transport(context: Context): BenchHttpTransport = http ?: BenchHttpTransport(context.applicationContext).also { http = it }

    private fun remember(decision: CompanionDecision, handOver: VoiceHandOver) {
        DebugVoiceLog.i(COMPANION_TAG, "route ${if (decision.toCompanion) "companion" else "agent"} (${decision.reason}): ${handOver.text.take(LOG_CHARS)}")
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        synchronized(decisions) {
            decisions.addLast("$stamp ${if (decision.toCompanion) "陪聊" else "小博"} · ${decision.reason} · 「${handOver.text.take(LOG_CHARS)}」")
            while (decisions.size > MAX_DECISIONS) decisions.removeFirst()
        }
    }

    private const val LOG_CHARS = 40
    private const val MAX_DECISIONS = 30
}

/** 检查 → 陪聊: off, or one short chat with the configured service and how long it took. */
internal suspend fun checkCompanion(context: Context): DebugCheckResult {
    val config = loadDebugCompanionConfig(context)
    if (!config.enabled) return DebugCheckResult(DebugCheckState.OFF, "没打开")
    // 端到端 has 豆包 chat itself and never hands a turn to 陪聊 (owner 2026-10-14: 「陪聊只在非端到端模式下用」).
    if (loadDebugRealtimeConfig(context).enabled) return DebugCheckResult(DebugCheckState.OFF, "端到端模式下不用陪聊：闲聊由豆包自己回答")
    val name = "${config.service.label} · ${config.endpoint().model} · ${config.mode.label.substringBefore("（")}"
    val started = SystemClock.elapsedRealtime()
    return try {
        val reply = DebugCompanion.ask(context, config, "你好")
        val elapsed = SystemClock.elapsedRealtime() - started
        if (reply.isBlank()) DebugCheckResult(DebugCheckState.WARN, "$name · 服务回了空内容")
        else DebugCheckResult(DebugCheckState.OK, "$name · 回复用了 ${elapsed} ms")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        DebugCheckResult(DebugCheckState.FAIL, "$name · ${error.message ?: error.javaClass.simpleName}")
    }
}
