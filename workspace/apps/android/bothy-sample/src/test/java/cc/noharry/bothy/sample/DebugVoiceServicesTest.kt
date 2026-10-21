package cc.noharry.bothy.sample

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Settings → 语音 (密钥, 判断与打断, 播报): keys seeded from local.properties, the Jev connection split and the saved TTS settings. */
@RunWith(RobolectricTestRunner::class)
class DebugVoiceServicesTest {
    private lateinit var context: Context

    @Before
    fun start() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun markSeeded() = markEmbeddedSeedsDone(context)

    @Test
    fun anEmbeddedJevKeyConnectsOnceAndLaterEditsWin() {
        val embedded = mapOf("JEV_API_KEY" to "jev-embedded", "JEV_MODEL" to "jev-2.0.0")
        seedEmbeddedJev(context) { embedded }
        val seeded = loadDebugJevConfig(context)
        assertTrue(seeded.hasApiKey)
        assertEquals("jev-2.0.0", seeded.model)
        assertEquals(DebugJevConfig().endpoint, seeded.endpoint)
        // Tool search stays off; only the voice judge needs the connection.
        assertFalse(seeded.enabled)

        assertNull(saveDebugJevConnection(context, seeded.endpoint, seeded.model, seeded.timeoutMillis, clearKey = true))
        seedEmbeddedJev(context) { embedded }
        assertEquals("", loadDebugJevKey(context))
    }

    @Test
    fun anEmbeddedJevKeyNeverReplacesAStoredOne() {
        markSeeded()
        assertNull(saveDebugJevConnection(context, "https://jev.example.com/v1", "mine", 4_000, replacementKey = "my-key"))
        context.getSharedPreferences("debug_jev_config", Context.MODE_PRIVATE).edit().remove("embedded_seeded").commit()
        seedEmbeddedJev(context) { mapOf("JEV_API_KEY" to "jev-embedded", "JEV_MODEL" to "other") }
        assertEquals("my-key", loadDebugJevKey(context))
        assertEquals("mine", loadDebugJevConfig(context).model)
    }

    @Test
    fun anApkWithoutTheKeysSeedsNothingAndLeavesNoMark() {
        seedEmbeddedJev(context) { mapOf("JEV_MODEL" to "jev-2.0.0") }
        seedEmbeddedVoice(context) { emptyMap() }
        assertEquals("", loadDebugJevKey(context))
        assertEquals("", loadDebugDashscopeKey(context))
        assertFalse(context.getSharedPreferences("debug_jev_config", Context.MODE_PRIVATE).contains("embedded_seeded"))
        assertFalse(context.getSharedPreferences("debug_voice", Context.MODE_PRIVATE).contains("embedded_seeded"))
    }

    @Test
    fun anEmbeddedDashScopeKeyPicksBailianOnlyWhenNoEngineWasChosen() {
        seedEmbeddedVoice(context) { mapOf("DASHSCOPE_API_KEY" to "sk-embedded") }
        val seeded = loadDebugVoiceConfig(context)
        assertTrue(seeded.hasDashscopeKey)
        assertEquals(DebugVoiceEngine.DASHSCOPE, seeded.engine)

        // Back to local and the key removed: neither comes back.
        assertNull(saveDebugVoiceConfig(context, seeded.copy(engine = DebugVoiceEngine.LOCAL), clearKey = true))
        seedEmbeddedVoice(context) { mapOf("DASHSCOPE_API_KEY" to "sk-embedded") }
        assertEquals(DebugVoiceEngine.LOCAL, loadDebugVoiceConfig(context).engine)
        assertEquals("", loadDebugDashscopeKey(context))
    }

    @Test
    fun embeddedRecognitionSettingsFillOnlyWhatIsUnset() {
        context.getSharedPreferences("debug_voice", Context.MODE_PRIVATE).edit().putString("hotwords", "我的词").commit()
        seedEmbeddedVoice(context) {
            mapOf(
                "VOICE_ASR_ENGINE" to "dashscope",
                "DASHSCOPE_API_KEY" to "sk-embedded",
                "DASHSCOPE_ASR_MODEL" to "qwen-audio-3.0-asr-flash-streaming",
                "DASHSCOPE_ASR_ENDPOINT" to "wss://example.com/ws",
                "VOICE_HOTWORDS" to "小博，Grok Bot",
            )
        }
        val config = loadDebugVoiceConfig(context)
        assertEquals(DebugVoiceEngine.DASHSCOPE, config.engine)
        assertEquals("qwen-audio-3.0-asr-flash-streaming", config.dashscopeModel)
        assertEquals("wss://example.com/ws", config.dashscopeEndpoint)
        assertEquals(listOf("我的词"), config.hotwords)
    }

    @Test
    fun theNearGateIsOnByDefaultAndSavesWhenSwitched() {
        markSeeded()
        assertTrue(loadDebugVoiceConfig(context).nearGate)

        assertNull(saveDebugVoiceConfig(context, loadDebugVoiceConfig(context).copy(nearGate = false)))

        assertFalse(loadDebugVoiceConfig(context).nearGate)
    }

    @Test
    fun overlayWakeCanBeSwitchedOnFromTheBuild() {
        seedEmbeddedVoice(context) { mapOf("VOICE_OVERLAY_WAKE" to "on") }
        assertTrue(loadDebugVoiceConfig(context).overlayWake)
    }

    @Test
    fun bailianIsNotPickedWithoutAKey() {
        seedEmbeddedVoice(context) { mapOf("VOICE_ASR_ENGINE" to "dashscope", "DASHSCOPE_ASR_MODEL" to "not-a-model") }
        val config = loadDebugVoiceConfig(context)
        assertEquals(DebugVoiceEngine.LOCAL, config.engine)
        assertEquals("paraformer-realtime-v2", config.dashscopeModel)
    }

