package cc.noharry.bothy.sample

import cc.noharry.agent.transport.HttpEvent
import cc.noharry.agent.transport.HttpRequest
import cc.noharry.agent.transport.HttpResponse
import cc.noharry.agent.transport.HttpTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okio.ByteString.Companion.decodeBase64

internal interface DebugTtsClient {
    suspend fun synthesize(config: DebugTtsConfig, text: String): DebugTtsAudio

    fun stream(config: DebugTtsConfig, text: String): Flow<DebugTtsAudioChunk>
}

internal data class DebugTtsAudio(
    val bytes: ByteArray,
    val format: String,
)

internal data class DebugTtsAudioChunk(
    val bytes: ByteArray,
    val format: String,
)

internal class DebugMimoTtsClient(
    private val transport: HttpTransport,
) : DebugTtsClient {
    override suspend fun synthesize(config: DebugTtsConfig, text: String): DebugTtsAudio {
        DebugTtsLog.info(
            "synthesize request model=${config.model} voice=${config.voice} format=${config.format} " +
                "textChars=${text.length} textPreview=${text.ttsLogPreview()}",
        )
        val response = transport.execute(mimoTtsRequest(config, text, stream = false))
        DebugTtsLog.info("synthesize response status=${response.statusCode} bodyBytes=${response.body.size}")
        if (response.statusCode !in HTTP_SUCCESS_RANGE) {
            throw IllegalStateException("MiMo TTS HTTP ${response.statusCode}")
        }
        val audioBytes = parseMimoAudioData(response)
        DebugTtsLog.info("audio decoded bytes=${audioBytes.size} format=${config.format.trim().lowercase()}")
        return DebugTtsAudio(
            bytes = audioBytes,
            format = config.format.trim().lowercase(),
        )
    }

    override fun stream(config: DebugTtsConfig, text: String): Flow<DebugTtsAudioChunk> = flow {
        val format = config.format.trim().lowercase()
        require(format == PCM16_FORMAT) { "MiMo streaming TTS requires pcm16 format" }
        DebugTtsLog.info(
            "stream request model=${config.model} voice=${config.voice} format=$format " +
                "textChars=${text.length} textPreview=${text.ttsLogPreview()}",
        )
        val parser = MimoTtsSseAudioParser(format)
        var chunks = 0
        var bytes = 0
        transport.stream(mimoTtsRequest(config, text, stream = true)).collect { event ->
            when (event) {
                is HttpEvent.Response -> {
                    DebugTtsLog.info("stream response status=${event.statusCode}")
                    if (event.statusCode !in HTTP_SUCCESS_RANGE) {
                        throw IllegalStateException("MiMo TTS HTTP ${event.statusCode}")
                    }
                }
                is HttpEvent.Data -> {
                    parser.accept(event.bytes).forEach { chunk ->
                        chunks += 1
                        bytes += chunk.bytes.size
                        if (chunks == 1) {
                            DebugTtsLog.info("stream first audio chunk bytes=${chunk.bytes.size}")
                        }
                        emit(chunk)
                    }
                }
                HttpEvent.Done -> Unit
            }
        }
        parser.finish().forEach { chunk ->
            chunks += 1
            bytes += chunk.bytes.size
            emit(chunk)
        }
        DebugTtsLog.info("stream completed chunks=$chunks bytes=$bytes")
    }

    private fun mimoTtsRequest(config: DebugTtsConfig, text: String, stream: Boolean): HttpRequest =
        HttpRequest(
            method = "POST",
            url = chatCompletionsUrl(config.baseUrl),
            headers = mapOf(
                "api-key" to config.apiKey,
                "Content-Type" to "application/json",
            ),
            body = mimoTtsBody(config, text, stream).toString().toByteArray(Charsets.UTF_8),
        )

    private fun mimoTtsBody(config: DebugTtsConfig, text: String, stream: Boolean): JsonObject =
        buildJsonObject {
            put("model", config.model)
            if (stream) put("stream", true)
            putJsonArray("messages") {
                config.style.trim().takeIf { it.isNotBlank() }?.let { style ->
                    add(message("user", style))
                }
                add(message("assistant", text))
            }
            putJsonObject("audio") {
                put("format", config.format.trim().lowercase())
                config.voice.trim().takeIf { it.isNotBlank() }?.let { put("voice", it) }
                if (config.optimizeTextPreview) put("optimize_text_preview", true)
            }
        }

    private fun message(role: String, content: String): JsonObject =
        buildJsonObject {
            put("role", role)
            put("content", content)
        }

    private fun parseMimoAudioData(response: HttpResponse): ByteArray {
        val root = Json.parseToJsonElement(response.body.toString(Charsets.UTF_8)).jsonObject
        val data = root["choices"]
            ?.jsonArrayOrNull()
            ?.firstOrNull()
            ?.jsonObjectOrNull()
            ?.get("message")
            ?.jsonObjectOrNull()
            ?.get("audio")
            ?.jsonObjectOrNull()
            ?.get("data")
            ?.jsonPrimitive
            ?.contentOrNull
        return data?.decodeBase64()?.toByteArray()
            ?: throw IllegalStateException("MiMo TTS response missing audio data")
    }

    private fun chatCompletionsUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        return if (normalized.endsWith(CHAT_COMPLETIONS_PATH)) {
            normalized
        } else {
            normalized + CHAT_COMPLETIONS_PATH
        }
    }

    private fun Any?.jsonArrayOrNull(): JsonArray? = this as? JsonArray

    private fun Any?.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    companion object {
        private val HTTP_SUCCESS_RANGE = 200..299
        private const val CHAT_COMPLETIONS_PATH = "/chat/completions"
        private const val PCM16_FORMAT = "pcm16"
    }
}

