package cc.noharry.bothy.sample

import android.content.Context
import cc.noharry.agent.transport.HttpEvent
import cc.noharry.agent.transport.HttpRequest
import cc.noharry.agent.transport.HttpTransport
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Who speaks the replies (owner 2026-10-13: 「TTS播报的部分也要能支持其他家」, then 「先加上豆包的tts支持」). */
internal enum class DebugTtsProvider(val wireName: String, val label: String) {
    MIMO("mimo", "MiMo"),
    DOUBAO("doubao", "豆包"),
    ;

    companion object {
        fun from(value: String?): DebugTtsProvider = entries.firstOrNull { it.wireName == value?.trim()?.lowercase() } ?: MIMO
    }
}

/**
 * 豆包语音合成 (火山引擎, V3 one-way streaming). [key] is the console's API key, or with [appId] set the Access Token of
 * the older App ID + Access Token pair. [resourceId] picks the model: a 2.0 voice (`*_uranus_bigtts`) needs
 * `seed-tts-2.0`, a 1.0 voice `seed-tts-1.0`. [speechRate] runs from -50 (half speed) to 100 (double).
 */
internal data class DoubaoTtsSettings(
    val appId: String = "",
    val key: String = "",
    val resourceId: String = DEFAULT_DOUBAO_TTS_RESOURCE_ID,
    val voice: String = DoubaoTtsVoice.XIAOHE.id,
    val speechRate: Int = 0,
) {
    val ready: Boolean get() = key.isNotBlank() && voice.isNotBlank() && resourceId.isNotBlank()

    val voiceLabel: String get() = DoubaoTtsVoice.entries.firstOrNull { it.id == voice }?.label ?: voice

    fun validationError(): String? = when {
        resourceId.isBlank() || resourceId.any(Char::isWhitespace) -> "请填写豆包资源 ID（如 seed-tts-2.0）。"
        speechRate !in -50..100 -> "语速要在 -50 到 100 之间。"
        appId.any(Char::isWhitespace) -> "App ID 不能包含空白字符。"
        else -> null
    }

    companion object {
        fun from(values: Map<String, String>) = DoubaoTtsSettings(
            appId = values[DOUBAO_TTS_APP_ID].orEmpty().trim(),
            key = values[DOUBAO_TTS_API_KEY].orEmpty().trim(),
            resourceId = values[DOUBAO_TTS_RESOURCE_ID].orDefault(DEFAULT_DOUBAO_TTS_RESOURCE_ID).trim(),
            voice = values[DOUBAO_TTS_VOICE].orDefault(DoubaoTtsVoice.XIAOHE.id).trim(),
            speechRate = values[DOUBAO_TTS_SPEECH_RATE]?.trim()?.toIntOrNull() ?: 0,
        )
    }
}

/** Some 豆包语音合成 2.0 voices (`seed-tts-2.0`); any other voice id can be typed in. */
internal enum class DoubaoTtsVoice(val id: String, val label: String) {
    XIAOHE("zh_female_xiaohe_uranus_bigtts", "小何 · 通用女声"),
    VIVI("zh_female_vv_uranus_bigtts", "Vivi · 多语种女声"),
    MEILINVYOU("zh_female_meilinvyou_uranus_bigtts", "魅力女友"),
    SAJIAOXUEMEI("zh_female_sajiaoxuemei_uranus_bigtts", "撒娇学妹"),
    QINGXIN("zh_female_qingxinnvsheng_uranus_bigtts", "清新女声"),
    TIANMEIXIAOYUAN("zh_female_tianmeixiaoyuan_uranus_bigtts", "甜美小源"),
    CANCAN("zh_female_cancan_uranus_bigtts", "知性灿灿"),
    YUNZHOU("zh_male_m191_uranus_bigtts", "云舟 · 通用男声"),
    XIAOTIAN("zh_male_taocheng_uranus_bigtts", "小天 · 男声"),
    RUYAYICHEN("zh_male_ruyayichen_uranus_bigtts", "儒雅逸辰 · 男声"),
}

internal fun loadDebugDoubaoTtsKey(context: Context): String =
    runCatching { File(context.noBackupFilesDir, DOUBAO_TTS_KEY_FILE).readText(Charsets.UTF_8).trim() }.getOrDefault("")

/** Settings → 语音 → 密钥 → 豆包语音: null when saved, else why not. */
internal fun saveDebugDoubaoTtsKey(context: Context, key: String): String? {
    val trimmed = key.trim()
    if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return "API key 不能为空或包含空白字符。"
    return try {
        context.noBackupFilesDir.mkdirs()
        File(context.noBackupFilesDir, DOUBAO_TTS_KEY_FILE).writeText(trimmed, Charsets.UTF_8)
        null
    } catch (_: IOException) {
        "无法保存豆包语音 key。"
    }
}

