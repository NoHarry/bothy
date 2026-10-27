package cc.noharry.bothy.playground.mcp
import cc.noharry.bothy.playground.PlaygroundStore
import cc.noharry.bothy.playground.cabin.Seat
import cc.noharry.bothy.playground.headunit.cabin
import java.net.HttpURLConnection
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The HTTP side over a real loopback socket: what an MCP client (Bothy's OkHttp one) sends and gets back. */
class McpHttpServerTest {
    private val store = PlaygroundStore()
    private lateinit var server: McpHttpServer

    @Before
    fun start() {
        server = McpHttpServer(0, playgroundEndpoints(store)).also { it.start() }
    }

    @After
    fun stop() = server.close()

    private fun send(method: String, path: String, body: String? = null): Triple<Int, String, Map<String, String>> {
        val connection = URL("http://127.0.0.1:${server.boundPort}$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json, text/event-stream")
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        val text = (if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        val headers = connection.headerFields.filterKeys { it != null }.mapValues { it.value.first() }
        return Triple(status, text, headers)
    }

    @Test
    fun initializeGivesASessionAndANotificationIsAccepted() {
        val (status, body, headers) = send("POST", "/home/mcp", """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
        assertEquals(200, status)
        assertTrue(body.contains("bothy-playground-home"))
        assertNotNull(headers["Mcp-Session-Id"])
        assertEquals(202, send("POST", "/home/mcp", """{"jsonrpc":"2.0","method":"notifications/initialized"}""").first)
    }

    @Test
    fun aToolCallOverHttpChangesTheSharedWorld() {
        val (status, body) = send("POST", "/cabin/mcp", """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"set_window","arguments":{"window":"all","open":40}}}""")
        assertEquals(200, status)
        assertTrue(body, body.contains("全部车窗开到 40%"))
        assertEquals(Seat.entries.associateWith { 40 }, store.read().cabin.windows)
    }

    @Test
    fun otherMethodsPathsAndOriginsAreTurnedAway() {
        assertEquals(405, send("GET", "/cabin/mcp").first)
        assertEquals(404, send("POST", "/garage/mcp", "{}").first)
        assertEquals(400, send("POST", "/cabin/mcp", "not json").first)
        // HttpURLConnection will not send Origin, so a browser's request is built by hand.
        fun fromPage(origin: String) = server.respond(HttpRequest("POST", "/cabin/mcp", mapOf("origin" to origin), """{"jsonrpc":"2.0","id":1,"method":"ping"}""")).status
        assertEquals(403, fromPage("https://evil.example"))
        assertEquals(200, fromPage("http://localhost:3000"))
        assertFalse(McpHttpServer.isLocalOrigin("http://localhost.evil.example"))
        assertTrue(send("GET", "/").second.contains("/cabin/mcp"))
    }
}
