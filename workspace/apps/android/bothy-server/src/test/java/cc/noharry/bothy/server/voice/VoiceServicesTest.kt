package cc.noharry.bothy.server.voice
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.bothy.protocol.BothyFindingCode
import cc.noharry.bothy.protocol.VoiceEngine
import cc.noharry.bothy.server.console.markEmbeddedSeedsDone
import cc.noharry.bothy.server.voice.realtime.loadRealtimeConfig
import cc.noharry.bothy.server.voice.realtime.saveRealtimeConfig
import cc.noharry.bothy.server.voice.tts.MIMO_TTS_API_KEY
import cc.noharry.bothy.server.voice.tts.MIMO_TTS_ENABLED
import cc.noharry.bothy.server.voice.tts.MIMO_TTS_MODEL
import cc.noharry.bothy.server.voice.tts.MIMO_TTS_VOICE
import cc.noharry.bothy.server.voice.tts.MimoTtsVoice
import cc.noharry.bothy.server.voice.tts.TTS_PROVIDER
import cc.noharry.bothy.server.voice.tts.TtsConfig
import cc.noharry.bothy.server.voice.tts.clearTtsKey
import cc.noharry.bothy.server.voice.tts.saveTtsChoice
import cc.noharry.bothy.server.voice.tts.saveTtsKey
import cc.noharry.bothy.server.voice.tts.saveTtsSettings
import cc.noharry.bothy.server.voice.tts.savedTtsValues
import cc.noharry.bothy.server.voice.tts.validationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings → 语音 (密钥, 判断与打断, 播报): keys seeded from local.properties, the Jev connection split and the saved TTS settings. */
@RunWith(RobolectricTestRunner::class)
class VoiceServicesTest {
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
        val seeded = loadJevConfig(context)
        assertTrue(seeded.hasApiKey)
        assertEquals("jev-2.0.0", seeded.model)
        assertEquals(JevConfig().endpoint, seeded.endpoint)
        // Tool search stays off; only the voice judge needs the connection.
        assertFalse(seeded.enabled)

