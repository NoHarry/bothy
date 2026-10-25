package cc.noharry.bothy.sample

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.transport.HttpEvent
import cc.noharry.agent.transport.HttpRequest
import cc.noharry.agent.transport.HttpResponse
import cc.noharry.agent.transport.HttpTransport
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 豆包语音合成 as 播报: the V3 one-way stream, its errors, both ways to sign in, and the provider switch. */
@RunWith(RobolectricTestRunner::class)
class DebugDoubaoTtsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

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

    private val pcmA = ByteArray(480) { it.toByte() }
    private val pcmB = ByteArray(320) { (it * 3).toByte() }
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private val config = DebugTtsConfig(
        enabled = true,
        provider = DebugTtsProvider.DOUBAO,
        doubao = DoubaoTtsSettings(key = "ark-key", voice = DoubaoTtsVoice.XIAOHE.id),
    )

    @Test
    fun framesCutAnywhereBecomeAudioInOrderUntilTheFinishCode() = runBlocking {
        val reply = "{\"code\":0,\"message\":\"\",\"data\":\"${b64(pcmA)}\"}\n" +
            "{\"code\":0,\"message\":\"\",\"data\":null,\"sentence\":{\"text\":\"你好，我是小博\"}}\n" +
            "{\"code\":0,\"message\":\"\",\"data\":\"${b64(pcmB)}\"}\n{\"code\":20000000,\"message\":\"OK\",\"data\":null}\n"
        val bytes = reply.toByteArray()
        val cuts = listOf(7, reply.indexOf("你").let { reply.substring(0, it).toByteArray().size + 1 }, bytes.size - 9)
        val chunks = (listOf(0) + cuts + bytes.size).zipWithNext { from, to -> bytes.copyOfRange(from, to) }
        val server = FakeStream(200, chunks)

        val audio = debugTtsClient(server).stream(config, "你好").toList()

        assertEquals(2, audio.size)
        assertArrayEquals(pcmA, audio[0].bytes)
        assertArrayEquals(pcmB, audio[1].bytes)
        assertEquals("pcm16", audio[0].format)
        assertEquals(DOUBAO_TTS_URL, server.request!!.url)
    }

    @Test
    fun anErrorFrameOrARefusalSaysWhatToFix() = runBlocking {
        val mismatch = FakeStream(200, listOf("{\"code\":55000000,\"message\":\"resource ID is mismatched\"}".toByteArray()))
        val refused = FakeStream(401, listOf("{\"header\":{\"code\":40300001,\"message\":\"auth failed\"}}".toByteArray()))
        for ((server, expected) in listOf(mismatch to "seed-tts-2.0", refused to "Access Token")) {
            try {
                DebugDoubaoTtsClient(server).stream(config, "你好").toList()
                fail("an error code is not audio")
            } catch (error: IllegalStateException) {
                assertTrue(error.message!!, error.message!!.contains(expected))
            }
        }
    }

    @Test
    fun theApiKeyAndTheAppIdPairSignInDifferently() = runBlocking {
        val done = listOf("{\"code\":20000000,\"message\":\"OK\"}".toByteArray())
        val keyServer = FakeStream(200, done)
        DebugDoubaoTtsClient(keyServer).stream(config.copy(doubao = config.doubao.copy(speechRate = 25)), "你好").toList()
        val keyHeaders = keyServer.request!!.headers
        assertEquals("ark-key", keyHeaders["X-Api-Key"])
        assertEquals("seed-tts-2.0", keyHeaders["X-Api-Resource-Id"])
        assertTrue(keyHeaders["X-Api-Request-Id"]!!.isNotBlank())
        assertNull(keyHeaders["X-Api-App-Id"])
        val body = Json.parseToJsonElement(keyServer.request!!.body!!.toString(Charsets.UTF_8)).jsonObject
        val params = body["req_params"]!!.jsonObject
        assertEquals(DoubaoTtsVoice.XIAOHE.id, params["speaker"]!!.jsonPrimitive.content)
        assertEquals("pcm", params["audio_params"]!!.jsonObject["format"]!!.jsonPrimitive.content)
        assertEquals("24000", params["audio_params"]!!.jsonObject["sample_rate"]!!.jsonPrimitive.content)
        assertEquals("25", params["audio_params"]!!.jsonObject["speech_rate"]!!.jsonPrimitive.content)

        val pairServer = FakeStream(200, done)
        DebugDoubaoTtsClient(pairServer).stream(config.copy(doubao = config.doubao.copy(appId = "123456", key = "token")), "你好").toList()
        val pairHeaders = pairServer.request!!.headers
        assertEquals("123456", pairHeaders["X-Api-App-Id"])
        assertEquals("token", pairHeaders["X-Api-Access-Key"])
        assertNull(pairHeaders["X-Api-Key"])
    }

    @Test
    fun aWholeClipIsWrappedAsWav() = runBlocking {
        val server = FakeStream(200, listOf("{\"code\":0,\"data\":\"${b64(pcmA)}\"}{\"code\":20000000}".toByteArray()))

        val clip = DebugDoubaoTtsClient(server).synthesize(config.copy(format = "wav"), "在呢")

        assertEquals("wav", clip.format)
        assertEquals("RIFF", String(clip.bytes.copyOfRange(0, 4)))
        assertEquals(44 + pcmA.size, clip.bytes.size)
    }

    @Test
    fun theProviderSwitchPicksDoubaoAndItsKeyMakesItReady() {
        val values = mapOf(TTS_PROVIDER to "doubao", DOUBAO_TTS_API_KEY to "k", DOUBAO_TTS_VOICE to DoubaoTtsVoice.YUNZHOU.id)
        val tts = DebugTtsConfig.from(values)

        assertEquals(DebugTtsProvider.DOUBAO, tts.provider)
        assertTrue("on by default once its key is there", tts.enabled && tts.ready)
        assertEquals("豆包 · seed-tts-2.0 · 云舟 · 通用男声", tts.label)
        assertFalse(DebugTtsConfig.from(mapOf(TTS_PROVIDER to "doubao")).ready)
        // Nothing chosen and no key: the device's own voice (owner 2026-10-25).
        assertEquals(DebugTtsProvider.SYSTEM, DebugTtsConfig.from(emptyMap()).provider)
        assertEquals("语速要在 -50 到 100 之间。", DoubaoTtsSettings(speechRate = 200).validationError())
    }

    @Test
    fun aSavedSwitchToDoubaoIsAmongTheValuesTheRunningSessionRefreshes() {
        assertTrue(TTS_PROVIDER in TTS_CONFIG_KEYS && DOUBAO_TTS_VOICE in TTS_CONFIG_KEYS && DOUBAO_TTS_API_KEY in TTS_CONFIG_KEYS)
        assertTrue(MIMO_TTS_VOICE in TTS_CONFIG_KEYS)
    }

    @Test
    fun theKeyIsKeptOnTheDeviceAndJoinsTheSavedValues() {
        assertNull(saveDebugDoubaoTtsKey(context, "volc-key"))
        assertTrue(saveDebugTtsChoice(context, TTS_PROVIDER, "doubao"))

        val values = savedTtsValues(context)
        assertEquals("volc-key", values[DOUBAO_TTS_API_KEY])
        assertEquals("doubao", values[TTS_PROVIDER])
        assertTrue(clearDebugDoubaoTtsKey(context))
        assertNull(savedTtsValues(context)[DOUBAO_TTS_API_KEY])
    }
}