internal fun clearDebugDoubaoTtsKey(context: Context): Boolean =
    File(context.noBackupFilesDir, DOUBAO_TTS_KEY_FILE).let { !it.exists() || it.delete() }

/** The TTS client every speaking path uses: the configured provider's. */
internal fun debugTtsClient(transport: HttpTransport): DebugTtsClient = DebugRoutingTtsClient(DebugMimoTtsClient(transport), DebugDoubaoTtsClient(transport))

private class DebugRoutingTtsClient(private val mimo: DebugTtsClient, private val doubao: DebugTtsClient) : DebugTtsClient {
    private fun clientFor(config: DebugTtsConfig) = if (config.provider == DebugTtsProvider.DOUBAO) doubao else mimo

    // Every spoken line passes here, so dates are read as dates whoever wrote them (reply, progress, report, reminder).
    override suspend fun synthesize(config: DebugTtsConfig, text: String): DebugTtsAudio = clientFor(config).synthesize(config, text.spokenDates())

    override fun stream(config: DebugTtsConfig, text: String): Flow<DebugTtsAudioChunk> = clientFor(config).stream(config, text.spokenDates())
}

/**
 * 豆包语音合成 over the V3 one-way streaming HTTP API: one POST, the reply a run of JSON frames, each `code` 0 with a
 * slice of base64 PCM in `data`, and `code` 20000000 at the end; any other code is an error with a `message`. Audio
 * is asked as 24 kHz 16-bit PCM, what the player plays as it arrives. A whole clip (the wake answer) wraps it as WAV.
 */
internal class DebugDoubaoTtsClient(private val transport: HttpTransport) : DebugTtsClient {
    override suspend fun synthesize(config: DebugTtsConfig, text: String): DebugTtsAudio {
        val pcm = ByteArrayOutputStream()
        stream(config, text).toList().forEach { pcm.write(it.bytes) }
        val bytes = pcm.toByteArray()
        return if (config.format.trim().lowercase() == WAV) DebugTtsAudio(pcm16Wav(bytes, DOUBAO_SAMPLE_RATE), WAV) else DebugTtsAudio(bytes, PCM16)
    }

    override fun stream(config: DebugTtsConfig, text: String): Flow<DebugTtsAudioChunk> = flow {
        val settings = config.doubao
        DebugTtsLog.info("doubao stream request resource=${settings.resourceId} voice=${settings.voice} textChars=${text.length}")
        val frames = JsonFrames()
        var status = 0
        var finished = false
        transport.stream(doubaoRequest(settings, text)).collect { event ->
            when (event) {
                is HttpEvent.Response -> status = event.statusCode
                is HttpEvent.Data -> frames.feed(event.bytes).forEach { frame ->
                    val code = frame.code()
                    when {
                        code == 0 || code == null && status in 200..299 -> frame.audio()?.let { emit(DebugTtsAudioChunk(it, PCM16)) }
                        code == DOUBAO_FINISHED -> finished = true
                        else -> throw IllegalStateException(doubaoError(code, frame.message(), status))
                    }
                }
                HttpEvent.Done -> Unit
            }
        }
        if (status !in 200..299) throw IllegalStateException(doubaoError(null, frames.rest(), status))
        DebugTtsLog.info("doubao stream completed finished=$finished")
    }

    private fun doubaoRequest(settings: DoubaoTtsSettings, text: String): HttpRequest {
        val auth = if (settings.appId.isNotBlank()) {
            mapOf("X-Api-App-Id" to settings.appId, "X-Api-Access-Key" to settings.key)
        } else {
            // The console's API key; the fixed app key goes with it (as volcengine-tts-provider sends it).
            mapOf("X-Api-Key" to settings.key, "X-Api-App-Key" to DOUBAO_API_KEY_APP_KEY)
        }
        val body = buildJsonObject {
            putJsonObject("user") { put("uid", DOUBAO_USER) }
            putJsonObject("req_params") {
                put("text", text)
                put("speaker", settings.voice)
                putJsonObject("audio_params") {
                    put("format", "pcm")
                    put("sample_rate", DOUBAO_SAMPLE_RATE)
                    if (settings.speechRate != 0) put("speech_rate", settings.speechRate)
                }
            }
        }
        return HttpRequest(
            method = "POST",
            url = DOUBAO_TTS_URL,
            headers = auth + mapOf(
                "X-Api-Resource-Id" to settings.resourceId,
                "X-Api-Request-Id" to UUID.randomUUID().toString(),
                "Content-Type" to "application/json",
            ),
            body = body.toString().toByteArray(Charsets.UTF_8),
        )
    }
}

