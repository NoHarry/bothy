package cc.noharry.bothy.voice

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DashScopeAsrEngineTest {
    @Test
    fun finishBeforeTheSocketOpensDrainsWithoutSending() {
        val drained = AtomicReference(false)
        DashScopeAsrEngine("ws://127.0.0.1:9/ws", "k", "paraformer-realtime-v2").finish { drained.set(true) }
        assertTrue(drained.get())
    }

    @Test
    fun finishBeforeTaskStartedSendsTheQueuedAudioThenFinishTask() {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        withServer(seen) { engine, socket ->
            engine.accept(ShortArray(16) { 1 })
            val drained = CountDownLatch(1)
            engine.finish { drained.countDown() }
            waitFor(seen) { it == listOf("run") }
            socket.send("""{"header":{"event":"task-started"},"payload":{}}""")
            waitFor(seen) { it == listOf("run", "bin", "finish") }
            socket.send("""{"header":{"event":"task-finished"},"payload":{}}""")
            assertTrue(drained.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun aFailureBeforeTaskStartedDrainsWithoutFinishTask() {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val problem = AtomicReference<VoiceProblem>()
        withServer(seen, onError = { problem.set(it) }) { engine, socket ->
            engine.accept(ShortArray(16) { 1 })
            val drained = CountDownLatch(1)
            engine.finish { drained.countDown() }
            waitFor(seen) { "run" in it }
            socket.send(
                """{"header":{"event":"task-failed","error_code":"CLIENT_ERROR","error_message":"timeout"},"payload":{}}""",
            )
            assertTrue(drained.await(5, TimeUnit.SECONDS))
            assertFalse(synchronized(seen) { "finish" in seen })
            assertEquals(VoiceProblem.RecognitionFailed("CLIENT_ERROR", "timeout"), problem.get())
        }
    }

    private fun withServer(
        seen: MutableList<String>,
        onError: (VoiceProblem) -> Unit = {},
        block: (DashScopeAsrEngine, WebSocket) -> Unit,
    ) {
        val server = MockWebServer()
        val sockets = LinkedBlockingQueue<WebSocket>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sockets.add(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when {
                    "run-task" in text -> seen += "run"
                    "finish-task" in text -> seen += "finish"
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                seen += "bin"
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }))
        server.start()
        val client = OkHttpClient.Builder().build()
        val engine = DashScopeAsrEngine(
            endpoint = server.url("/").toString().replace("http", "ws"),
            apiKey = "test-key",
            model = "paraformer-realtime-v2",
            client = client,
        )
        var socket: WebSocket? = null
        try {
            engine.start(object : AsrListener {
                override fun onPartial(text: String) = Unit
                override fun onFinal(text: String) = Unit
                override fun onError(problem: VoiceProblem) = onError(problem)
            })
            socket = sockets.poll(5, TimeUnit.SECONDS) ?: error("socket was not accepted")
            block(engine, socket)
        } finally {
            runCatching { socket?.close(1000, null) }
            runCatching { engine.stop() }
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    private fun waitFor(seen: MutableList<String>, ready: (List<String>) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val snapshot = synchronized(seen) { seen.toList() }
            if (ready(snapshot)) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out, saw ${synchronized(seen) { seen.toList() }}")
    }
}
