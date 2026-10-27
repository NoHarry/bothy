package cc.noharry.bothy.app.voice
import android.content.Context
import android.os.Handler
import android.os.Looper
import cc.noharry.bothy.app.a2a.A2aClientConsole
import cc.noharry.bothy.app.a2a.A2aInputSource
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.runtime.RuntimeSession
import cc.noharry.bothy.app.session.BACKEND_LIVE
import cc.noharry.bothy.app.session.LEGACY_BACKEND_LIVE_ALIAS
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class AsrTextRequest(
    val text: String,
    val backend: String? = null,
    val stream: String? = null,
    val validation: String? = null,
    val profile: String? = null,
)

internal class AsrTextServer(
    private val host: String = DEBUG_ASR_HOST,
    private val port: Int = DEBUG_ASR_PORT,
    private val submit: (AsrTextRequest) -> Unit,
) {
    private val started = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var worker: Thread? = null

    val localPort: Int get() = serverSocket?.localPort ?: port

    fun start(): Int {
        if (!started.compareAndSet(false, true)) return localPort
        val socket = ServerSocket().also {
            it.reuseAddress = true
            it.bind(InetSocketAddress(InetAddress.getByName(host), port))
        }
        serverSocket = socket
        worker = Thread({ acceptLoop(socket) }, DEBUG_ASR_THREAD_NAME).also {
            it.isDaemon = true
            it.start()
        }
        return socket.localPort
    }

    fun stop() {
        started.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        worker = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (started.get() && !socket.isClosed) {
            runCatching { socket.accept().use(::handleClient) }
        }
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = DEBUG_ASR_SOCKET_TIMEOUT_MS
        val request = parseAsrHttpRequest(client.getInputStream())
        val response = when {
            request == null -> AsrHttpResponse(400, "bad_request")
            request.text.isBlank() -> AsrHttpResponse(400, "missing_text")
            else -> {
                submit(request)
                AsrHttpResponse(202, "accepted")
            }
        }
        writeAsrHttpResponse(client.getOutputStream(), response)
    }
}

internal object AsrTextServerManager {
    @Volatile private var server: AsrTextServer? = null

    fun start(context: Context) {
        if (server != null) return
        val appContext = context.applicationContext
        val mainHandler = Handler(Looper.getMainLooper())
        val candidate = AsrTextServer { request ->
            mainHandler.post {
                request.profile?.takeIf { it.isNotBlank() }?.let { RuntimeSession.selectProfile(appContext, it) }
                if (request.validation.equals(DEBUG_ASR_VALIDATION_HOSTED_SEARCH, ignoreCase = true)) {
                    RuntimeSession.validateHostedSearch(appContext, request.text, request.stream)
                } else {
                    submitSpokenText(appContext, request.text, request.backend, request.stream)
                }
            }
        }
        runCatching { candidate.start() }
            .onSuccess { server = candidate }
            .onFailure { candidate.stop() }
    }
}

/**
 * Spoken input from any recognizer (the ASR port, the voice session): it replaces a running local turn, or goes over
 * A2A in client mode, where backend/stream overrides do not apply. Main thread.
 */
internal fun submitSpokenText(context: Context, text: String, backend: String? = null, stream: String? = null) {
    if (A2aClientConsole.isEnabled(context)) {
        A2aClientConsole.replace(context, text, A2aInputSource.ASR)
    } else {
        RuntimeSession.submitTextReplacingRun(context, text, backend ?: BACKEND_LIVE, stream)
    }
}

internal fun parseAsrHttpRequest(input: InputStream): AsrTextRequest? {
    val raw = readRawHttpRequest(input) ?: return null
    if (!raw.method.equals("POST", ignoreCase = true)) return null
    val path = raw.path.substringBefore("?")
    if (path != DEBUG_ASR_PATH) return null
    val query = raw.path.substringAfter("?", missingDelimiterValue = "").parseAsrQuery()
    val body = raw.body.toString(Charsets.UTF_8).trim()
    return if (raw.isJson() || body.firstOrNull() == DEBUG_ASR_JSON_OBJECT_START) {
        parseAsrJsonBody(body, query)
    } else {
        AsrTextRequest(body, externalBackend(query["backend"]), query["stream"])
    }
}

