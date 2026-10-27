package cc.noharry.bothy.app.voice.realtime
import android.content.Context
import cc.noharry.bothy.app.evidence.benchTrustedClient
import cc.noharry.bothy.app.memory.UserMemory
import cc.noharry.bothy.app.memory.memoryProfileText
import cc.noharry.bothy.app.model.ProviderConfig
import cc.noharry.bothy.app.usage.tokens
import cc.noharry.bothy.app.voice.tts.loadDoubaoTtsKey
import cc.noharry.bothy.app.voice.tts.ttsConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * 端到端演示模式 (owner 2026-10-13: 「做端到端演示模式」): 豆包 Seeduplex 3.0 hears, talks and decides when to hand a
 * task to 小博, who then runs as the executor. Off by default; the 豆包语音 key of 设置 → 语音 → 密钥 opens it.
 */
internal data class RealtimeConfig(
    val enabled: Boolean = false,
    val voice: String = DoubaoRealtimeVoice.VV.id,
    /** The typed persona, used when [personaPreset] is [CUSTOM_REALTIME_PERSONA]. */
    val persona: String = DEFAULT_REALTIME_PERSONA,
    /** Full duplex: the model hears while it talks and the speaker can cut in; needs echo cancelling the device may not have. */
    val bargeIn: Boolean = false,
    /** A [DoubaoRealtimePersona] id, or [CUSTOM_REALTIME_PERSONA]. */
    val personaPreset: String = DoubaoRealtimePersona.WARM.id,
    /**
     * 补播报: 小博 says a result no ask_xiaobo waits for (past the wait, a subagent's or plan's report) itself, once the
     * model is quiet ([RealtimeVoice.tellLate]). Off by default (owner 2026-10-21: 「能不能给一版不要补播报的，现在这个
     * 打乱了很多流程」): the model only gets it as a text item, and the screen shows it.
     */
    val lateSpeech: Boolean = false,
) {
    /** Who the model is told it is: the preset's text, read from the app so a revised preset applies, or the typed one. */
    val effectivePersona: String get() = DoubaoRealtimePersona.byId(personaPreset)?.text ?: persona
}

/** The voices the realtime model speaks with (its own jupiter set, not the TTS ones). */
internal enum class DoubaoRealtimeVoice(val id: String, val label: String) {
    VV("zh_female_vv_jupiter_bigtts", "Vivi · 女声"),
    XIAOHE("zh_female_xiaohe_jupiter_bigtts", "小何 · 女声"),
    YUNZHOU("zh_male_yunzhou_jupiter_bigtts", "云舟 · 男声"),
    XIAOTIAN("zh_male_xiaotian_jupiter_bigtts", "小天 · 男声"),
    ;

    companion object {
        fun labelOf(id: String): String = entries.firstOrNull { it.id == id }?.label ?: id
    }
}

internal fun loadRealtimeConfig(context: Context): RealtimeConfig {
    val prefs = context.getSharedPreferences(REALTIME_PREFS, Context.MODE_PRIVATE)
    val persona = prefs.getString(KEY_PERSONA, null)?.takeIf(String::isNotBlank) ?: DEFAULT_REALTIME_PERSONA
    val preset = prefs.getString(KEY_PERSONA_PRESET, null)
        ?.takeIf { it == CUSTOM_REALTIME_PERSONA || DoubaoRealtimePersona.byId(it) != null }
        // Saved before the presets: a persona the owner typed stays in use.
        ?: if (persona == DEFAULT_REALTIME_PERSONA) DoubaoRealtimePersona.WARM.id else CUSTOM_REALTIME_PERSONA
    return RealtimeConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        voice = prefs.getString(KEY_VOICE, null)?.takeIf(String::isNotBlank) ?: DoubaoRealtimeVoice.VV.id,
        persona = persona,
        bargeIn = prefs.getBoolean(KEY_BARGE_IN, false),
        personaPreset = preset,
        lateSpeech = prefs.getBoolean(KEY_LATE_SPEECH, false),
    )
}

