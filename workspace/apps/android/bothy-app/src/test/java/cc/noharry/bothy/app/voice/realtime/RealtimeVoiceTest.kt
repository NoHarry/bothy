package cc.noharry.bothy.app.voice.realtime
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import cc.noharry.bothy.voice.NearSpeechGate
import cc.noharry.bothy.voice.VoiceAudioSource
import cc.noharry.bothy.voice.VoiceSessionStatus
import cc.noharry.bothy.app.console.status
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** One end-to-end conversation against a local service: what is heard, how it ends, a lost connection opened again. */
@RunWith(RobolectricTestRunner::class)
class RealtimeVoiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun aConnectionTheServiceClosesMidConversationIsOpenedAgainOnce() {
        val sessions = LinkedBlockingQueue<String>()
        // The first connection opens its session, then drops; the second stays.
        server.enqueue(MockResponse().withWebSocketUpgrade(service(sessions, dropAfterCreate = true)))
        server.enqueue(MockResponse().withWebSocketUpgrade(service(sessions, dropAfterCreate = false)))
        val ended = LinkedBlockingQueue<String>()
        val voice = voice(onEnd = { ended += it })
        voice.start()
        assertTrue(sessions.poll(10, TimeUnit.SECONDS).orEmpty().contains("session.create"))
        assertTrue(sessions.poll(10, TimeUnit.SECONDS).orEmpty().contains("session.create"))
        waitFor { voice.state.value.status == VoiceSessionStatus.LISTENING }
        assertEquals(VoiceSessionStatus.LISTENING, voice.state.value.status)
        assertTrue(ended.isEmpty())
        assertEquals(2, server.requestCount)
        voice.stop()
    }

    @Test
    fun heardWordsReplaceEachOtherAndTheModelCanEndTheConversation() {
        // As the service sends them (measured 2026-10-14): each delta is the whole sentence so far.
        val deltas = listOf("退", "退出", "退出语音")
        val sockets = LinkedBlockingQueue<WebSocket>()
        server.enqueue(MockResponse().withWebSocketUpgrade(connection(onCreate = { socket ->
            socket.send("""{"type":"conversation.item.input_audio_transcription.started"}""")
            deltas.forEach { socket.send("""{"type":"conversation.item.input_audio_transcription.delta","delta":"$it"}""") }
            sockets += socket
        })))
        val ended = LinkedBlockingQueue<String>()
        val voice = voice(onEnd = { ended += it })
        voice.start()
        val socket = sockets.poll(10, TimeUnit.SECONDS)!!
        waitFor { voice.state.value.liveText == "退出语音" }
        assertEquals("退出语音", voice.state.value.liveText)

        socket.send("""{"type":"response.function_call_arguments.done","items":[{"call_id":"c1","name":"$END_CONVERSATION","arguments":"{}"}]}""")
        waitFor { ended.isNotEmpty() }
        assertEquals("ended by voice", ended.poll())
        voice.stop()
    }

    @Test
    fun xiaobosTaskKeepsTheConversationOpenAcrossAReconnect() {
        // The first connection asks 小博 something, then drops (2026-10-14: a timeout mid-task); the second stays.
        server.enqueue(MockResponse().withWebSocketUpgrade(connection(onCreate = { socket ->
            socket.send("""{"type":"response.function_call_arguments.done","items":[{"call_id":"c1","name":"$ASK_XIAOBO","arguments":"{\"request\":\"打开空调\"}"}]}""")
            socket.close(GOING_AWAY, "timeout")
        })))
        val second = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(connection(onCreate = { second += "created" }, others = { second += it })))
        val asked = LinkedBlockingQueue<String>()
        val answer = CompletableDeferred<String>()
        val voice = voice(agent = { request, _ -> asked += request; answer.await() })
        voice.start()
        assertEquals("打开空调", asked.poll(10, TimeUnit.SECONDS))
        assertEquals("created", second.poll(10, TimeUnit.SECONDS))
        waitFor { voice.state.value.status == VoiceSessionStatus.LISTENING }
        assertTrue("小博 is still working after the reconnect", voice.state.value.agentWorking)

        answer.complete("空调已打开")
        waitFor { !voice.state.value.agentWorking }
        assertFalse(voice.state.value.agentWorking)
        // The new session never asked for c1: its answer is not sent there.
        Thread.sleep(200)
        assertTrue(second.none { "c1" in it })
        voice.stop()
    }

    @Test
    fun wordsHeardWhileXiaoboSaysALateResultAreNotActedOn() = wordsHeardOverXiaoboAreNotActedOn(bargeIn = false)

    /** Full duplex hears the microphone over 小博, but words that came with silence sent are still not the speaker's. */
    @Test
    fun withBargeInWordsHeardInSilenceAreNotActedOnEither() = wordsHeardOverXiaoboAreNotActedOn(bargeIn = true)

    private fun wordsHeardOverXiaoboAreNotActedOn(bargeIn: Boolean) {
        // On a test device (2026-10-21): during 小博's trip plan the service heard 「3 点了。」 and the model asked 小博 again, then
        // spoke over the plan.
        val sockets = LinkedBlockingQueue<WebSocket>()
        val sent = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(connection(onCreate = { sockets += it }, others = { sent += it })))
        val asked = LinkedBlockingQueue<String>()
        val voice = voice(agent = { request, _ -> asked += request; "好" }, bargeIn = bargeIn)
        voice.start()
        val socket = sockets.poll(10, TimeUnit.SECONDS)!!
        waitFor { voice.state.value.status == VoiceSessionStatus.LISTENING }
        var said = 0
        voice.tellLate("桂林三天按飞机往返排好了") { said++ }
        waitFor { said == 1 }

        socket.send("""{"type":"conversation.item.input_audio_transcription.started"}""")
        socket.send("""{"type":"conversation.item.input_audio_transcription.completed","text":"3 点了。"}""")
        socket.send("""{"type":"response.function_call_arguments.done","items":[{"call_id":"c9","name":"$ASK_XIAOBO","arguments":"{\"request\":\"查询桂林行程\"}"}]}""")
        socket.send("""{"type":"response.done"}""")
        socket.send("""{"type":"response.output_audio.delta","delta":"${java.util.Base64.getEncoder().encodeToString(ByteArray(3_200))}"}""")
        socket.send("""{"type":"response.done"}""")
        waitFor { sent.any { IGNORED_CALL_RESULT in it } }

        assertTrue("the call is answered as ignored", sent.any { "c9" in it && IGNORED_CALL_RESULT in it })
        Thread.sleep(200)
        assertTrue("小博 is not asked again", asked.isEmpty())
        assertFalse("the model's answer is not played over 小博", voice.state.value.assistantSpeaking)
        assertTrue(voice.state.value.segments.none { it.text == "3 点了。" })
        voice.stop()
    }

    @Test
    fun withBargeInTheSpeakerCutsInOnXiaobosLateResult() {
        // Owner 2026-10-21: 「补播报的时候端到端的全双工就失效了」.
        val sockets = LinkedBlockingQueue<WebSocket>()
        val sent = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(connection(onCreate = { sockets += it }, others = { sent += it })))
        val asked = LinkedBlockingQueue<String>()
        val voice = voice(agent = { request, _ -> asked += request; "好" }, bargeIn = true, speaking = true)
        voice.start()
        val socket = sockets.poll(10, TimeUnit.SECONDS)!!
        waitFor { voice.state.value.status == VoiceSessionStatus.LISTENING }
        var said = 0
        voice.tellLate("桂林三天按飞机往返排好了：周五上午飞过去，先逛象鼻山和东西巷") { said++ }
        waitFor { said == 1 }

        // 小博's own words coming back are not the speaker's.
        socket.send("""{"type":"conversation.item.input_audio_transcription.started"}""")
        socket.send("""{"type":"conversation.item.input_audio_transcription.completed","text":"先逛象鼻山和东西巷。"}""")
        socket.send("""{"type":"response.function_call_arguments.done","items":[{"call_id":"c8","name":"$ASK_XIAOBO","arguments":"{\"request\":\"逛象鼻山\"}"}]}""")
        socket.send("""{"type":"response.done"}""")
        waitFor { sent.any { "c8" in it && IGNORED_CALL_RESULT in it } }
        assertTrue(sent.any { "c8" in it && IGNORED_CALL_RESULT in it })
        socket.send("""{"type":"response.done"}""")

        // The speaker's own words cut in, and are handed over as any.
        socket.send("""{"type":"conversation.item.input_audio_transcription.started"}""")
        socket.send("""{"type":"conversation.item.input_audio_transcription.delta","delta":"停一下"}""")
        socket.send("""{"type":"conversation.item.input_audio_transcription.completed","text":"停一下，改坐高铁吧。"}""")
        socket.send("""{"type":"response.function_call_arguments.done","items":[{"call_id":"c9","name":"$ASK_XIAOBO","arguments":"{\"request\":\"改坐高铁\"}"}]}""")
        waitFor { asked.isNotEmpty() }
        assertTrue(asked.peek().orEmpty(), asked.peek().orEmpty().startsWith("改坐高铁"))
        assertTrue(sent.none { "c9" in it && IGNORED_CALL_RESULT in it })
        assertTrue(voice.state.value.segments.any { it.text == "停一下，改坐高铁吧。" })
        voice.stop()
    }

    @Test
    fun aLateResultWaitsForTheAnswerTheModelOwes() {
        // 小博 finishing just after the model was handed 「还在办」: its 「请稍等」 comes first, then 小博's result.
        val sockets = LinkedBlockingQueue<WebSocket>()
        val sent = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(connection(onCreate = { sockets += it }, others = { sent += it })))
        val voice = voice(agent = { _, _ -> "小博还在办" })
        voice.start()
        val socket = sockets.poll(10, TimeUnit.SECONDS)!!
        waitFor { voice.state.value.status == VoiceSessionStatus.LISTENING }
        socket.send("""{"type":"response.function_call_arguments.done","items":[{"call_id":"c1","name":"$ASK_XIAOBO","arguments":"{\"request\":\"规划桂林三天\"}"}]}""")
        waitFor { sent.any { "c1" in it } }

        var said = 0
        voice.tellLate("桂林三天排好了") { said++ }
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("held while the model owes its answer", 0, said)
        socket.send("""{"type":"response.done"}""")
        waitFor { said == 1 }
        assertEquals(1, said)
        voice.stop()
    }

    @Test
    fun withoutLateSpeechALateResultIsOnlyToldToTheModel() {
        // Owner 2026-10-21: 「能不能给一版不要补播报的，现在这个打乱了很多流程」.
        val sockets = LinkedBlockingQueue<WebSocket>()
        val sent = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(connection(onCreate = { sockets += it }, others = { sent += it })))
        val asked = LinkedBlockingQueue<String>()
        val voice = voice(agent = { request, _ -> asked += request; "好" }, lateSpeech = false)
        voice.start()
        val socket = sockets.poll(10, TimeUnit.SECONDS)!!
        waitFor { voice.state.value.status == VoiceSessionStatus.LISTENING }

        var said = 0
        voice.tellLate("桂林三天按飞机往返排好了") { said++ }
        waitFor { sent.any { "桂林三天按飞机往返排好了" in it } }
        assertTrue("the model knows it", sent.any { "桂林三天按飞机往返排好了" in it })
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("nobody says it", 0, said)

        // Nothing is held back: what the speaker says next is handed over as any.
        socket.send("""{"type":"conversation.item.input_audio_transcription.completed","text":"改坐高铁吧。"}""")
        socket.send("""{"type":"response.function_call_arguments.done","items":[{"call_id":"c9","name":"$ASK_XIAOBO","arguments":"{\"request\":\"改坐高铁\"}"}]}""")
        waitFor { asked.isNotEmpty() }
        assertTrue(asked.peek().orEmpty(), asked.peek().orEmpty().startsWith("改坐高铁"))
        assertTrue(sent.none { IGNORED_CALL_RESULT in it })
        voice.stop()
    }

    @Test
    fun aResultThatComesAsTheWaitRunsOutAnswersTheCall() {
        RealtimeWaits.begin()
        RealtimeWaits.hold("桂林三天排好了")
        assertEquals("桂林三天排好了", RealtimeWaits.takeHeld())
        assertEquals(null, RealtimeWaits.takeHeld())
        RealtimeWaits.hold("没人取")
        RealtimeWaits.end()
        assertFalse(RealtimeWaits.waiting)
        assertEquals("the last wait's end clears it", null, RealtimeWaits.takeHeld())
    }

    private fun voice(
        agent: RealtimeAgent = RealtimeAgent { _, _ -> "好" },
        onEnd: (String) -> Unit = {},
        bargeIn: Boolean = false,
        /** The microphone's level: someone speaking (a square wave at -20 dBFS), or the default silence. */
        speaking: Boolean = false,
        lateSpeech: Boolean = true,
    ) = RealtimeVoice(
        context = context,
        config = RealtimeConfig(enabled = true, bargeIn = bargeIn, lateSpeech = lateSpeech),
        audio = VoiceAudioSource { flow { while (true) { emit(ShortArray(1_600) { if (speaking) (if (it % 20 < 10) 3_000 else -3_000).toShort() else 0 }); delay(100) } } },
        gate = NearSpeechGate(null),
        agent = agent,
        endsWhenIdle = false,
        onDismiss = {},
        onEnd = onEnd,
        client = OkHttpClient(),
        key = "test-key",
        url = server.url("/").toString().replace("http", "ws"),
    )

    /** Up to 5 s, running what the session posts to the main thread. */
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** A service connection: the session is created, then [onCreate]; other messages but audio go to [others]. */
    private fun connection(onCreate: (WebSocket) -> Unit, others: (String) -> Unit = {}) = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if ("input_audio_buffer" in text) return
            if (""""session.create"""" !in text) return others(text)
            webSocket.send("""{"type":"session.created","session":{"id":"s"}}""")
            onCreate(webSocket)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    private fun service(sessions: LinkedBlockingQueue<String>, dropAfterCreate: Boolean) = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (""""session.create"""" !in text) return
            sessions += text
            webSocket.send("""{"type":"session.created","session":{"id":"s"}}""")
            if (dropAfterCreate) webSocket.close(GOING_AWAY, "lost")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    private companion object {
        const val GOING_AWAY = 1001
    }
}