        assertNull(saveJevConnection(context, seeded.endpoint, seeded.model, seeded.timeoutMillis, clearKey = true))
        seedEmbeddedJev(context) { embedded }
        assertEquals("", loadJevKey(context))
    }

    @Test
    fun anEmbeddedJevKeyNeverReplacesAStoredOne() {
        markSeeded()
        assertNull(saveJevConnection(context, "https://jev.example.com/v1", "mine", 4_000, replacementKey = "my-key"))
        context.getSharedPreferences("debug_jev_config", Context.MODE_PRIVATE).edit().remove("embedded_seeded").commit()
        seedEmbeddedJev(context) { mapOf("JEV_API_KEY" to "jev-embedded", "JEV_MODEL" to "other") }
        assertEquals("my-key", loadJevKey(context))
        assertEquals("mine", loadJevConfig(context).model)
    }

    @Test
    fun anApkWithoutTheKeysSeedsNothingAndLeavesNoMark() {
        seedEmbeddedJev(context) { mapOf("JEV_MODEL" to "jev-2.0.0") }
        seedEmbeddedVoice(context) { emptyMap() }
        assertEquals("", loadJevKey(context))
        assertEquals("", loadDashscopeKey(context))
        assertFalse(context.getSharedPreferences("debug_jev_config", Context.MODE_PRIVATE).contains("embedded_seeded"))
        assertFalse(context.getSharedPreferences("debug_voice", Context.MODE_PRIVATE).contains("embedded_seeded"))
    }

    @Test
    fun anEmbeddedDashScopeKeyPicksBailianOnlyWhenNoEngineWasChosen() {
        seedEmbeddedVoice(context) { mapOf("DASHSCOPE_API_KEY" to "sk-embedded") }
        val seeded = loadVoiceConfig(context)
        assertTrue(seeded.hasDashscopeKey)
        assertEquals(VoiceEngine.DASHSCOPE, seeded.engine)

        // Back to local and the key removed: neither comes back.
        assertNull(saveVoiceConfig(context, seeded.copy(engine = VoiceEngine.LOCAL), clearKey = true))
        seedEmbeddedVoice(context) { mapOf("DASHSCOPE_API_KEY" to "sk-embedded") }
        assertEquals(VoiceEngine.LOCAL, loadVoiceConfig(context).engine)
        assertEquals("", loadDashscopeKey(context))
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
        val config = loadVoiceConfig(context)
        assertEquals(VoiceEngine.DASHSCOPE, config.engine)
        assertEquals("qwen-audio-3.0-asr-flash-streaming", config.dashscopeModel)
        assertEquals("wss://example.com/ws", config.dashscopeEndpoint)
        assertEquals(listOf("我的词"), config.hotwords)
    }

    @Test
    fun theNearGateIsOnByDefaultAndSavesWhenSwitched() {
        markSeeded()
        assertTrue(loadVoiceConfig(context).nearGate)

        assertNull(saveVoiceConfig(context, loadVoiceConfig(context).copy(nearGate = false)))

        assertFalse(loadVoiceConfig(context).nearGate)
    }

    @Test
    fun overlayWakeCanBeSwitchedOnFromTheBuild() {
        voicePrefs().edit().clear().commit()
        seedEmbeddedVoice(context) { mapOf("VOICE_OVERLAY_WAKE" to "on") }
        assertTrue(loadVoiceConfig(context).overlayWake)
    }

    @Test
    fun aSeededBuildWithoutAPackagedWakeStillTurnsAnOldWakeOff() {
        voicePrefs().edit().clear().putBoolean("embedded_seeded", true).putBoolean("overlay_wake", true).commit()
        assertFalse(loadVoiceConfig(context, NO_PACKAGED_WAKE).overlayWake)
        assertTrue(wakeMigrationNotice(context))
    }

    @Test
    fun anOldWakeThatWasNeverToggledIsTurnedOffOnce() {
        voicePrefs().edit().clear().putBoolean("overlay_wake", true).commit()
        assertFalse(loadVoiceConfig(context, NO_PACKAGED_WAKE).overlayWake)
        assertTrue(wakeMigrationNotice(context))
        assertFalse(loadVoiceConfig(context, NO_PACKAGED_WAKE).overlayWake)
    }

    @Test
    fun aWakeChoiceSavedInSettingsIsKept() {
        voicePrefs().edit().clear().commit()
        assertTrue(saveOverlayWake(context, true))
        assertTrue(loadVoiceConfig(context).overlayWake)
        assertFalse(wakeMigrationNotice(context))
    }

    @Test
    fun anUpgradeThatAlreadySeededPackagedWakeKeepsItOn() {
        voicePrefs().edit().clear()
            .putBoolean("embedded_seeded", true)
            .putBoolean("overlay_wake", true)
            .commit()
        assertTrue(loadVoiceConfig(context) { mapOf("VOICE_OVERLAY_WAKE" to "on") }.overlayWake)
        assertFalse(wakeMigrationNotice(context))
        assertTrue(voicePrefs().getBoolean("overlay_wake_from_build", false))
    }

    @Test
    fun aMistakenMigrationOfPackagedWakeIsRestoredWhileTheNoticeIsUp() {
        voicePrefs().edit().clear()
            .putBoolean("embedded_seeded", true)
            .putBoolean("overlay_wake", false)
            .putBoolean("overlay_wake_default_migrated", true)
            .putBoolean("overlay_wake_migration_notice", true)
            .commit()
        assertTrue(loadVoiceConfig(context) { mapOf("VOICE_OVERLAY_WAKE" to "on") }.overlayWake)
        assertFalse(wakeMigrationNotice(context))
        assertTrue(voicePrefs().getBoolean("overlay_wake_from_build", false))
    }

    @Test
    fun aDismissedWakeNoticeStaysOff() {
        voicePrefs().edit().clear()
            .putBoolean("embedded_seeded", true)
            .putBoolean("overlay_wake", false)
            .putBoolean("overlay_wake_default_migrated", true)
            .commit()
        assertFalse(loadVoiceConfig(context) { mapOf("VOICE_OVERLAY_WAKE" to "on") }.overlayWake)
        assertFalse(wakeMigrationNotice(context))
    }

    @Test
    fun aSeededBuildWhosePackageTurnsWakeOffStillMigrates() {
        voicePrefs().edit().clear().putBoolean("embedded_seeded", true).putBoolean("overlay_wake", true).commit()
        assertFalse(loadVoiceConfig(context) { mapOf("VOICE_OVERLAY_WAKE" to "off") }.overlayWake)
        assertTrue(wakeMigrationNotice(context))
    }

    @Test
    fun anEndToEndConversationIsTappedEvenOnAPortraitPhone() {
        assertTrue(VoiceController.holdsToTalk(context))
        saveRealtimeConfig(context, loadRealtimeConfig(context).copy(enabled = true))
        assertFalse(VoiceController.holdsToTalk(context))
    }

    @Test
    fun bailianIsNotPickedWithoutAKey() {
        seedEmbeddedVoice(context) { mapOf("VOICE_ASR_ENGINE" to "dashscope", "DASHSCOPE_ASR_MODEL" to "not-a-model") }
        val config = loadVoiceConfig(context)
        assertEquals(VoiceEngine.LOCAL, config.engine)
        assertEquals("paraformer-realtime-v2", config.dashscopeModel)
    }

    @Test
    fun aChosenEngineIsKeptWhenTheEmbeddedKeyArrives() {
        context.getSharedPreferences("debug_voice", Context.MODE_PRIVATE).edit().putString("engine", VoiceEngine.LOCAL.wireName).commit()
        seedEmbeddedVoice(context) { mapOf("DASHSCOPE_API_KEY" to "sk-embedded") }
        assertEquals("sk-embedded", loadDashscopeKey(context))
        assertEquals(VoiceEngine.LOCAL, loadVoiceConfig(context).engine)
    }

    @Test
    fun connectionAndToolSearchSaveWithoutTouchingEachOther() {
        markSeeded()
        assertNull(saveJevConnection(context, "https://jev.example.com/v1", "jev-a", 3_000, replacementKey = "k"))
        val search = loadJevConfig(context).copy(enabled = true, maxTools = 8, endpoint = "https://ignored.example.com", model = "ignored")
        assertNull(saveJevToolSearch(context, search))
        val afterSearch = loadJevConfig(context)
        assertEquals("jev-a", afterSearch.model)
        assertEquals("https://jev.example.com/v1", afterSearch.endpoint)
        assertTrue(afterSearch.enabled)
        assertEquals(8, afterSearch.maxTools)

        assertNull(saveJevConnection(context, afterSearch.endpoint, "jev-b", 3_000))
        assertEquals(8, loadJevConfig(context).maxTools)
        assertTrue(loadJevConfig(context).enabled)
        // Without a key, tool search cannot run, so removing it turns tool search off.
        assertNull(saveJevConnection(context, afterSearch.endpoint, "jev-b", 3_000, clearKey = true))
        assertFalse(loadJevConfig(context).enabled)
    }

    @Test
    @Config(qualifiers = "zh")
    fun toolSearchCannotBeTurnedOnWithoutTheSharedKey() {
        markSeeded()
        val error = saveJevToolSearch(context, loadJevConfig(context).copy(enabled = true))
        assertEquals(BothyFindingCode.VOICE_JEV_KEY_NEEDED_FOR_SEARCH.name, error?.code)
    }

    @Test
    fun savedTtsSettingsWinAndTheKeyIsKeptApart() {
        val mine = TtsConfig.from(emptyMap()).copy(model = "mimo-v3-tts", style = "慢一点")
        assertNull(saveTtsSettings(context, mine))
        assertNull(saveTtsKey(context, "mimo-key"))
        val values = savedTtsValues(context)
        assertEquals("mimo-key", values[MIMO_TTS_API_KEY])
        val layered = TtsConfig.from(mapOf(MIMO_TTS_MODEL to "packaged-model", MIMO_TTS_API_KEY to "packaged") + values)
        assertEquals("mimo-v3-tts", layered.model)
        assertEquals("慢一点", layered.style)
        // Saving typed values never writes 播报回复: with a key it is on unless switched off by hand.
        assertTrue(layered.enabled && layered.ready)
        assertFalse(values.containsKey(MIMO_TTS_ENABLED))
        assertFalse(context.getSharedPreferences("debug_tts", Context.MODE_PRIVATE).all.values.any { it == "mimo-key" })

        assertTrue(clearTtsKey(context))
        assertNull(savedTtsValues(context)[MIMO_TTS_API_KEY])
        assertEquals("packaged", TtsConfig.from(mapOf(MIMO_TTS_API_KEY to "packaged") + savedTtsValues(context)).apiKey)
    }

    @Test
    fun ttsSettingsAreCheckedBeforeSaving() {
        val base = TtsConfig.from(emptyMap())
        assertNull(base.validationError())
        assertNull(base.copy(baseUrl = "http://127.0.0.1:18080/v1").validationError())
        assertNotNull(base.copy(baseUrl = "http://api.example.com/v1").validationError())
        assertNotNull(base.copy(baseUrl = "https://api.example.com/v1?key=x").validationError())
        assertNotNull(base.copy(model = "  ").validationError())
        assertNotNull(base.copy(voice = "").validationError())
        assertNotNull(saveTtsKey(context, "has space"))
        assertNotNull(saveTtsKey(context, " "))
    }

    @Test
    fun aTtsChoiceSavesOnlyItselfAndTypedValuesStayPackaged() {
        assertTrue(saveTtsChoice(context, MIMO_TTS_VOICE, "苏打"))
        assertEquals(mapOf(MIMO_TTS_VOICE to "苏打"), savedTtsValues(context))
        val layered = TtsConfig.from(mapOf(MIMO_TTS_MODEL to "packaged-model") + savedTtsValues(context))
        assertEquals("苏打", layered.voice)
        assertEquals("packaged-model", layered.model)
        assertTrue(runCatching { saveTtsChoice(context, MIMO_TTS_MODEL, "typed") }.isFailure)
    }

    @Test
    fun thePresetVoicesIncludeTheDefaultOnce() {
        val ids = MimoTtsVoice.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(TtsConfig.from(emptyMap()).voice in ids)
    }

    @Test
    @Config(qualifiers = "zh")
    fun ttsStatusSaysWhatIsMissing() {
        // MiMo chosen by hand, before its key.
        val base = TtsConfig.from(mapOf(TTS_PROVIDER to "mimo"))
        assertEquals(BothyFindingCode.VOICE_MIMO_KEY_MISSING_STATUS.name, ttsStatus(context, base).code)
        assertEquals(BothyFindingCode.VOICE_READY_DETAIL.name, ttsStatus(context, base.copy(enabled = true, apiKey = "k")).code)
        assertEquals(BothyFindingCode.VOICE_OFF.name, ttsStatus(context, base.copy(enabled = false, apiKey = "k")).code)
    }

    private fun voicePrefs() = context.getSharedPreferences("debug_voice", Context.MODE_PRIVATE)
}

/** A released build: a local one may package VOICE_OVERLAY_WAKE=on, which keeps the wake word on by design. */
private val NO_PACKAGED_WAKE: () -> Map<String, String> = { emptyMap() }