internal fun saveRealtimeConfig(context: Context, config: RealtimeConfig) {
    context.getSharedPreferences(REALTIME_PREFS, Context.MODE_PRIVATE).edit()
        .putBoolean(KEY_ENABLED, config.enabled)
        .putString(KEY_VOICE, config.voice.trim())
        .putString(KEY_PERSONA, config.persona.trim())
        .putBoolean(KEY_BARGE_IN, config.bargeIn)
        .putString(KEY_PERSONA_PRESET, config.personaPreset)
        .putBoolean(KEY_LATE_SPEECH, config.lateSpeech)
        .apply()
}

/**
 * What the realtime model is told: the persona, then the fixed rule for handing tasks to 小博, then what was said in
 * this session before this conversation ([recent], [realtimeRecentText]). With 记忆 on ([remembers]), remembering is
 * 小博's task too, and [profile] says what it remembers ([memoryProfileText]).
 */
internal fun realtimeInstructions(
    persona: String,
    profile: String = "",
    remembers: Boolean = false,
    recent: List<Pair<String, String>> = emptyList(),
): String =
    listOf(
        persona.trim().ifEmpty { DEFAULT_REALTIME_PERSONA },
        REALTIME_TOOL_RULE + if (remembers) REALTIME_MEMORY_RULE else "",
        realtimeRecentText(recent),
        profile.takeIf { remembers }.orEmpty(),
    ).filter(String::isNotBlank).joinToString("\n")

/**
 * The session's latest exchanges, the user's words and the answer, clipped (owner 2026-10-22: 「上下文丢了吗」). Each
 * wake opens a new conversation with the service, which knew nothing of the trip planned a minute before: it asked
 * 「你是要规划从哪里出发」 instead of handing 「接到他先去福田香格里拉酒店放行李，再去吃饭」 to 小博.
 */
internal fun realtimeRecentText(recent: List<Pair<String, String>>): String =
    if (recent.isEmpty()) ""
    else REALTIME_RECENT_RULE + recent.joinToString("") { (words, answer) ->
        "\n用户：${clipRecent(withoutUserWords(words))}\n回答：${clipRecent(answer)}"
    }

private fun clipRecent(text: String): String =
    text.replace(Regex("\\s+"), " ").trim().let { if (it.length > RECENT_CHARS) it.take(RECENT_CHARS) + "…" else it }

/** One server event, as the session acts on it; unknown types are dropped. */
internal sealed interface DoubaoRealtimeEvent {
    data class SessionCreated(val id: String) : DoubaoRealtimeEvent
    data object HearingStarted : DoubaoRealtimeEvent
    data class Hearing(val delta: String) : DoubaoRealtimeEvent
    data class Heard(val text: String) : DoubaoRealtimeEvent
    data class ReplyText(val text: String) : DoubaoRealtimeEvent
    data object SpeechStarted : DoubaoRealtimeEvent

    /** Float32 little-endian, 24 kHz mono, as sent. */
    class Speech(val float32: ByteArray) : DoubaoRealtimeEvent
    data object SpeechDone : DoubaoRealtimeEvent
    data class ToolCalls(val calls: List<DoubaoToolCall>) : DoubaoRealtimeEvent
    data class ResponseDone(val inputTokens: Int, val outputTokens: Int) : DoubaoRealtimeEvent

    /** An `error` event: the session goes on. */
    data class ServerError(val message: String) : DoubaoRealtimeEvent

    /** The connection failed or never opened: the session ends. */
    data class Failure(val message: String) : DoubaoRealtimeEvent
    data object Closed : DoubaoRealtimeEvent
}

