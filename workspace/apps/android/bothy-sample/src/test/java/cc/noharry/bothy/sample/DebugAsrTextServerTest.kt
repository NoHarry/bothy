package cc.noharry.bothy.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class DebugAsrTextServerTest {
    @Test
    fun parsesPlainTextBodyWithQueryOverrides() {
        val body = "打开空调"
        val request = parseDebugAsrHttpRequest(
            http(
                path = "/asr?backend=catalog&stream=0",
                contentType = "text/plain; charset=utf-8",
                body = body,
            ).byteInput(),
        )

        assertEquals(DebugAsrTextRequest(body, "live", "0"), request)
    }

    @Test
    fun parsesJsonBody() {
        val request = parseDebugAsrHttpRequest(
            http(
                contentType = "application/json",
                body = """{"text":"导航去广州塔","backend":"simulated","stream":"1","validation":"hosted_search","profile":"deepseek-api"}""",
            ).byteInput(),
        )

        assertEquals(DebugAsrTextRequest("导航去广州塔", null, "1", "hosted_search", "deepseek-api"), request)
    }

    @Test
    fun rejectsUnsupportedPath() {
        val request = parseDebugAsrHttpRequest(http(path = "/other", body = "hello").byteInput())

        assertEquals(null, request)
    }

    @Test
    fun loopbackServerAcceptsAsrPost() {
        val received = ArrayBlockingQueue<DebugAsrTextRequest>(1)
        val server = DebugAsrTextServer(port = 0) { received += it }
        val port = server.start()
        try {
            Socket(DEBUG_ASR_HOST, port).use { socket ->
                socket.getOutputStream().write(http(body = "好热").toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
                val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(response.startsWith("HTTP/1.1 202 Accepted"))
            }
            assertEquals(DebugAsrTextRequest("好热"), received.poll(2, TimeUnit.SECONDS))
        } finally {
            server.stop()
        }
    }

    private fun http(
        path: String = "/asr",
        contentType: String = "text/plain; charset=utf-8",
        body: String,
    ): String {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return """
            |POST $path HTTP/1.1
            |Host: 127.0.0.1
            |Content-Type: $contentType
            |Content-Length: ${bytes.size}
            |
            |$body
        """.trimMargin().replace("\n", "\r\n")
    }

    private fun String.byteInput(): ByteArrayInputStream =
        ByteArrayInputStream(toByteArray(Charsets.UTF_8))
}
