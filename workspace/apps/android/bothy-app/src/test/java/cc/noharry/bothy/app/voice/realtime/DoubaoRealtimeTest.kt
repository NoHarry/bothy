package cc.noharry.bothy.app.voice.realtime
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.bothy.app.runtime.append
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 端到端演示模式's wire: the Seeduplex events as captured from the service, the messages sent, float audio, and the probe. */
@RunWith(RobolectricTestRunner::class)
class DoubaoRealtimeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun parsesTheEventsTheServiceSends() {
        assertEquals(
            DoubaoRealtimeEvent.SessionCreated("a1ff"),
            parseDoubaoRealtimeEvent("""{"type":"session.created","event_id":"event_1","session":{"id":"a1ff"}}"""),
        )
        assertEquals(DoubaoRealtimeEvent.HearingStarted, parseDoubaoRealtimeEvent("""{"item_id":"1","type":"conversation.item.input_audio_transcription.started"}"""))
        assertEquals(
            DoubaoRealtimeEvent.Hearing("今天"),
            parseDoubaoRealtimeEvent("""{"content_index":0,"delta":"今天","type":"conversation.item.input_audio_transcription.delta","item_id":"1"}"""),
        )
        assertEquals(
            DoubaoRealtimeEvent.Heard("今天有点累，陪我聊会天吧。"),
            parseDoubaoRealtimeEvent("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"1","text":"今天有点累，陪我聊会天吧。"}"""),
        )
        assertEquals(DoubaoRealtimeEvent.ReplyText("累了就慢慢聊。"), parseDoubaoRealtimeEvent("""{"type":"response.output_text.done","text":"累了就慢慢聊。"}"""))
        assertEquals(DoubaoRealtimeEvent.SpeechStarted, parseDoubaoRealtimeEvent("""{"type":"response.output_audio.started","tts_type":"default"}"""))
        assertEquals(DoubaoRealtimeEvent.SpeechDone, parseDoubaoRealtimeEvent("""{"type":"response.output_audio.done"}"""))
        assertEquals(DoubaoRealtimeEvent.Closed, parseDoubaoRealtimeEvent("""{"type":"session.closed"}"""))
        val audio = parseDoubaoRealtimeEvent("""{"type":"response.output_audio.delta","delta":"${Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))}"}""")
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), (audio as DoubaoRealtimeEvent.Speech).float32)
        assertEquals(null, parseDoubaoRealtimeEvent("""{"type":"response.output_text.delta","delta":"累了"}"""))
        assertEquals(null, parseDoubaoRealtimeEvent("not json"))
    }

    @Test
    fun parsesToolCallsUsageAndErrors() {
        val calls = parseDoubaoRealtimeEvent(
            """{"items":[{"call_id":"01d0","name":"ask_xiaobo","arguments":"{\"request\":\"导航去公司\"}"}],"type":"response.function_call_arguments.done"}""",
        ) as DoubaoRealtimeEvent.ToolCalls
        assertEquals(listOf(DoubaoToolCall("01d0", "ask_xiaobo", """{"request":"导航去公司"}""")), calls.calls)
        assertEquals("导航去公司", calls.calls.single().request())
        assertEquals("打开空调", DoubaoToolCall("1", ASK_XIAOBO, "打开空调").request())
        assertEquals(
            DoubaoRealtimeEvent.ResponseDone(2756, 220),
            parseDoubaoRealtimeEvent("""{"type":"response.done","response":{"usage":{"total_tokens":2976,"input_tokens":2756,"output_tokens":220}}}"""),
        )
        assertEquals(DoubaoRealtimeEvent.ServerError("bad voice"), parseDoubaoRealtimeEvent("""{"type":"error","error":{"message":"bad voice"}}"""))
    }

    @Test
    fun sessionCreateCarriesTheToolTheRuleAndTheAudioFormats() {
        val config = RealtimeConfig(enabled = true, voice = DoubaoRealtimeVoice.YUNZHOU.id, persona = "你是小博。", personaPreset = CUSTOM_REALTIME_PERSONA)
        val session = Json.parseToJsonElement(doubaoRealtimeSessionCreate(config)).jsonObject["session"]!!.jsonObject
        assertEquals("1.2.6.1", session["model"]!!.jsonPrimitive.content)
        val instructions = session["instructions"]!!.jsonPrimitive.content
        assertTrue(instructions.startsWith("你是小博。\n") && "ask_xiaobo" in instructions)
        val tools = session["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(END_CONVERSATION, ASK_XIAOBO), tools.map { it["name"]!!.jsonPrimitive.content })
        val tool = tools.last()
        assertEquals("request", tool["parameters"]!!.jsonObject["required"]!!.jsonArray.single().jsonPrimitive.content)
        val audio = session["audio"]!!.jsonObject
        assertEquals("16000", audio.path("input", "format")["sample_rate"]!!.jsonPrimitive.content)
        assertEquals("24000", audio.path("output", "format")["sample_rate"]!!.jsonPrimitive.content)
        assertEquals(DoubaoRealtimeVoice.YUNZHOU.id, audio["output"]!!.jsonObject["voice"]!!.jsonPrimitive.content)
    }

    @Test
    fun audioAndToolResultsHaveTheShapesTheServiceTakes() {
        val append = Json.parseToJsonElement(doubaoRealtimeAudio(byteArrayOf(0, 1, 2))).jsonObject
        assertEquals("input_audio_buffer.append", append["type"]!!.jsonPrimitive.content)
        assertArrayEquals(byteArrayOf(0, 1, 2), Base64.getDecoder().decode(append["audio"]!!.jsonPrimitive.content))
        val result = Json.parseToJsonElement(doubaoRealtimeToolResult("c1", "已开始导航")).jsonObject
        assertEquals("conversation.item.create", result["type"]!!.jsonPrimitive.content)
        val item = result["items"]!!.jsonArray.single().jsonObject
        assertEquals("c1", item["call_id"]!!.jsonPrimitive.content)
        assertEquals("tool", item["role"]!!.jsonPrimitive.content)
        val content = item["content"]!!.jsonArray.single().jsonObject
        assertEquals("input_text", content["type"]!!.jsonPrimitive.content)
        assertEquals("已开始导航", content["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun floatAudioBecomesPcm16AcrossChunkBoundaries() {
        val floats = floatsLe(0f, 1f, -1f, 0.5f, 2f, Float.NaN)
        val converter = Float32ToPcm16()
        // Split inside the second sample: its first bytes wait for the next chunk.
        val first = converter.convert(floats.copyOfRange(0, 6))
        val rest = converter.convert(floats.copyOfRange(6, floats.size))
        val samples = ByteBuffer.wrap(first + rest).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().let { b -> ShortArray(b.remaining()).also { b.get(it) } }
        assertArrayEquals(shortArrayOf(0, 32767, -32767, 16384, 32767, 0), samples)
        assertEquals(2, first.size)
    }

    @Test
    fun gainRaisesTheVoiceAndClipsAtFullScale() {
        val pcm = Float32ToPcm16(REALTIME_OUTPUT_GAIN).convert(floatsLe(0.25f, -0.3f, 0.75f))
        val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().let { b -> ShortArray(b.remaining()).also { b.get(it) } }
        assertArrayEquals(shortArrayOf(16384, -19660, 32767), samples)
    }

    @Test
    fun toolResultsAreCleanedAndDismissalsRecognized() {
        assertEquals("已导航 到公司 预计 25 分钟", realtimeToolResult("**已导航** 到公司\n\n# 预计 25 分钟"))
        assertEquals("小博办完了，没有说明。", realtimeToolResult("  "))
        assertEquals(800, realtimeToolResult("长".repeat(2000)).length)
        assertTrue(dismisses("退下吧。"))
        assertTrue(dismisses("退出语音。"))
        assertTrue(dismisses("好，关闭语音"))
        assertFalse(dismisses("退出QQ音乐"))
        assertTrue(dismisses("好了，再见"))
        assertFalse(dismisses("帮我导航去再见面的地方吧"))
        assertFalse(dismisses("再见到他记得叫我"))
        // 导航's or 地图's own voice, and a negation, never end the conversation.
        assertFalse(dismisses("导航关闭语音"))
        assertFalse(dismisses("地图关掉语音"))
        assertFalse(dismisses("别退出语音"))
        assertFalse(dismisses("先别退下"))
        // A task said with the goodbye goes to the model, which hands it over before ending.
        assertFalse(dismisses("回家然后退下吧"))
        assertTrue(dismisses("那就退出语音。"))
        assertTrue(dismisses("不聊了"))
        // 「退出」 alone right after a wake (owner 2026-10-20).
        assertTrue(dismisses("退出。"))
        assertTrue(dismisses("好，退出吧"))
        assertFalse(dismisses("别退出"))
        assertFalse(dismisses("先别退出"))
        assertTrue(dismisses("那个就是退出。"))
        assertTrue(dismisses("嗯，我退出吧"))
        assertFalse(dismisses("我要退出QQ音乐"))
        assertFalse(dismisses("导航退出"))
    }

    @Test
    fun anAdditionMergedIntoTheRunCarriesTheReplyOnce() {
        val pending = listOf("first", "addition")
        assertEquals(REALTIME_MERGED_RESULT, realtimeToolAnswer("first", pending, "已导航到深圳北站"))
        assertEquals("已导航到深圳北站", realtimeToolAnswer("addition", pending, "已导航到深圳北站"))
        assertEquals("已导航到深圳北站", realtimeToolAnswer("addition", listOf("addition"), "已导航到深圳北站"))
    }

    @Test
    fun gateFollowsTheWakeLevelElseAFloor() {
        assertEquals(-40.0, realtimeGate(-30.0, nearGate = true).thresholdDb!!, 0.001)
        assertEquals(REALTIME_FLOOR_DB, realtimeGate(-30.0, nearGate = false).thresholdDb!!, 0.001)
        assertEquals(REALTIME_FLOOR_DB, realtimeGate(null, nearGate = true).thresholdDb!!, 0.001)
    }

    @Test
    fun probeReportsSessionCreatedAndSendsTheSessionFirst() = runBlocking {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                if (""""session.create"""" in text) webSocket.send("""{"type":"session.created","session":{"id":"s1"}}""")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        }))
        val result = probeDoubaoRealtime(OkHttpClient(), "test-key", RealtimeConfig(), server.url("/").toString().replace("http", "ws"))
        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("test-key", request.getHeader("X-Api-Key"))
        assertTrue(request.getHeader("X-Api-Connect-Id").orEmpty().isNotBlank())
        assertTrue(""""session.create"""" in received.poll(5, TimeUnit.SECONDS).orEmpty())
    }

    @Test
    fun probeExplainsARefusedKey() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"requested resource not granted"}"""))
        val result = probeDoubaoRealtime(OkHttpClient(), "test-key", RealtimeConfig(), server.url("/").toString().replace("http", "ws"))
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message, "HTTP 403" in message && "not granted" in message)
    }

    @Test
    fun configRoundTripsAndDefaultsOff() {
        assertFalse(loadRealtimeConfig(context).enabled)
        saveRealtimeConfig(context, RealtimeConfig(enabled = true, voice = DoubaoRealtimeVoice.XIAOHE.id, persona = " 你是小博 ", bargeIn = true))
        assertEquals(RealtimeConfig(true, DoubaoRealtimeVoice.XIAOHE.id, "你是小博", bargeIn = true), loadRealtimeConfig(context))
        assertFalse("补播报 is off unless chosen", loadRealtimeConfig(context).lateSpeech)
        saveRealtimeConfig(context, RealtimeConfig(lateSpeech = true))
        assertTrue(loadRealtimeConfig(context).lateSpeech)
        saveRealtimeConfig(context, RealtimeConfig(persona = ""))
        assertEquals(DEFAULT_REALTIME_PERSONA, loadRealtimeConfig(context).persona)
    }

    @Test
    fun aNewConversationIsToldTheSessionsLatestExchanges() {
        val request = "规划接机再吃素的路线（用户原话：「先去机场接她，再去吃素」）"
        val result = "导航已经在走了：先去宝安机场 T3 接人，再开去若蘭慈素食馆。" + "路上".repeat(100)
        val told = realtimeInstructions("你是小博。", "- 称呼与身份：叫我老王", remembers = true, recent = listOf(request to result))
        assertTrue(told.contains("\n用户：规划接机再吃素的路线\n回答：导航已经在走了：先去宝安机场 T3 接人，再开去若蘭慈素食馆。"))
        assertFalse(told.contains("先去机场接她"))
        val answer = told.lines().first { it.startsWith("回答：") }.removePrefix("回答：")
        assertTrue(answer.endsWith("…") && answer.length == 161)
        // What 小博 remembers stays last; with nothing said before, nothing is added.
        assertTrue(told.endsWith("叫我老王"))
        assertEquals(realtimeInstructions("你是小博。"), realtimeInstructions("你是小博。", recent = emptyList()))
        val session = Json.parseToJsonElement(doubaoRealtimeSessionCreate(RealtimeConfig(), recent = listOf("你好" to "你好呀"))).jsonObject
        assertTrue(session["session"]!!.jsonObject["instructions"]!!.jsonPrimitive.content.endsWith("用户：你好\n回答：你好呀"))
    }

    @Test
    fun aPresetIsWhatTheModelIsToldAndATypedPersonaIsKept() {
        val savage = RealtimeConfig(persona = "你是小博", personaPreset = DoubaoRealtimePersona.SAVAGE.id)
        assertTrue(realtimeInstructions(savage.effectivePersona).startsWith(DoubaoRealtimePersona.SAVAGE.text + "\n"))
        assertEquals("毒舌损友", savage.personaLabel())
        saveRealtimeConfig(context, savage)
        assertEquals(savage, loadRealtimeConfig(context))
        // Custom: the typed text, kept while a preset is in use.
        assertEquals("你是小博", savage.copy(personaPreset = CUSTOM_REALTIME_PERSONA).effectivePersona)
        assertEquals("自定义人设", savage.copy(personaPreset = CUSTOM_REALTIME_PERSONA).personaLabel())

        // Saved before the presets: a typed persona stays in use, the old default is the default preset.
        val prefs = context.getSharedPreferences("debug_realtime_voice", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("persona", "你是老司机").commit()
        assertEquals(CUSTOM_REALTIME_PERSONA, loadRealtimeConfig(context).personaPreset)
        assertEquals("你是老司机", loadRealtimeConfig(context).effectivePersona)
        prefs.edit().clear().putString("persona", DEFAULT_REALTIME_PERSONA).commit()
        assertEquals(DoubaoRealtimePersona.WARM.id, loadRealtimeConfig(context).personaPreset)
        // A preset this build does not know falls back the same way.
        prefs.edit().putString("persona_preset", "gone").commit()
        assertEquals(DoubaoRealtimePersona.WARM.id, loadRealtimeConfig(context).personaPreset)
    }

    @Test
    fun presetsAreDistinctShortEnoughAndKeepTheWakeName() {
        val presets = DoubaoRealtimePersona.entries
        assertEquals(presets.size, presets.map { it.id }.toSet().size)
        assertEquals(presets.size, presets.map { it.text }.toSet().size)
        assertFalse(presets.any { it.id == CUSTOM_REALTIME_PERSONA })
        presets.forEach { assertTrue(it.label, it.text.startsWith("你是小博") && it.text.length <= 300) }
        assertEquals(DEFAULT_REALTIME_PERSONA, DoubaoRealtimePersona.WARM.text)
    }

    private fun floatsLe(vararg values: Float): ByteArray =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach(::putFloat) }.array()

    private fun JsonObject.path(vararg names: String): JsonObject = names.fold(this) { node, name -> node[name]!!.jsonObject }
}