    @Test
    fun aChosenEngineIsKeptWhenTheEmbeddedKeyArrives() {
        context.getSharedPreferences("debug_voice", Context.MODE_PRIVATE).edit().putString("engine", DebugVoiceEngine.LOCAL.wireName).commit()
        seedEmbeddedVoice(context) { mapOf("DASHSCOPE_API_KEY" to "sk-embedded") }
        assertEquals("sk-embedded", loadDebugDashscopeKey(context))
        assertEquals(DebugVoiceEngine.LOCAL, loadDebugVoiceConfig(context).engine)
    }

    @Test
    fun connectionAndToolSearchSaveWithoutTouchingEachOther() {
        markSeeded()
        assertNull(saveDebugJevConnection(context, "https://jev.example.com/v1", "jev-a", 3_000, replacementKey = "k"))
        val search = loadDebugJevConfig(context).copy(enabled = true, maxTools = 8, endpoint = "https://ignored.example.com", model = "ignored")
        assertNull(saveDebugJevToolSearch(context, search))
        val afterSearch = loadDebugJevConfig(context)
        assertEquals("jev-a", afterSearch.model)
        assertEquals("https://jev.example.com/v1", afterSearch.endpoint)
        assertTrue(afterSearch.enabled)
        assertEquals(8, afterSearch.maxTools)

        assertNull(saveDebugJevConnection(context, afterSearch.endpoint, "jev-b", 3_000))
        assertEquals(8, loadDebugJevConfig(context).maxTools)
        assertTrue(loadDebugJevConfig(context).enabled)
        // Without a key, tool search cannot run, so removing it turns tool search off.
        assertNull(saveDebugJevConnection(context, afterSearch.endpoint, "jev-b", 3_000, clearKey = true))
        assertFalse(loadDebugJevConfig(context).enabled)
    }

    @Test
    fun toolSearchCannotBeTurnedOnWithoutTheSharedKey() {
        markSeeded()
        val error = saveDebugJevToolSearch(context, loadDebugJevConfig(context).copy(enabled = true))
        assertTrue(error.orEmpty().contains("语音"))
    }

    @Test
    fun savedTtsSettingsWinAndTheKeyIsKeptApart() {
        val mine = DebugTtsConfig.from(emptyMap()).copy(model = "mimo-v3-tts", style = "慢一点")
        assertNull(saveDebugTtsSettings(context, mine))
        assertNull(saveDebugTtsKey(context, "mimo-key"))
        val values = savedTtsValues(context)
        assertEquals("mimo-key", values[MIMO_TTS_API_KEY])
        val layered = DebugTtsConfig.from(mapOf(MIMO_TTS_MODEL to "packaged-model", MIMO_TTS_API_KEY to "packaged") + values)
        assertEquals("mimo-v3-tts", layered.model)
        assertEquals("慢一点", layered.style)
        // Saving typed values never writes 播报回复: with a key it is on unless switched off by hand.
        assertTrue(layered.enabled && layered.ready)
        assertFalse(values.containsKey(MIMO_TTS_ENABLED))
        assertFalse(context.getSharedPreferences("debug_tts", Context.MODE_PRIVATE).all.values.any { it == "mimo-key" })

        assertTrue(clearDebugTtsKey(context))
        assertNull(savedTtsValues(context)[MIMO_TTS_API_KEY])
        assertEquals("packaged", DebugTtsConfig.from(mapOf(MIMO_TTS_API_KEY to "packaged") + savedTtsValues(context)).apiKey)
    }

    @Test
    fun ttsSettingsAreCheckedBeforeSaving() {
        val base = DebugTtsConfig.from(emptyMap())
        assertNull(base.validationError())
        assertNull(base.copy(baseUrl = "http://127.0.0.1:18080/v1").validationError())
        assertNotNull(base.copy(baseUrl = "http://api.example.com/v1").validationError())
        assertNotNull(base.copy(baseUrl = "https://api.example.com/v1?key=x").validationError())
        assertNotNull(base.copy(model = "  ").validationError())
        assertNotNull(base.copy(voice = "").validationError())
        assertNotNull(saveDebugTtsKey(context, "has space"))
        assertNotNull(saveDebugTtsKey(context, " "))
    }

    @Test
    fun aTtsChoiceSavesOnlyItselfAndTypedValuesStayPackaged() {
        assertTrue(saveDebugTtsChoice(context, MIMO_TTS_VOICE, "苏打"))
        assertEquals(mapOf(MIMO_TTS_VOICE to "苏打"), savedTtsValues(context))
        val layered = DebugTtsConfig.from(mapOf(MIMO_TTS_MODEL to "packaged-model") + savedTtsValues(context))
        assertEquals("苏打", layered.voice)
        assertEquals("packaged-model", layered.model)
        assertTrue(runCatching { saveDebugTtsChoice(context, MIMO_TTS_MODEL, "typed") }.isFailure)
    }

    @Test
    fun thePresetVoicesIncludeTheDefaultOnce() {
        val ids = MimoTtsVoice.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(DebugTtsConfig.from(emptyMap()).voice in ids)
    }

    @Test
    fun ttsStatusSaysWhatIsMissing() {
        val base = DebugTtsConfig.from(emptyMap())
        assertTrue(ttsStatusLine(base).contains("密钥"))
        assertTrue(ttsStatusLine(base.copy(enabled = true, apiKey = "k")).startsWith("可用"))
        assertEquals("已关闭", ttsStatusLine(base.copy(enabled = false, apiKey = "k")))
    }
}
