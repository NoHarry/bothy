package cc.noharry.bothy.server.server

import android.content.Context
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FindingKind
import cc.noharry.agent.appserver.protocol.FlagParams
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.lines
import cc.noharry.agent.appserver.runtime.RuntimeSession
import cc.noharry.agent.appserver.server.RouteModule
import cc.noharry.agent.appserver.server.RouteTable
import cc.noharry.agent.appserver.server.done
import cc.noharry.bothy.protocol.BothyFindingCode
import cc.noharry.bothy.protocol.DoubaoTtsView
import cc.noharry.bothy.protocol.SettingsOption
import cc.noharry.bothy.protocol.TtsChoice
import cc.noharry.bothy.protocol.TtsSettings
import cc.noharry.bothy.protocol.VoiceEngine
import cc.noharry.bothy.protocol.VoiceKey
import cc.noharry.bothy.protocol.VoiceSettings
import cc.noharry.bothy.protocol.VoiceSettingsRequests
import cc.noharry.bothy.protocol.VoiceSettingsResult
import cc.noharry.bothy.protocol.VoiceSettingsSaveParams
import cc.noharry.bothy.protocol.VoiceSettingsView
import cc.noharry.bothy.protocol.VoiceThresholds
import cc.noharry.bothy.protocol.WakeLevelView
import cc.noharry.bothy.server.voice.VoiceConfig
import cc.noharry.bothy.server.voice.WAKE_IDLE_END_MILLIS
import cc.noharry.bothy.server.voice.WakeWord
import cc.noharry.bothy.server.voice.XIAOBO_WAKE_PROFILE
import cc.noharry.bothy.server.voice.config
import cc.noharry.bothy.server.voice.displayLabel
import cc.noharry.bothy.server.voice.loadJevConfig
import cc.noharry.bothy.server.voice.loadVoiceConfig
import cc.noharry.bothy.server.voice.localAsrUnavailable
import cc.noharry.bothy.server.voice.saveJevConnection
import cc.noharry.bothy.server.voice.saveVoiceConfig
import cc.noharry.bothy.server.voice.tts.DOUBAO_TTS_SPEECH_RATE
import cc.noharry.bothy.server.voice.tts.DOUBAO_TTS_VOICE
import cc.noharry.bothy.server.voice.tts.DoubaoTtsVoice
import cc.noharry.bothy.server.voice.tts.MIMO_TTS_ENABLED
import cc.noharry.bothy.server.voice.tts.MIMO_TTS_STREAM
import cc.noharry.bothy.server.voice.tts.MIMO_TTS_VOICE
import cc.noharry.bothy.server.voice.tts.MimoTtsVoice
import cc.noharry.bothy.server.voice.tts.TTS_PROVIDER
import cc.noharry.bothy.server.voice.tts.TtsConfig
import cc.noharry.bothy.server.voice.tts.clearDoubaoTtsKey
import cc.noharry.bothy.server.voice.tts.clearTtsKey
import cc.noharry.bothy.server.voice.tts.loadDoubaoTtsKey
import cc.noharry.bothy.server.voice.tts.loadTtsKey
import cc.noharry.bothy.server.voice.tts.saveDoubaoTtsKey
import cc.noharry.bothy.server.voice.tts.saveTtsChoice
import cc.noharry.bothy.server.voice.tts.saveTtsKey
import cc.noharry.bothy.server.voice.tts.saveTtsSettings
import cc.noharry.bothy.server.voice.tts.ttsConfig
import cc.noharry.bothy.server.voice.ttsStatus
import cc.noharry.bothy.server.voice.view
import cc.noharry.bothy.voice.DashScopeAsrModel
import cc.noharry.bothy.voice.WakeSensitivity
import cc.noharry.bothy.voice.echoCancellerAvailable
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Settings → 语音: recognition, turns, speech, the keys each needs, the wake level and 试听. */
internal class VoiceSettingsRoutes(private val app: Context) : RouteModule {
    override fun RouteTable.install() {
        on(VoiceSettingsRequests.READ) { io { view() } }
        on(VoiceSettingsRequests.SAVE_VOICE) { settings ->
            io { result(saveVoiceConfig(app, settings.config(loadVoiceConfig(app)))) }
        }
        on(VoiceSettingsRequests.CHOOSE_TTS) { params ->
            val saved = io { saveTtsChoice(app, params.choice.key, params.value) }
            if (saved) RuntimeSession.refreshTts(app)
            io { result(if (saved) null else finding(BothyFindingCode.TTS_SAVE_FAILED)) }
        }
        on(VoiceSettingsRequests.SAVE) { params ->
            val error = io { save(params) }
            if (error == null) RuntimeSession.refreshTts(app)
            io { result(error) }
        }
        on(VoiceSettingsRequests.DELETE_KEY) { params ->
            val error = io { deleteKey(params.key) }
            if (error == null && (params.key == VoiceKey.MIMO || params.key == VoiceKey.DOUBAO)) RuntimeSession.refreshTts(app)
            io { result(error) }
        }
        on(VoiceSettingsRequests.SET_WAKE_LEVEL) { params ->
            val level = WakeSensitivity.of(params.text) ?: WakeSensitivity.STANDARD
            val saved = WakeWord.setSensitivity(app, level)
            io { result(if (saved) null else finding(BothyFindingCode.VOICE_WAKE_SENSITIVITY_SAVE_FAILED)) }
        }
        on(VoiceSettingsRequests.PREVIEW) {
            FlagParams(suspendCancellableCoroutine { done -> RuntimeSession.previewTts(app, PREVIEW_LINE) { done.resume(it) } })
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun result(error: Finding?) = VoiceSettingsResult(view(), error)

    /** Null when every service saved, else what did not, one line each. Jev and speech are written only when sent. */
    private fun save(params: VoiceSettingsSaveParams): Finding? = listOfNotNull(
        saveVoiceConfig(app, params.voice.config(loadVoiceConfig(app)), params.asrKey)?.under(BothyFindingCode.VOICE_ERROR_RECOGNITION),
        params.jev?.let { saveJevConnection(app, it.endpoint, it.model, it.timeoutMillis, it.key) }?.under(BothyFindingCode.VOICE_ERROR_JEV),
        params.tts?.let { typed -> saveTtsSettings(app, effectiveTts().withTyped(typed)) }?.under(BothyFindingCode.VOICE_ERROR_TTS),
        params.ttsKey.takeIf(String::isNotBlank)?.let { saveTtsKey(app, it) }?.under(BothyFindingCode.VOICE_ERROR_MIMO_KEY),
        params.doubaoKey.takeIf(String::isNotBlank)?.let { saveDoubaoTtsKey(app, it) }?.under(BothyFindingCode.VOICE_ERROR_DOUBAO_KEY),
    ).takeIf { it.isNotEmpty() }?.let(::lines)

    /** This finding as [code]'s cause: what did not save, under which service. */
    private fun Finding.under(code: FindingKind) = Finding(code, more = listOf(this))

    private fun deleteKey(key: VoiceKey): Finding? = when (key) {
        VoiceKey.DASHSCOPE -> saveVoiceConfig(app, loadVoiceConfig(app).copy(engine = VoiceEngine.LOCAL), clearKey = true)
        VoiceKey.JEV -> loadJevConfig(app).let { saveJevConnection(app, it.endpoint, it.model, it.timeoutMillis, clearKey = true) }
        VoiceKey.MIMO -> finding(BothyFindingCode.VOICE_MIMO_KEY_DELETE_FAILED).takeUnless { clearTtsKey(app) }
        VoiceKey.DOUBAO -> finding(BothyFindingCode.VOICE_DOUBAO_KEY_DELETE_FAILED).takeUnless { clearDoubaoTtsKey(app) }
    }

    private fun effectiveTts(): TtsConfig = ProviderConfig.load(app).ttsConfig()

    private fun view(): VoiceSettingsView {
        val tts = effectiveTts()
        return VoiceSettingsView(
            voice = loadVoiceConfig(app).settings(),
            jev = loadJevConfig(app).view(),
            tts = tts.settings(ttsStatus(app, tts)),
            ttsKeyStored = loadTtsKey(app).isNotEmpty(),
            doubaoKeyStored = loadDoubaoTtsKey(app).isNotEmpty(),
            localAsrProblem = localAsrUnavailable(app),
            echoCancellerAvailable = echoCancellerAvailable(),
            asrModels = DashScopeAsrModel.entries.map { SettingsOption(it.id, app.getString(it.displayLabel())) },
            mimoVoices = MimoTtsVoice.entries.map { SettingsOption(it.id, it.label(app), if (it.chinese) CHINESE else "") },
            doubaoVoices = DoubaoTtsVoice.entries.map { SettingsOption(it.id, it.label(app)) },
            wakeLevels = WakeSensitivity.entries.map { level ->
                WakeLevelView(level.wireName, XIAOBO_WAKE_PROFILE.phrasesFor(level).map { it.phrase }.distinct())
            },
            wakeIdleEndSeconds = WAKE_IDLE_END_MILLIS / 1000,
        )
    }

    private companion object {
        const val PREVIEW_LINE = "你好，我是小博，播报正常。"

        /** [SettingsOption.summary] of MiMo's Chinese voices. */
        const val CHINESE = "zh"
    }
}

private val TtsChoice.key: String
    get() = when (this) {
        TtsChoice.ENABLED -> MIMO_TTS_ENABLED
        TtsChoice.VOICE -> MIMO_TTS_VOICE
        TtsChoice.STREAM -> MIMO_TTS_STREAM
        TtsChoice.PROVIDER -> TTS_PROVIDER
        TtsChoice.DOUBAO_VOICE -> DOUBAO_TTS_VOICE
        TtsChoice.DOUBAO_SPEECH_RATE -> DOUBAO_TTS_SPEECH_RATE
    }

private fun VoiceConfig.settings() = VoiceSettings(
    engine = engine,
    localThreads = localThreads,
    dashscopeEndpoint = dashscopeEndpoint,
    dashscopeModel = dashscopeModel,
    hasDashscopeKey = hasDashscopeKey,
    autoSubmit = autoSubmit,
    historyMessages = historyMessages,
    echoCancel = echoCancel,
    bargeIn = bargeIn,
    handOverChime = handOverChime,
    jevTurns = jevTurns,
    nearGate = nearGate,
    thresholds = VoiceThresholds(
        thresholds.eos,
        thresholds.eosFloor,
        thresholds.addresseeAgent,
        thresholds.addresseeSide,
        thresholds.silenceCapMillis,
        thresholds.bargeIn,
    ),
    wakeSensitivity = wakeSensitivity.wireName,
    nearGateBelowDb = nearGateBelowDb,
    hotwords = hotwords,
)

/** [saved] with what the page edits; the wake switch, the wake level and the other thresholds stay. */
private fun VoiceSettings.config(saved: VoiceConfig) = saved.copy(
    engine = engine,
    localThreads = localThreads,
    dashscopeEndpoint = dashscopeEndpoint,
    dashscopeModel = dashscopeModel,
    autoSubmit = autoSubmit,
    historyMessages = historyMessages,
    echoCancel = echoCancel,
    bargeIn = bargeIn,
    handOverChime = handOverChime,
    jevTurns = jevTurns,
    nearGate = nearGate,
    nearGateBelowDb = nearGateBelowDb,
    hotwords = hotwords,
    thresholds = saved.thresholds.copy(
        eos = thresholds.eos,
        eosFloor = thresholds.eosFloor,
        addresseeAgent = thresholds.addresseeAgent,
        addresseeSide = thresholds.addresseeSide,
        silenceCapMillis = thresholds.silenceCapMillis,
        bargeIn = thresholds.bargeIn,
    ),
)

private fun TtsConfig.settings(status: Finding) = TtsSettings(
    enabled = enabled,
    provider = provider,
    baseUrl = baseUrl,
    model = model,
    voice = voice,
    style = style,
    stream = stream,
    hasApiKey = apiKey.isNotBlank(),
    ready = ready,
    status = status,
    doubao = DoubaoTtsView(doubao.appId, doubao.resourceId, doubao.voice, doubao.speechRate, doubao.key.isNotBlank()),
)

/** The effective speech settings with the fields the page types: address, model, style, 豆包's App ID and resource. */
private fun TtsConfig.withTyped(typed: TtsSettings) = copy(
    baseUrl = typed.baseUrl,
    model = typed.model,
    style = typed.style,
    doubao = doubao.copy(appId = typed.doubao.appId, resourceId = typed.doubao.resourceId),
)