private fun parseAsrJsonBody(body: String, query: Map<String, String>): AsrTextRequest? {
    val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    val text = json.stringValue("text") ?: json.stringValue("utterance") ?: return null
    return AsrTextRequest(
        text = text.trim(),
        backend = externalBackend(json.stringValue("backend") ?: query["backend"]),
        stream = json.stringValue("stream") ?: query["stream"],
        validation = json.stringValue("validation") ?: query["validation"],
        profile = json.stringValue("profile") ?: json.stringValue("profileId") ?: query["profile"] ?: query["profileId"],
    )
}

private fun externalBackend(value: String?): String? =
    when (value?.trim()?.lowercase()) {
        BACKEND_LIVE, LEGACY_BACKEND_LIVE_ALIAS -> BACKEND_LIVE
        else -> null
    }

private fun readRawHttpRequest(input: InputStream): RawAsrHttpRequest? {
    val header = readHttpHeader(input) ?: return null
    val lines = header.toString(Charsets.UTF_8).trimEnd().lines()
    val requestParts = lines.firstOrNull()?.split(" ", limit = 3) ?: return null
    if (requestParts.size < 2) return null
    val headers = lines.drop(1).mapNotNull(::parseHeaderLine).toMap()
    val length = headers["content-length"]?.toIntOrNull() ?: 0
    if (length < 0 || length > DEBUG_ASR_MAX_BODY_BYTES) return null
    return RawAsrHttpRequest(
        method = requestParts[0],
        path = requestParts[1],
        headers = headers,
        body = readExactBytes(input, length),
    )
}

private fun readHttpHeader(input: InputStream): ByteArray? {
    val bytes = ByteArrayOutputStream()
    var match = 0
    while (bytes.size() <= DEBUG_ASR_MAX_HEADER_BYTES) {
        val next = input.read()
        if (next < 0) return null
        bytes.write(next)
        match = if (next == HTTP_HEADER_END[match].toInt()) match + 1 else 0
        if (match == HTTP_HEADER_END.size) return bytes.toByteArray()
    }
    return null
}

private fun readExactBytes(input: InputStream, length: Int): ByteArray {
    val out = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = input.read(out, offset, length - offset)
        if (read < 0) break
        offset += read
    }
    return if (offset == length) out else out.copyOf(offset)
}

private fun parseHeaderLine(line: String): Pair<String, String>? {
    val split = line.indexOf(":")
    if (split <= 0) return null
    return line.substring(0, split).trim().lowercase() to line.substring(split + 1).trim()
}

private fun String.parseAsrQuery(): Map<String, String> {
    if (isBlank()) return emptyMap()
    return split("&").mapNotNull { part ->
        val split = part.indexOf("=")
        if (split <= 0) return@mapNotNull null
        part.substring(0, split).urlDecode() to part.substring(split + 1).urlDecode()
    }.toMap()
}

private fun String.urlDecode(): String =
    URLDecoder.decode(this, StandardCharsets.UTF_8.name())

private fun JsonObject.stringValue(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun RawAsrHttpRequest.isJson(): Boolean =
    headers["content-type"]?.contains("application/json", ignoreCase = true) == true

private fun writeAsrHttpResponse(output: OutputStream, response: AsrHttpResponse) {
    val body = """{"status":"${response.status}"}"""
    val statusLine = when (response.code) {
        202 -> "HTTP/1.1 202 Accepted"
        else -> "HTTP/1.1 ${response.code} Bad Request"
    }
    output.write(
        """
        |$statusLine
        |Content-Type: application/json; charset=utf-8
        |Content-Length: ${body.toByteArray(Charsets.UTF_8).size}
        |Connection: close
        |
        |$body
        """.trimMargin().replace("\n", "\r\n").toByteArray(Charsets.UTF_8),
    )
    output.flush()
}

private data class RawAsrHttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

private data class AsrHttpResponse(
    val code: Int,
    val status: String,
)

internal const val DEBUG_ASR_HOST = "127.0.0.1"
internal const val DEBUG_ASR_PORT = 8765
internal const val DEBUG_ASR_PATH = "/asr"
internal const val DEBUG_ASR_VALIDATION_HOSTED_SEARCH = "hosted_search"

private const val DEBUG_ASR_THREAD_NAME = "debug-asr-text-server"
private const val DEBUG_ASR_SOCKET_TIMEOUT_MS = 2000
private const val DEBUG_ASR_MAX_HEADER_BYTES = 8192
private const val DEBUG_ASR_MAX_BODY_BYTES = 32768
private const val DEBUG_ASR_JSON_OBJECT_START = '{'
private val HTTP_HEADER_END = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
