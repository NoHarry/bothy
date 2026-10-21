package cc.noharry.bothy.sample

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.bothy.voice.DashScopeVocabularyClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DebugHotwordsTest {
    private lateinit var context: Context
    private val server = MockWebServer()
    private val model = "qwen-audio-3.0-asr-flash-streaming"

    @Before
    fun start() {
        context = ApplicationProvider.getApplicationContext()
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private fun vocabulary(words: List<String>) = debugDashScopeVocabulary(context, model, "key", words) {
        DashScopeVocabularyClient(it, endpoint = server.url("/customization").toString())
    }

    private fun ok(output: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"output":$output}""")

    @Test
    fun theListIsCreatedOnceAndUpdatedOnlyWhenTheWordsChange() {
        server.enqueue(ok("""{"vocabulary_id":"vocab-agentvoice-1"}"""))
        assertEquals("vocab-agentvoice-1", vocabulary(listOf("小博", "你好小博")))
        val create = server.takeRequest().body.readUtf8()
        assertTrue(create.contains("create_vocabulary") && create.contains(model) && create.contains("小博") && create.contains("speech-biasing"))

        assertEquals("vocab-agentvoice-1", vocabulary(listOf("小博", "你好小博")))
        assertEquals(1, server.requestCount)

        server.enqueue(ok("{}"))
        assertEquals("vocab-agentvoice-1", vocabulary(listOf("小博", "你好小博", "高德地图")))
        assertTrue(server.takeRequest().body.readUtf8().contains("update_vocabulary"))
    }

    @Test
    fun aListThatCannotBeUpdatedIsReplacedAndNoWordsMeansNoList() {
        server.enqueue(ok("""{"vocabulary_id":"vocab-agentvoice-1"}"""))
        vocabulary(listOf("小博"))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"vocabulary not found"}"""))
        server.enqueue(ok("""{"vocabulary_id":"vocab-agentvoice-2"}"""))
        assertEquals("vocab-agentvoice-2", vocabulary(listOf("你好小博")))
        assertNull(vocabulary(emptyList()))
    }

    @Test
    fun hotwordsAreTypedWithAnySeparator() {
        assertEquals(listOf("小博", "你好小博", "高德地图"), parseHotwords("小博，你好小博、高德地图\n小博"))
        assertEquals(DEFAULT_HOTWORDS, DebugVoiceConfig().hotwords)
        assertTrue(DebugVoiceConfig(hotwords = listOf("一二三四五六七八九十一二三四五六")).validationError()!!.contains("15"))
    }
}
