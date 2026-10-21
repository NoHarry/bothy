package cc.noharry.bothy.sample

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.bothy.transport.HttpEvent
import cc.noharry.bothy.transport.HttpRequest
import cc.noharry.bothy.transport.HttpResponse
import cc.noharry.bothy.transport.HttpTransport
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 陪聊: where a voice turn goes, the OpenAI-compatible stream, and the settings. */
@RunWith(RobolectricTestRunner::class)
class DebugCompanionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val routing = DebugCompanionConfig(enabled = true, mode = DebugCompanionMode.ROUTE)

    @Test
    fun onlyScoredSmallTalkWithoutTaskWordsGoesToTheCompanion() {
        fun route(words: String, score: Double?, config: DebugCompanionConfig = routing, last: String? = null, lastWasCompanion: Boolean = false) =
            decideCompanionRoute(config, words, score, last, lastWasCompanion)

        assertTrue(route("今天有点累", 0.9).toCompanion)
        assertFalse(route("今天有点累", 0.6).toCompanion)
        assertFalse(route("今天有点累", null).toCompanion)
        assertEquals("task word 「空调」", route("好热啊，空调开大点", 0.9).reason)
        assertFalse("an answer to the agent's question is for the agent", route("好的", 0.9, last = "要我帮你导航过去吗？").toCompanion)
        assertTrue("a question the companion asked is still chat", route("还行吧", 0.9, last = "今天过得怎么样？", lastWasCompanion = true).toCompanion)
        val observing = route("今天有点累", 0.9, config = routing.copy(mode = DebugCompanionMode.OBSERVE))
        assertFalse(observing.toCompanion)
        assertTrue(observing.reason.startsWith("observe: would chat"))
        assertFalse(route("今天有点累", 0.9, config = routing.copy(enabled = false)).toCompanion)
    }

    /** Streams [chunks] (bytes, so a character may be cut between two) after [status]; records the request. */
    private class FakeStream(private val status: Int, private val chunks: List<ByteArray>) : HttpTransport {
        var request: HttpRequest? = null

        override fun stream(request: HttpRequest): Flow<HttpEvent> = flow {
            this@FakeStream.request = request
            emit(HttpEvent.Response(status))
            chunks.forEach { emit(HttpEvent.Data(it)) }
            emit(HttpEvent.Done)
        }

        override suspend fun execute(request: HttpRequest): HttpResponse = throw IOException("not used")
    }

    private val endpoint = DebugCompanionEndpoint("https://chat.test/v1/", "qwen3.8-omni-flash", """{"enable_thinking": false, "model": "ignored"}""")

    @Test
    fun theStreamIsReadOnBytesSoACharacterCutBetweenChunksStaysWhole() = runBlocking {
        val sse = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"想想\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"辛苦啦，\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"歇会儿吧。\"}}]}\n\ndata: [DONE]\n\n"
        val bytes = sse.toByteArray()
        val cut = sse.indexOf("歇").let { sse.substring(0, it).toByteArray().size + 1 }
        val server = FakeStream(200, listOf(bytes.copyOfRange(0, cut), bytes.copyOfRange(cut, bytes.size)))

        val reply = DebugCompanionChat(server).reply(endpoint, "k", companionMessages("你是小博。", listOf("刚才放的什么歌" to "是《晴天》"), "今天有点累"))

        assertEquals("辛苦啦，歇会儿吧。", reply)
        val request = server.request!!
        assertEquals("https://chat.test/v1/chat/completions", request.url)
        assertEquals("Bearer k", request.headers["Authorization"])
        val body = Json.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals("qwen3.8-omni-flash", body["model"]!!.jsonPrimitive.content)
        assertEquals("false", body["enable_thinking"]!!.jsonPrimitive.content)
        val messages = body["messages"].toString()
        assertTrue(messages.contains("【交回】") && messages.contains("是《晴天》") && messages.contains("\"content\":\"今天有点累"))
    }

    @Test
    fun aRefusalSaysWhatToCheckAndAWholeReplyIsReadToo() = runBlocking {
        try {
            DebugCompanionChat(FakeStream(401, listOf("""{"error":{"message":"Incorrect API key"}}""".toByteArray()))).reply(endpoint, "k", emptyList())
            fail("a 401 is an error")
        } catch (error: IOException) {
            assertTrue(error.message!!, error.message!!.contains("Incorrect API key") && error.message!!.contains("sk-ws-"))
        }
        val whole = """{"choices":[{"message":{"role":"assistant","content":"在呢"}}]}""".toByteArray()
        assertEquals("在呢", DebugCompanionChat(FakeStream(200, listOf(whole))).reply(endpoint, "k", emptyList()))
        assertNull(streamedText("data: [DONE]"))
    }

    @Test
    fun eachServiceKeepsItsOwnFieldsAndQwenFallsBackToTheDashscopeKey() {
        val doubao = DebugCompanionEndpoint("https://ark.cn-beijing.volces.com/api/v3", "ep-123", "")
        val config = loadDebugCompanionConfig(context).let { it.copy(enabled = true, service = DebugCompanionService.DOUBAO, endpoints = it.endpoints + (DebugCompanionService.DOUBAO to doubao)) }
        assertNull(saveDebugCompanionConfig(context, config, key = "ark-key"))
        assertNull(saveDebugCompanionConfig(context, loadDebugCompanionConfig(context).copy(service = DebugCompanionService.HUNYUAN)))

        val back = loadDebugCompanionConfig(context).copy(service = DebugCompanionService.DOUBAO)
        assertEquals(doubao, back.endpoint())
        assertEquals("ark-key", DebugCompanionKeys.read(context, DebugCompanionService.DOUBAO))
        assertEquals("qwen3.8-omni-flash", back.endpoint(DebugCompanionService.QWEN).model)
        context.noBackupFilesDir.mkdirs()
        java.io.File(context.noBackupFilesDir, "debug_voice_dashscope_key").writeText("sk-ds")
        assertEquals("Qwen without its own key uses the 百炼 key", "sk-ds", DebugCompanionKeys.read(context, DebugCompanionService.QWEN))
    }

    @Test
    fun anIncompleteServiceCanBePickedWhileTheCompanionIsOnAndSaysWhatIsMissing() = runBlocking {
        val on = DebugCompanionConfig(enabled = true, mode = DebugCompanionMode.ROUTE, service = DebugCompanionService.DOUBAO)

        assertNull("picking 豆包 before its model is filled in is allowed", saveDebugCompanionConfig(context, on))
        assertEquals(DebugCompanionService.DOUBAO, loadDebugCompanionConfig(context).service)
        assertEquals("豆包还没填模型", loadDebugCompanionConfig(context).readinessError())
        try {
            DebugCompanion.ask(context, loadDebugCompanionConfig(context), "你好")
            fail("an incomplete service is not asked")
        } catch (error: IllegalStateException) {
            assertEquals("豆包还没填模型", error.message)
        }
    }

    @Test
    fun badSettingsAreRefusedWithAReason() {
        val base = DebugCompanionConfig()
        assertTrue(saveDebugCompanionConfig(context, base.copy(threshold = 0.2))!!.contains("灵敏度"))
        val http = base.endpoints + (DebugCompanionService.QWEN to DebugCompanionEndpoint("http://chat.test/v1", "m", ""))
        assertTrue(saveDebugCompanionConfig(context, base.copy(endpoints = http))!!.contains("https://"))
        val json = base.endpoints + (DebugCompanionService.QWEN to DebugCompanionEndpoint("https://chat.test/v1", "m", "{oops"))
        assertTrue(saveDebugCompanionConfig(context, base.copy(endpoints = json))!!.contains("JSON"))
        assertTrue(saveDebugCompanionConfig(context, base, key = "two words")!!.contains("空白"))
    }

    @Test
    fun anApkWithCompanionKeysFillsTheSettingsOnce() {
        val embedded = mapOf("COMPANION_SERVICE" to "hunyuan", "COMPANION_HUNYUAN_API_KEY" to "hy-key", "COMPANION_HUNYUAN_MODEL" to "hunyuan-role")
        seedEmbeddedCompanion(context) { embedded }
        seedEmbeddedCompanion(context) { mapOf("COMPANION_SERVICE" to "doubao") }

        val config = loadDebugCompanionConfig(context)
        assertEquals(DebugCompanionService.HUNYUAN, config.service)
        assertEquals("hunyuan-role", config.endpoint().model)
        assertEquals("hy-key", DebugCompanionKeys.read(context, DebugCompanionService.HUNYUAN))
        assertFalse("seeding never switches the companion on", config.enabled)
    }
}
