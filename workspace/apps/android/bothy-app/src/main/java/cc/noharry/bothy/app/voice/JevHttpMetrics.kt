package cc.noharry.bothy.app.voice
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID
import java.util.WeakHashMap
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response

/** Allowlisted transport measurements only: never retain URLs, addresses, headers or exception text. */
internal class JevHttpMetrics : EventListener() {
    private fun nanoTime(): Long = System.nanoTime()
    private val lock = Any()
    private var started = nanoTime()
    private val starts = mutableMapOf<String, Long>()
    private val durations = mutableMapOf<String, Long>()
    private val counts = mutableMapOf<String, Int>()
    private var uploadEnd: Long? = null
    private var headersReceived: Long? = null
    private var finished: Long? = null
    private var connectionId: String? = null
    private var reused: Boolean? = null
    private var protocol: String? = null
    private var httpStatus: Int? = null
    private var failureType: String? = null

    override fun callStart(call: Call) { synchronized(lock) { started = nanoTime() } }
    override fun dnsStart(call: Call, domainName: String) = begin("dns")
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = end("dns")
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = begin("connect")
    override fun secureConnectStart(call: Call) = begin("tls")
    override fun secureConnectEnd(call: Call, handshake: Handshake?) = end("tls")
    override fun connectEnd(call: Call, address: InetSocketAddress, proxy: Proxy, protocol: Protocol?) = end("connect")
    override fun connectFailed(call: Call, address: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
        end("tls"); end("connect")
        synchronized(lock) { failureType = ioe.javaClass.simpleName }
    }
    override fun connectionAcquired(call: Call, connection: Connection) {
        val use = JevConnectionIds.acquire(connection)
        synchronized(lock) {
            connectionId = use.first; reused = use.second; protocol = connection.protocol().toString()
        }
    }
    override fun requestBodyStart(call: Call) = begin("upload")
    override fun requestBodyEnd(call: Call, byteCount: Long) {
        end("upload")
        synchronized(lock) { uploadEnd = nanoTime() }
    }
    override fun responseHeadersEnd(call: Call, response: Response) {
        synchronized(lock) { headersReceived = nanoTime(); httpStatus = response.code }
    }
    override fun responseBodyStart(call: Call) = begin("download")
    override fun responseBodyEnd(call: Call, byteCount: Long) = end("download")
    override fun callEnd(call: Call) { synchronized(lock) { finished = nanoTime() } }
    override fun callFailed(call: Call, ioe: IOException) {
        synchronized(lock) { failureType = ioe.javaClass.simpleName; finished = nanoTime() }
    }

    private fun begin(phase: String) { synchronized(lock) {
        starts[phase] = nanoTime(); counts[phase] = (counts[phase] ?: 0) + 1
    } }

    private fun end(phase: String) { synchronized(lock) {
        starts.remove(phase)?.let { durations[phase] = (durations[phase] ?: 0) + nanoTime() - it }
    } }

    fun snapshot(): String = synchronized(lock) {
        val now = finished ?: nanoTime()
        buildJsonObject {
            put("schemaVersion", 1)
            put("connectionScope", JevConnectionIds.scope)
            put("elapsedMs", (now - started) / NANOS_PER_MILLI)
            put("transportFinished", finished != null)
            put("phases", buildJsonObject {
                for (phase in listOf("dns", "connect", "tls", "upload", "download")) {
                    put(phase, buildJsonObject {
                        put("attempts", counts[phase] ?: 0)
                        put("durationMs", ((durations[phase] ?: 0) + (starts[phase]?.let { now - it } ?: 0)) / NANOS_PER_MILLI)
                        put("inFlight", phase in starts)
                    })
                }
            })
            connectionId?.let { put("connectionId", it) }
            reused?.let { put("connectionReused", it) }
            protocol?.let { put("protocol", it) }
            httpStatus?.let { put("httpStatus", it) }
            failureType?.let { put("failureType", it) }
            headersReceived?.let { headers ->
                put("timeToResponseHeadersMs", (headers - started) / NANOS_PER_MILLI)
                uploadEnd?.let { put("uploadEndToResponseHeadersMs", (headers - it) / NANOS_PER_MILLI) }
            }
        }.toString()
    }
}

/** Connection identities are opaque and process scoped; weak keys do not keep sockets alive. */
private object JevConnectionIds {
    val scope: String = UUID.randomUUID().toString()
    private val uses = WeakHashMap<Connection, String>()
    private var sequence = 0L
    @Synchronized fun acquire(connection: Connection): Pair<String, Boolean> {
        val old = uses[connection]
        if (old != null) return old to true
        val id = "c${++sequence}"
        uses[connection] = id
        return id to false
    }
}

private const val NANOS_PER_MILLI = 1_000_000L