private class MimoTtsSseAudioParser(
    private val format: String,
) {
    private var pending = ""

    fun accept(bytes: ByteArray): List<DebugTtsAudioChunk> {
        pending += bytes.toString(Charsets.UTF_8).replace("\r\n", "\n")
        return drain(complete = false)
    }

    fun finish(): List<DebugTtsAudioChunk> = drain(complete = true)

    private fun drain(complete: Boolean): List<DebugTtsAudioChunk> {
        val output = mutableListOf<DebugTtsAudioChunk>()
        var separator = pending.indexOf(SSE_FRAME_SEPARATOR)
        while (separator >= 0) {
            output += parseFrame(pending.substring(0, separator))
            pending = pending.substring(separator + SSE_FRAME_SEPARATOR.length)
            separator = pending.indexOf(SSE_FRAME_SEPARATOR)
        }
        if (complete && pending.isNotBlank()) {
            output += parseFrame(pending)
            pending = ""
        }
        return output
    }

    private fun parseFrame(frame: String): List<DebugTtsAudioChunk> =
        frame.lineSequence()
            .filter { it.startsWith(SSE_DATA_PREFIX) }
            .map { it.removePrefix(SSE_DATA_PREFIX).trim() }
            .filter { it.isNotBlank() && it != SSE_DONE_MARKER }
            .mapNotNull(::parsePayload)
            .toList()

    private fun parsePayload(payload: String): DebugTtsAudioChunk? {
        val root = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val data = root["choices"]
            ?.jsonArrayOrNull()
            ?.firstOrNull()
            ?.jsonObjectOrNull()
            ?.get("delta")
            ?.jsonObjectOrNull()
            ?.get("audio")
            ?.jsonObjectOrNull()
            ?.get("data")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: root["choices"]
                ?.jsonArrayOrNull()
                ?.firstOrNull()
                ?.jsonObjectOrNull()
                ?.get("message")
                ?.jsonObjectOrNull()
                ?.get("audio")
                ?.jsonObjectOrNull()
                ?.get("data")
                ?.jsonPrimitive
                ?.contentOrNull
        val bytes = data?.decodeBase64()?.toByteArray() ?: ByteArray(0)
        return bytes.takeIf { it.isNotEmpty() }?.let { DebugTtsAudioChunk(it, format) }
    }

    private fun Any?.jsonArrayOrNull(): JsonArray? = this as? JsonArray

    private fun Any?.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private companion object {
        const val SSE_FRAME_SEPARATOR = "\n\n"
        const val SSE_DATA_PREFIX = "data:"
        const val SSE_DONE_MARKER = "[DONE]"
    }
}

private fun String.ttsLogPreview(): String {
    val normalized = trim().replace(Regex("\\s+"), " ")
    return if (normalized.length <= TTS_LOG_PREVIEW_CHARS) {
        normalized
    } else {
        normalized.take(TTS_LOG_PREVIEW_CHARS).trimEnd() + "..."
    }
}

private const val TTS_LOG_PREVIEW_CHARS = 64
