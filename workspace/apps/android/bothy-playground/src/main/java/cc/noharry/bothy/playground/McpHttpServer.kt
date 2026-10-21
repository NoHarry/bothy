package cc.noharry.bothy.playground

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * MCP's Streamable HTTP transport, the plain JSON half of it: each POST carries a JSON-RPC message (or a batch) and
 * gets the answer as `application/json`, a notification gets 202, GET gets 405 (no server-initiated stream). It
 * listens on loopback only, so only apps on this device reach it, and refuses a browser page from another origin.
 * One short-lived connection per request keeps it small; the clients it serves reconnect as they need.
 */
class McpHttpServer(private val port: Int, private val endpoints: Map<String, McpEndpoint>) : Closeable {
    private var socket: ServerSocket? = null
    private var workers: ExecutorService? = null

    /** Binds and starts accepting; throws when the port is taken. */
    fun start() {
        val server = ServerSocket()
        server.reuseAddress = true
        // 127.0.0.1 itself: Android's getLoopbackAddress() is ::1, which a client dialling 127.0.0.1 never reaches.
        server.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port))
        socket = server
        val pool = Executors.newFixedThreadPool(WORKERS)
        workers = pool
        Thread({ accept(server, pool) }, "playground-mcp").apply { isDaemon = true }.start()
    }

    val boundPort: Int get() = socket?.localPort ?: port

    override fun close() {
        runCatching { socket?.close() }
        workers?.shutdownNow()
    }

    private fun accept(server: ServerSocket, pool: ExecutorService) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            pool.execute { client.use(::serve) }
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        val input = BufferedInputStream(client.getInputStream())
        val response = try {
            val request = readRequest(input) ?: return
            respond(request)
        } catch (_: IOException) {
            return
        } catch (tooBig: IllegalStateException) {
            HttpResponse(413, body = tooBig.message.orEmpty(), type = "text/plain; charset=utf-8")
        }
        client.getOutputStream().apply {
            write(response.bytes())
            flush()
        }
    }

    internal fun respond(request: HttpRequest): HttpResponse {
        val origin = request.header("origin")
        if (origin != null && !isLocalOrigin(origin)) return HttpResponse(403, body = "origin not allowed", type = "text/plain")
        if (request.path == "/" && request.method == "GET") return HttpResponse(200, body = indexText(), type = "text/plain; charset=utf-8")
        val endpoint = endpoints[request.path] ?: return HttpResponse(404, body = "not found", type = "text/plain")
        return when (request.method) {
            "POST" -> post(endpoint, request.body)
            "DELETE" -> HttpResponse(200)
            "GET" -> HttpResponse(405, headers = mapOf("Allow" to "POST, DELETE"))
            else -> HttpResponse(405, headers = mapOf("Allow" to "POST, DELETE"))
        }
    }

    private fun post(endpoint: McpEndpoint, body: String): HttpResponse {
        val parsed = try {
            Json.parseToJsonElement(body)
        } catch (_: SerializationException) {
            return json(400, McpEndpoint.error(JsonNull, McpEndpoint.PARSE_ERROR, "不是 JSON"))
        }
        val messages = when (parsed) {
            is JsonObject -> listOf(parsed)
            is JsonArray -> parsed.filterIsInstance<JsonObject>()
            else -> emptyList()
        }
        val answers = messages.mapNotNull(endpoint::handle)
        if (answers.isEmpty()) return HttpResponse(202)
        val initializing = messages.any { (it["method"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "initialize" }
        val headers = if (initializing) mapOf("Mcp-Session-Id" to UUID.randomUUID().toString()) else emptyMap()
        val out = if (parsed is JsonArray) JsonArray(answers) else answers.single()
        return json(200, out, headers)
    }

    private fun json(status: Int, body: kotlinx.serialization.json.JsonElement, headers: Map<String, String> = emptyMap()) =
        HttpResponse(status, headers, body.toString(), "application/json")

    private fun indexText(): String = "Bothy Playground MCP\n" + endpoints.keys.joinToString("\n") { "POST http://127.0.0.1:$boundPort$it" }

    companion object {
        const val DEFAULT_PORT = 8790
        private const val WORKERS = 4
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_BODY_BYTES = 1 shl 20
        private const val MAX_HEADER_BYTES = 16 * 1024

        /** A request line, headers and a Content-Length body; null when the client closed without sending one. */
        internal fun readRequest(input: InputStream): HttpRequest? {
            val head = readHead(input) ?: return null
            val lines = head.split("\r\n")
            val parts = lines.first().split(" ")
            if (parts.size < 2) throw IOException("bad request line")
            val headers = lines.drop(1).filter { it.contains(':') }
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            check(length <= MAX_BODY_BYTES) { "body too large" }
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) throw IOException("body cut short")
                read += n
            }
            return HttpRequest(parts[0].uppercase(), parts[1].substringBefore('?'), headers, String(body, Charsets.UTF_8))
        }

        private fun readHead(input: InputStream): String? {
            val bytes = java.io.ByteArrayOutputStream()
            var matched = 0
            while (matched < 4) {
                val b = input.read()
                if (b < 0) return if (bytes.size() == 0) null else throw IOException("head cut short")
                bytes.write(b)
                matched = if (b == HEAD_END[matched].code) matched + 1 else if (b == '\r'.code) 1 else 0
                check(bytes.size() <= MAX_HEADER_BYTES) { "headers too large" }
            }
            return bytes.toString(Charsets.US_ASCII.name()).removeSuffix(HEAD_END)
        }

        private const val HEAD_END = "\r\n\r\n"

        internal fun isLocalOrigin(origin: String): Boolean =
            origin == "null" || LOCAL_ORIGINS.any { origin == it || origin.startsWith("$it:") }

        private val LOCAL_ORIGINS = listOf("http://localhost", "http://127.0.0.1", "https://localhost", "https://127.0.0.1")
    }
}

internal data class HttpRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String) {
    fun header(name: String): String? = headers[name.lowercase()]
}

internal data class HttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val type: String? = null,
) {
    fun bytes(): ByteArray {
        val payload = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status ${REASONS[status] ?: "OK"}\r\n")
            type?.let { append("Content-Type: $it\r\n") }
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("Content-Length: ${payload.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        return head.toByteArray(Charsets.US_ASCII) + payload
    }

    private companion object {
        val REASONS = mapOf(200 to "OK", 202 to "Accepted", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed", 413 to "Payload Too Large")
    }
}