internal data class DoubaoToolCall(val callId: String, val name: String, val arguments: String) {
    /** ask_xiaobo's `request`, or the raw arguments when they are not the expected object. */
    fun request(): String = runCatching {
        (Json.parseToJsonElement(arguments).jsonObject["request"] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()?.takeIf(String::isNotBlank) ?: arguments
}

internal fun parseDoubaoRealtimeEvent(text: String): DoubaoRealtimeEvent? {
    val event = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    fun string(name: String) = (event[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
    return when (string("type")) {
        "session.created" -> DoubaoRealtimeEvent.SessionCreated(((event["session"] as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull.orEmpty())
        "conversation.item.input_audio_transcription.started" -> DoubaoRealtimeEvent.HearingStarted
        "conversation.item.input_audio_transcription.delta" -> DoubaoRealtimeEvent.Hearing(string("delta"))
        "conversation.item.input_audio_transcription.completed" -> DoubaoRealtimeEvent.Heard(string("text"))
        "response.output_text.done" -> DoubaoRealtimeEvent.ReplyText(string("text"))
        "response.output_audio.started" -> DoubaoRealtimeEvent.SpeechStarted
        "response.output_audio.delta" -> runCatching { Base64.getDecoder().decode(string("delta")) }.getOrNull()?.let(DoubaoRealtimeEvent::Speech)
        "response.output_audio.done" -> DoubaoRealtimeEvent.SpeechDone
        "response.function_call_arguments.done" -> DoubaoRealtimeEvent.ToolCalls(toolCalls(event["items"] as? JsonArray))
        "response.done" -> usage(event).let { (input, output) -> DoubaoRealtimeEvent.ResponseDone(input, output) }
        "session.closed" -> DoubaoRealtimeEvent.Closed
        "error" -> DoubaoRealtimeEvent.ServerError(errorMessage(event))
        else -> null
    }
}

private fun toolCalls(items: JsonArray?): List<DoubaoToolCall> = items.orEmpty().mapNotNull { item ->
    val call = item as? JsonObject ?: return@mapNotNull null
    fun field(name: String) = (call[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
    DoubaoToolCall(field("call_id"), field("name"), field("arguments")).takeIf { it.callId.isNotBlank() }
}

private fun usage(event: JsonObject): Pair<Int, Int> {
    val usage = ((event["response"] as? JsonObject)?.get("usage") as? JsonObject) ?: return 0 to 0
    fun tokens(name: String) = (usage[name] as? JsonPrimitive)?.intOrNull ?: 0
    return tokens("input_tokens") to tokens("output_tokens")
}

private fun errorMessage(event: JsonObject): String {
    val error = event["error"] as? JsonObject
    val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull ?: (event["message"] as? JsonPrimitive)?.contentOrNull
    return message ?: event.toString().take(MAX_ERROR_CHARS)
}

/**
 * `session.create`: the model, persona and rule (with what 小博 remembers, [memories], when 记忆 is on), the ask_xiaobo
 * tool, 16 kHz PCM16 in and 24 kHz PCM out in [config]'s voice.
 */
internal fun doubaoRealtimeSessionCreate(
    config: RealtimeConfig,
    memories: List<UserMemory>? = null,
    recent: List<Pair<String, String>> = emptyList(),
): String = buildJsonObject {
    put("type", "session.create")
    putJsonObject("session") {
        put("model", DOUBAO_REALTIME_MODEL)
        put("instructions", realtimeInstructions(config.effectivePersona, memoryProfileText(memories.orEmpty()), remembers = memories != null, recent))
        putJsonArray("tools") {
            addJsonObject {
                put("type", "function")
                put("name", END_CONVERSATION)
                put("description", END_CONVERSATION_DESCRIPTION)
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {}
                }
            }
            addJsonObject {
                put("type", "function")
                put("name", ASK_XIAOBO)
                put("description", ASK_XIAOBO_DESCRIPTION)
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("request") {
                            put("type", "string")
                            put("description", "用户要办的事，尽量用用户的原话")
                        }
                    }
                    putJsonArray("required") { add("request") }
                }
            }
        }
        putJsonObject("audio") {
            putJsonObject("input") { putJsonObject("format") { put("type", "pcm"); put("sample_rate", REALTIME_INPUT_RATE) } }
            putJsonObject("output") {
                putJsonObject("format") { put("type", "pcm"); put("sample_rate", REALTIME_OUTPUT_RATE) }
                put("voice", config.voice)
            }
        }
    }
}.toString()

/** One slice of 16 kHz PCM16 from the microphone. */
internal fun doubaoRealtimeAudio(pcm16: ByteArray): String = buildJsonObject {
    put("type", "input_audio_buffer.append")
    put("audio", Base64.getEncoder().encodeToString(pcm16))
}.toString()

/** A tool's result, which the model then speaks about. */
internal fun doubaoRealtimeToolResult(callId: String, text: String): String = buildJsonObject {
    put("type", "conversation.item.create")
    putJsonArray("items") {
        addJsonObject {
            put("call_id", callId)
            put("role", "tool")
            putJsonArray("content") { addJsonObject { put("type", "input_text"); put("text", text) } }
        }
    }
}.toString()

/**
 * Something 小博 finished and told the user itself with no ask_xiaobo waiting (a reply past the wait, a subagent's
 * report, a plan), for the model to know when the user asks on: a user item marked as not said by the user.
 */
internal fun doubaoRealtimeNotice(text: String): String = buildJsonObject {
    put("type", "conversation.item.create")
    putJsonArray("items") {
        addJsonObject {
            put("role", "user")
            putJsonArray("content") { addJsonObject { put("type", "input_text"); put("text", "$LATE_RESULT_LEAD$text") } }
        }
    }
}.toString()

internal const val DOUBAO_REALTIME_CLOSE = """{"type":"session.close"}"""
private const val LATE_RESULT_LEAD = "（这句不是用户说的：小博刚办完之前交给它的事，已经亲口告诉了用户，结果在下面。用户接着问时参考，不用复述，不用调用工具。）\n"

/**
 * Float32 samples to PCM16, both little-endian, times [gain] (clipped at full scale); a sample split between chunks is
 * carried to the next.
 */
internal class Float32ToPcm16(private val gain: Float = 1f) {
    private var carry = ByteArray(0)

    fun convert(chunk: ByteArray): ByteArray {
        val bytes = if (carry.isEmpty()) chunk else carry + chunk
        val samples = bytes.size / FLOAT_BYTES
        carry = bytes.copyOfRange(samples * FLOAT_BYTES, bytes.size)
        val floats = ByteBuffer.wrap(bytes, 0, samples * FLOAT_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val value = floats.getFloat(i * FLOAT_BYTES).let { if (it.isNaN()) 0f else (it * gain).coerceIn(-1f, 1f) }
            val sample = (value * Short.MAX_VALUE).roundToInt()
            out[2 * i] = sample.toByte()
            out[2 * i + 1] = (sample shr 8).toByte()
        }
        return out
    }
}

/** The realtime WebSocket: [onEvent] gets every event it understands, on OkHttp's thread, then [DoubaoRealtimeEvent.Closed] or a failure. */
internal class DoubaoRealtimeConnection(
    private val client: OkHttpClient,
    private val key: String,
    private val onEvent: (DoubaoRealtimeEvent) -> Unit,
    private val url: String = DOUBAO_REALTIME_URL,
) {
    @Volatile private var socket: WebSocket? = null

    fun open() {
        val request = Request.Builder().url(url)
            .header("X-Api-Key", key)
            .header("X-Api-Connect-Id", UUID.randomUUID().toString())
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                parseDoubaoRealtimeEvent(text)?.let(onEvent)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onEvent(DoubaoRealtimeEvent.Closed)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                onEvent(DoubaoRealtimeEvent.Failure(realtimeFailure(t, response)))
        })
    }

    /** False when the socket is gone or its queue is full. */
    fun send(json: String): Boolean = socket?.send(json) == true

    fun close() {
        val active = socket ?: return
        socket = null
        active.send(DOUBAO_REALTIME_CLOSE)
        active.close(NORMAL_CLOSURE, null)
    }
}

/** Opens a session and closes it again: how long `session.created` took in milliseconds, or why it did not come. */
internal suspend fun probeDoubaoRealtime(
    client: OkHttpClient,
    key: String,
    config: RealtimeConfig,
    url: String = DOUBAO_REALTIME_URL,
): Result<Long> {
    val outcome = CompletableDeferred<Result<Long>>()
    val started = System.nanoTime()
    fun fail(message: String) = outcome.complete(Result.failure(IllegalStateException(message)))
    val connection = DoubaoRealtimeConnection(client, key, { event ->
        when (event) {
            is DoubaoRealtimeEvent.SessionCreated -> outcome.complete(Result.success((System.nanoTime() - started) / NANOS_PER_MILLI))
            is DoubaoRealtimeEvent.Failure -> fail(event.message)
            is DoubaoRealtimeEvent.ServerError -> fail("豆包端到端报错：${event.message}")
            DoubaoRealtimeEvent.Closed -> fail("豆包端到端没有建立会话就关闭了连接")
            else -> Unit
        }
    }, url)
    connection.open()
    connection.send(doubaoRealtimeSessionCreate(config))
    return try {
        withTimeoutOrNull(PROBE_MILLIS) { outcome.await() } ?: Result.failure(IllegalStateException("${PROBE_MILLIS / 1000} 秒内没有建立会话"))
    } finally {
        connection.close()
    }
}

/**
 * The WebSocket client for the realtime API, made once: the bench's CA roots, no read timeout (the line is quiet
 * between turns), pings to keep it open.
 */
internal fun doubaoRealtimeClient(context: Context): OkHttpClient = realtimeClient ?: synchronized(RealtimeConfig::class) {
    realtimeClient ?: benchTrustedClient(context)
        .connectTimeout(REALTIME_CONNECT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(REALTIME_PING_SECONDS, TimeUnit.SECONDS)
        .build()
        .also { realtimeClient = it }
}

@Volatile private var realtimeClient: OkHttpClient? = null

/** The 豆包语音 key 端到端 opens with: the one saved in 设置 → 语音 → 密钥, else the one the build carries. */
internal fun realtimeKey(context: Context): String = loadDoubaoTtsKey(context).ifEmpty {
    runCatching { ProviderConfig.load(context).ttsConfig().doubao.key }.getOrDefault("")
}

/** Why the connection failed, in the words 设置 and the voice bar show: a refused handshake names what to open in the console. */
internal fun realtimeFailure(error: Throwable, response: Response?): String {
    val code = response?.code ?: return "连不上豆包端到端：${error.message ?: error.javaClass.simpleName}"
    val body = runCatching { response.body?.string() }.getOrNull().orEmpty().take(MAX_ERROR_CHARS)
    return when (code) {
        401, 403 -> "豆包端到端拒绝了这个 key（HTTP $code）：确认控制台已开通端到端实时语音。$body".trim()
        else -> "豆包端到端连接失败（HTTP $code）$body".trim()
    }
}

internal const val ASK_XIAOBO = "ask_xiaobo"
/** The speaker wants the conversation over, in whatever words (owner 2026-10-14: 「端到端好像漏了语音说退出语音的功能」). */
internal const val END_CONVERSATION = "end_conversation"
/**
 * +6 dB on the realtime voice. Its speech averages -21.3 dBFS with peaks held 8–11 dB under full scale (Vivi, 小何 and
 * 云舟 measured 2026-10-13), 1–3 dB under 豆包 TTS; doubled it sits a little above TTS and still does not clip.
 */
internal const val REALTIME_OUTPUT_GAIN = 2f
internal const val REALTIME_INPUT_RATE = 16_000
internal const val REALTIME_OUTPUT_RATE = 24_000
internal const val DEFAULT_REALTIME_PERSONA = "你是小博，一个语音助手，温暖、机灵，说话口语化、简短，一次一两句。"
private const val ASK_XIAOBO_DESCRIPTION =
    "用户要办事（规划行程和出游、查机票和天气、设提醒、调音量亮度、放音乐、发消息、查资料、操作屏幕上的应用等）时调用，" +
        "把要办的事交给助手小博去执行，返回执行结果。"
private const val END_CONVERSATION_DESCRIPTION =
    "用户要结束这次对话时调用：退出语音、关闭语音、让你退下、说不用了没事了、再见等。调用后对话立即结束，不用再回复。" +
        "用户只是停顿、应答「好」「嗯」，或只是让某个应用、页面退出时，不要调用。"
private const val REALTIME_TOOL_RULE =
    // Straight to the call, no words first (owner 2026-10-22: 「提前主动播的那句去掉，这样可以等120秒也能续播」): after words of
    // its own the service's voice gives up on the result near 30 s, so the call is answered within SPOKEN_CALL_REPLY_MILLIS
    // with 「还在办」; without them it waits up to AGENT_REPLY_MILLIS and the model says the result itself.
    "用户要办事时：不要先说话（不要说「好，我来办」「我帮你规划」这类话），直接调用 ask_xiaobo，把要办的事交给它；拿到结果后用一两句话" +
        "告诉用户，不要念长列表、网址和编号。结果是小博的提问时，把问题转述给用户。闲聊、陪伴、常识问答直接回答，不调用工具。" +
        "不要编造办事的结果。周围的歌声和视频声、别人之间的聊天、听不清的半句话，都不要回应。用户要结束对话、退出语音时调用 end_conversation。" +
        "设提醒、定时要办的事、机票价格或天气到了某个条件时提醒，也交给 ask_xiaobo，不要只口头答应。" +
        "你自己调不了任何设置：用户嫌声音小或大、冷或热，或调完说「不行」「还是太小」，都调用 ask_xiaobo（如「语音音量调大」），不要口头说调好了。" +
        // 2026-10-20 recording: 「去桂林玩三天，帮我规划一下」 was answered from the model's own knowledge.
        "规划行程、出游、几天去哪怎么去住哪，以及天气、营业时间、班次这类要实时查的事，也都交给 ask_xiaobo，不要自己编一份。" +
        // 2026-10-20: 「下周六」 went to 小博 as 「2026年10月27日」, a Tuesday.
        "交给 ask_xiaobo 时，日期和时间照用户原话说（「下周六」「明早九点」），不要自己换算成几月几号；" +
        "用户没说的细节不要自己补，用户没说「再」「继续」也不要加。"
private const val REALTIME_RECENT_RULE =
    "这次唤醒前，用户和你在这段对话里刚说过下面这些，现在接着聊：用户说的「他」「那家」「刚才那条路线」指的就是这里的人、地方和行程；" +
        "改行程、加一站、换地方，照样交给 ask_xiaobo。"
/** Exchanges told to a new conversation, and how much of each side. */
internal const val REALTIME_CONTEXT_TURNS = 4
private const val RECENT_CHARS = 160
private const val REALTIME_MEMORY_RULE = "用户让你记住、修改或忘掉关于他自己的事（比如「记住我家在…」「叫我老王」「忘掉我公司地址」），也交给 ask_xiaobo，由小博记下。"
internal const val DOUBAO_REALTIME_URL = "wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue"
private const val DOUBAO_REALTIME_MODEL = "1.2.6.1"
private const val REALTIME_PREFS = "debug_realtime_voice"
private const val KEY_ENABLED = "enabled"
private const val KEY_VOICE = "voice"
private const val KEY_PERSONA = "persona"
private const val KEY_PERSONA_PRESET = "persona_preset"
private const val KEY_BARGE_IN = "barge_in"
private const val KEY_LATE_SPEECH = "late_speech"
private const val FLOAT_BYTES = 4
private const val NORMAL_CLOSURE = 1000
private const val MAX_ERROR_CHARS = 200
private const val REALTIME_CONNECT_SECONDS = 15L
private const val REALTIME_PING_SECONDS = 15L
private const val PROBE_MILLIS = 8_000L
private const val NANOS_PER_MILLI = 1_000_000L