/** Why 豆包 refused, with the fix for the codes that need one. */
internal fun doubaoError(code: Int?, message: String?, status: Int): String {
    val hint = when (code) {
        40300001 -> "（鉴权失败：检查 API key，用 App ID 时检查 Access Token）"
        40000001 -> "（参数错误：常见是音色和资源 ID 不配，2.0 音色要用 seed-tts-2.0）"
        55000000 -> "（资源 ID 和音色不配：2.0 音色用 seed-tts-2.0，1.0 音色用 seed-tts-1.0，复刻音色 S_ 开头用 seed-icl-2.0）"
        40402003 -> "（文本太长）"
        else -> ""
    }
    return "豆包语音合成失败：${code ?: "HTTP $status"} ${message.orEmpty().take(MAX_ERROR_CHARS)}$hint".trim()
}

/**
 * Splits the reply into JSON objects whatever separates them (newlines, `data:` lines, nothing). Braces and quotes
 * are single bytes in UTF-8 and never part of a longer character, so the scan runs on bytes and a frame is decoded
 * only once it is whole.
 */
private class JsonFrames {
    private val buffer = ByteArrayOutputStream()
    private var start = -1
    private var depth = 0
    private var inString = false
    private var escaped = false

    fun feed(bytes: ByteArray): List<JsonObject> {
        val frames = mutableListOf<JsonObject>()
        bytes.forEach { byte ->
            if (start < 0 && byte != OPEN) return@forEach
            if (start < 0) start = buffer.size()
            buffer.write(byte.toInt())
            when {
                escaped -> escaped = false
                inString && byte == BACKSLASH -> escaped = true
                byte == QUOTE -> inString = !inString
                !inString && byte == OPEN -> depth++
                !inString && byte == CLOSE -> if (--depth == 0) {
                    parse(buffer.toByteArray().copyOfRange(start, buffer.size()))?.let(frames::add)
                    buffer.reset()
                    start = -1
                }
            }
        }
        return frames
    }

    /** Whatever was not a whole frame: an error body that is not JSON. */
    fun rest(): String = buffer.toString(Charsets.UTF_8.name())

    private fun parse(bytes: ByteArray): JsonObject? = try {
        Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    private companion object {
        val OPEN = '{'.code.toByte()
        val CLOSE = '}'.code.toByte()
        val QUOTE = '"'.code.toByte()
        val BACKSLASH = '\\'.code.toByte()
    }
}

/** `code` at the top or under `header`, as different 豆包 replies carry it. */
private fun JsonObject.code(): Int? =
    ((this["code"] ?: (this["header"] as? JsonObject)?.get("code")) as? JsonPrimitive)?.intOrNull

private fun JsonObject.message(): String? =
    ((this["message"] ?: (this["header"] as? JsonObject)?.get("message")) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.audio(): ByteArray? =
    (this["data"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { Base64.getDecoder().decode(it) }

/** 16-bit mono PCM at [sampleRate] as a WAV file. */
internal fun pcm16Wav(pcm: ByteArray, sampleRate: Int): ByteArray {
    val header = java.nio.ByteBuffer.allocate(WAV_HEADER_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
    header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
    header.put("data".toByteArray()).putInt(pcm.size)
    return header.array() + pcm
}

/**
 * Every value 播报 reads, MiMo's and 豆包's: what a saved 播报 setting refreshes in the running session. Refreshing
 * only MiMo's left a switch to 豆包 unheard until the app restarted (the 试听 still spoke MiMo's 冰糖).
 */
internal val TTS_CONFIG_KEYS: Set<String>
    get() = MIMO_TTS_CONFIG_KEYS + setOf(TTS_PROVIDER, DOUBAO_TTS_APP_ID, DOUBAO_TTS_API_KEY, DOUBAO_TTS_RESOURCE_ID, DOUBAO_TTS_VOICE, DOUBAO_TTS_SPEECH_RATE)

internal const val TTS_PROVIDER = "TTS_PROVIDER"
internal const val DOUBAO_TTS_APP_ID = "DOUBAO_TTS_APP_ID"
internal const val DOUBAO_TTS_API_KEY = "DOUBAO_TTS_API_KEY"
internal const val DOUBAO_TTS_RESOURCE_ID = "DOUBAO_TTS_RESOURCE_ID"
internal const val DOUBAO_TTS_VOICE = "DOUBAO_TTS_VOICE"
internal const val DOUBAO_TTS_SPEECH_RATE = "DOUBAO_TTS_SPEECH_RATE"
internal const val DEFAULT_DOUBAO_TTS_RESOURCE_ID = "seed-tts-2.0"
internal const val DOUBAO_TTS_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
private const val DOUBAO_API_KEY_APP_KEY = "aGjiRDfUWi"
private const val DOUBAO_USER = "agent-runtime-sample"
private const val DOUBAO_TTS_KEY_FILE = "doubao-tts-api-key"
private const val DOUBAO_SAMPLE_RATE = 24_000
private const val DOUBAO_FINISHED = 20_000_000
private const val PCM16 = "pcm16"
private const val WAV = "wav"
private const val WAV_HEADER_BYTES = 44
private const val MAX_ERROR_CHARS = 160
