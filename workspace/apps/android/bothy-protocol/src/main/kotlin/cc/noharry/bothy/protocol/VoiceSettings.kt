package cc.noharry.bothy.protocol

import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FlagParams
import cc.noharry.agent.appserver.protocol.TextParams
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import cc.noharry.agent.appserver.protocol.text
import kotlinx.serialization.Serializable

/** Who recognizes speech: on the device, or 百炼 in the cloud. */
@Serializable
enum class VoiceEngine(val wireName: String) {
    LOCAL("local"),
    DASHSCOPE("dashscope"),
}

/** Who speaks the replies: MiMo, 豆包 or the device's own voice. */
@Serializable
enum class TtsProvider(val wireName: String) {
    MIMO("mimo"),
    DOUBAO("doubao"),
    SYSTEM("system"),
    ;

    companion object {
        fun from(value: String?): TtsProvider = parse(value) ?: MIMO

        /** The provider [value] names, or null when it names none. */
        fun parse(value: String?): TtsProvider? = entries.firstOrNull { it.wireName == value?.trim()?.lowercase() }
    }
}

/** The turn thresholds Settings → 语音 edits; the others stay as stored. */
@Serializable
data class VoiceThresholds(
    val eos: Double = 0.0,
    val eosFloor: Double = 0.0,
    val addresseeAgent: Double = 0.0,
    val addresseeSide: Double = 0.0,
    val silenceCapMillis: Long = 0,
    val bargeIn: Double = 0.0,
)

/**
 * Settings → 语音 as stored: the recognizer, the 百炼 connection, capture and the turn thresholds. The wake switch and
 * [wakeSensitivity] (a level's id) are applied on their own, never by saving these.
 */
@Serializable
data class VoiceSettings(
    val engine: VoiceEngine = VoiceEngine.LOCAL,
    val localThreads: Int = 0,
    val dashscopeEndpoint: String = "",
    val dashscopeModel: String = "",
    val hasDashscopeKey: Boolean = false,
    val autoSubmit: Boolean = true,
    val historyMessages: Int = 0,
    val echoCancel: Boolean = true,
    val bargeIn: Boolean = true,
    val handOverChime: Boolean = true,
    val jevTurns: Boolean = true,
    val nearGate: Boolean = true,
    val thresholds: VoiceThresholds = VoiceThresholds(),
    val wakeSensitivity: String = "",
    val nearGateBelowDb: Int = 0,
    val hotwords: List<String> = emptyList(),
)

/** 豆包语音合成 as stored; its key only as [hasKey]. */
@Serializable
data class DoubaoTtsView(
    val appId: String = "",
    val resourceId: String = "",
    val voice: String = "",
    val speechRate: Int = 0,
    val hasKey: Boolean = false,
)

/** What replies are spoken with now (app settings over the build's): keys only as [hasApiKey], [status] in words. */
@Serializable
data class TtsSettings(
    val enabled: Boolean = false,
    val provider: TtsProvider = TtsProvider.MIMO,
    val baseUrl: String = "",
    val model: String = "",
    val voice: String = "",
    val style: String = "",
    val stream: Boolean = false,
    val hasApiKey: Boolean = false,
    val ready: Boolean = false,
    /** Ready with what speaks, or what is missing. */
    val status: Finding? = null,
    val doubao: DoubaoTtsView = DoubaoTtsView(),
)

/**
 * Settings → 语音 with what it offers: the recognition models, MiMo's voices ([SettingsOption.summary] "zh" for the
 * Chinese ones), 豆包's, the wake levels, whether keys are stored here (a key may also come with the build), why the
 * device's recognizer cannot run, and whether the platform has an echo canceller.
 */
@Serializable
data class VoiceSettingsView(
    val voice: VoiceSettings = VoiceSettings(),
    val jev: JevSettings = JevSettings(),
    val tts: TtsSettings = TtsSettings(),
    val ttsKeyStored: Boolean = false,
    val doubaoKeyStored: Boolean = false,
    val localAsrProblem: Finding? = null,
    val echoCancellerAvailable: Boolean = false,
    val asrModels: List<SettingsOption> = emptyList(),
    val mimoVoices: List<SettingsOption> = emptyList(),
    val doubaoVoices: List<SettingsOption> = emptyList(),
    val wakeLevels: List<WakeLevelView> = emptyList(),
    val wakeIdleEndSeconds: Long = 0,
)

/** A speech choice applied when tapped. */
@Serializable
enum class TtsChoice { ENABLED, VOICE, STREAM, PROVIDER, DOUBAO_VOICE, DOUBAO_SPEECH_RATE }

@Serializable
data class TtsChoiceParams(val choice: TtsChoice, val value: String)

/** Jev's connection as typed; [key] blank keeps the stored one. */
@Serializable
data class JevConnectionDraft(val endpoint: String, val model: String, val timeoutMillis: Long, val key: String = "")

/**
 * 保存 on Settings → 语音: the voice settings with a new 百炼 key ([asrKey], blank keeps it), Jev's connection when it
 * changed, the speech fields when typed ([tts]: address, model, style, 豆包's App ID and resource), and new keys.
 */
@Serializable
data class VoiceSettingsSaveParams(
    val voice: VoiceSettings,
    val asrKey: String = "",
    val jev: JevConnectionDraft? = null,
    val tts: TtsSettings? = null,
    val ttsKey: String = "",
    val doubaoKey: String = "",
)

/** The page as now stored, and what did not save (one line per service). */
@Serializable
data class VoiceSettingsResult(val view: VoiceSettingsView, val error: Finding? = null)

/** A wake level by its id (high, standard, low) and the phrases it listens for. */
@Serializable
data class WakeLevelView(val id: String, val phrases: List<String>)

@Serializable
enum class VoiceKey { DASHSCOPE, JEV, MIMO, DOUBAO }

@Serializable
data class VoiceKeyParams(val key: VoiceKey)

/** Settings → 语音. Keys go in only. */
object VoiceSettingsRequests {
    val READ = RequestMethod("voiceSettings/read", Empty.serializer(), VoiceSettingsView.serializer())

    /** A choice of the voice settings, applied when tapped. */
    val SAVE_VOICE = RequestMethod("voiceSettings/saveVoice", VoiceSettings.serializer(), VoiceSettingsResult.serializer())
    val CHOOSE_TTS = RequestMethod("voiceSettings/chooseTts", TtsChoiceParams.serializer(), VoiceSettingsResult.serializer())
    val SAVE = RequestMethod("voiceSettings/save", VoiceSettingsSaveParams.serializer(), VoiceSettingsResult.serializer())
    val DELETE_KEY = RequestMethod("voiceSettings/deleteKey", VoiceKeyParams.serializer(), VoiceSettingsResult.serializer())

    /** 唤醒阈值, by a level's id; the spotter starts again with it. */
    val SET_WAKE_LEVEL = RequestMethod("voiceSettings/setWakeLevel", TextParams.serializer(), VoiceSettingsResult.serializer())

    /** 试听: speaks a line with the saved settings; whether speech started. */
    val PREVIEW = RequestMethod("voiceSettings/preview", Empty.serializer(), FlagParams.serializer())
}

/** 百炼 hotwords as typed: split on commas, 、 and lines, trimmed, without repeats. */
fun parseHotwords(text: String): List<String> =
    text.split(',', '，', '、', '\n').map(String::trim).filter(String::isNotEmpty).distinct()
