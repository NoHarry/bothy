package cc.noharry.bothy.sample

import android.content.Context
import cc.noharry.bothy.voice.DASHSCOPE_DEFAULT_ENDPOINT
import cc.noharry.bothy.voice.DashScopeVocabularyClient
import cc.noharry.bothy.voice.echoCancellerAvailable
import cc.noharry.bothy.voice.hotwordProblem
import cc.noharry.bothy.voice.NearSpeechGate
import cc.noharry.bothy.voice.MicrophoneAudioSource
import cc.noharry.bothy.voice.VoiceAudioEffects
import cc.noharry.bothy.voice.VoiceAudioSource
import cc.noharry.bothy.voice.DASHSCOPE_DEFAULT_MODEL
import cc.noharry.bothy.voice.DashScopeAsrModel
import cc.noharry.bothy.voice.VoiceTurnThresholds
import cc.noharry.bothy.voice.WakeSensitivity
import java.io.File
import java.net.URI

internal enum class DebugVoiceEngine(val wireName: String, val label: String) {
    LOCAL("local", "本地 · sherpa-onnx"),
    DASHSCOPE("dashscope", "云端 · 阿里云百炼"),
}

/** The platform microphone, with its echo canceller when [DebugVoiceConfig.echoCancel]; [onEffects] hears which effects run. */
internal fun debugMicrophone(
    config: DebugVoiceConfig,
    onEffects: (VoiceAudioEffects) -> Unit = {},
): VoiceAudioSource = MicrophoneAudioSource(config.echoCancel, onEffects)

/**
 * Full duplex needs something that keeps the reply from being heard as speech: the platform's echo canceller. Half
 * duplex goes deaf for the whole task, so "顺便…" while the agent works would be lost.
 */
internal fun debugFullDuplex(config: DebugVoiceConfig): Boolean = config.bargeIn && config.echoCancel && echoCancellerAvailable()

/**
 * Settings → 语音: the recognizer choice, the 百炼 connection, capture and the turn thresholds. Jev's connection
 * (endpoint, model, key) is [DebugJevConfig], edited on the same page and shared with tool search.
 */
internal data class DebugVoiceConfig(
    val engine: DebugVoiceEngine = DebugVoiceEngine.LOCAL,
    val localThreads: Int = 2,
    val dashscopeEndpoint: String = DASHSCOPE_DEFAULT_ENDPOINT,
    val dashscopeModel: String = DASHSCOPE_DEFAULT_MODEL,
    val hasDashscopeKey: Boolean = false,
    val autoSubmit: Boolean = true,
    val historyMessages: Int = 6,
    /** Records the VOICE_COMMUNICATION source with the platform's AEC and noise suppression. */
    val echoCancel: Boolean = true,
    /** Keeps listening while the agent works and speaks, so speech can cut in; needs [echoCancel]. */
    val bargeIn: Boolean = true,
    /** A tone when words are appended to, or replace, the running task. */
    val handOverChime: Boolean = true,
    /**
     * Jev judges whether a turn is over and meant for 小博 (with a saved Jev key). Off, or without a key, every sentence
     * the recognizer closes goes straight to the agent ([cc.noharry.bothy.voice.DirectTurnJudge]).
     */
    val jevTurns: Boolean = true,
    /**
     * Talk well below the wake phrase's level (someone nearby, a video) is not recognized ([NearSpeechGate], owner
     * 2026-10-12: 「旁边有人在说话的时候…asr 还是不断叠加，停不下来」).
     */
    val nearGate: Boolean = true,
    val thresholds: VoiceTurnThresholds = VoiceTurnThresholds(),
    /**
     * The overlay wakes on 你好，小博 or a long press on 小博 (off by default). Switched on its own
     * (saveDebugOverlayWake), not by the form's 保存: it opens the microphone at once.
     */
    val overlayWake: Boolean = false,
    /** 唤醒阈值: applied at once like [overlayWake] (saveDebugWakeSensitivity). */
    val wakeSensitivity: WakeSensitivity = WakeSensitivity.STANDARD,
    /** [NearSpeechGate]: speech this many dB under the wake phrase is someone else's. */
    val nearGateBelowDb: Int = NearSpeechGate.DEFAULT_BELOW_REFERENCE_DB.toInt(),
    /** 百炼 hotwords (预编译热词), kept as one list per recognition model in the user's account. */
    val hotwords: List<String> = DEFAULT_HOTWORDS,
) {

    fun validationError(): String? {
        val uri = runCatching { URI(dashscopeEndpoint) }.getOrNull()
        return when {
            localThreads !in 1..MAX_LOCAL_THREADS -> "本地线程数需为 1–$MAX_LOCAL_THREADS。"
            uri?.scheme != "wss" || uri.host.isNullOrBlank() -> "百炼地址需为 wss:// 地址。"
            uri.userInfo != null || uri.query != null || uri.fragment != null -> "百炼地址不能包含凭据、查询参数或片段。"
            DashScopeAsrModel.entries.none { it.id == dashscopeModel } -> "请选择一个百炼识别模型。"
            engine == DebugVoiceEngine.DASHSCOPE && !hasDashscopeKey -> "使用百炼前请先在「密钥」页保存百炼 key。"
            historyMessages !in 0..MAX_HISTORY_MESSAGES -> "上下文条数需为 0–$MAX_HISTORY_MESSAGES。"
            nearGateBelowDb !in NEAR_GATE_BELOW_RANGE -> "近场门需为 ${NEAR_GATE_BELOW_RANGE.first}–${NEAR_GATE_BELOW_RANGE.last} dB。"
            hotwords.size > MAX_HOTWORDS -> "热词最多 $MAX_HOTWORDS 个。"
            hotwords.firstNotNullOfOrNull(::hotwordProblem) != null -> hotwords.firstNotNullOf(::hotwordProblem)
            else -> thresholds.validationError()
        }
    }
}

internal fun loadDebugVoiceConfig(context: Context): DebugVoiceConfig {
    seedEmbeddedVoice(context)
    val prefs = context.getSharedPreferences(VOICE_PREFS, Context.MODE_PRIVATE)
    val defaults = DebugVoiceConfig()
    val t = defaults.thresholds
    return DebugVoiceConfig(
        engine = DebugVoiceEngine.entries.firstOrNull { it.wireName == prefs.getString("engine", null) } ?: defaults.engine,
        localThreads = prefs.getInt("local_threads", defaults.localThreads),
        dashscopeEndpoint = prefs.getString("dashscope_endpoint", null) ?: defaults.dashscopeEndpoint,
        dashscopeModel = DashScopeAsrModel.of(prefs.getString("dashscope_model", null).orEmpty()).id,
        hasDashscopeKey = loadDebugDashscopeKey(context).isNotEmpty(),
        autoSubmit = prefs.getBoolean("auto_submit", defaults.autoSubmit),
        historyMessages = prefs.getInt("history_messages", defaults.historyMessages),
        echoCancel = prefs.getBoolean("echo_cancel", defaults.echoCancel),
        bargeIn = prefs.getBoolean("barge_in", defaults.bargeIn),
        handOverChime = prefs.getBoolean("hand_over_chime", defaults.handOverChime),
        jevTurns = prefs.getBoolean("jev_turns", defaults.jevTurns),
        nearGate = prefs.getBoolean("near_gate", defaults.nearGate),
        overlayWake = prefs.getBoolean(OVERLAY_WAKE_KEY, defaults.overlayWake),
        wakeSensitivity = WakeSensitivity.of(prefs.getString(WAKE_SENSITIVITY_KEY, null)) ?: defaults.wakeSensitivity,
        nearGateBelowDb = prefs.getInt("near_gate_below_db", defaults.nearGateBelowDb),
        hotwords = prefs.getString("hotwords", null)?.let(::parseHotwords) ?: defaults.hotwords,
        thresholds = VoiceTurnThresholds(
            eos = prefs.double("eos", t.eos),
            eosFloor = prefs.double("eos_floor", t.eosFloor),
            eosDecayMillis = prefs.getLong("eos_decay_ms", t.eosDecayMillis),
            holdingFloorMin = prefs.double("holding_min", t.holdingFloorMin),
            addresseeAgent = prefs.double("addressee_agent", t.addresseeAgent),
            addresseeSide = prefs.double("addressee_side", t.addresseeSide),
            silenceCapMillis = prefs.getLong("silence_cap_ms", t.silenceCapMillis),
            holdingSilenceCapMillis = prefs.getLong("holding_silence_cap_ms", t.holdingSilenceCapMillis),
            bargeIn = prefs.double("barge_in_score", t.bargeIn),
        ),
    )
}

/** Null when saved, else why not. A blank [replacementKey] keeps the stored key. */
internal fun saveDebugVoiceConfig(
    context: Context,
    config: DebugVoiceConfig,
    replacementKey: String = "",
    clearKey: Boolean = false,
): String? {
    val key = if (clearKey) "" else replacementKey.trim().ifEmpty { loadDebugDashscopeKey(context) }
    if (key.any(Char::isWhitespace)) return "API key 不能包含空白字符。"
    val saved = config.copy(
        dashscopeEndpoint = config.dashscopeEndpoint.trim(),
        dashscopeModel = config.dashscopeModel.trim(),
        hasDashscopeKey = key.isNotEmpty(),
    )
    saved.validationError()?.let { return it }
    return try {
        val keyFile = File(context.noBackupFilesDir, DASHSCOPE_KEY_FILE)
        if (clearKey) {
            if (keyFile.exists() && !keyFile.delete()) return "无法删除已保存的 key。"
        } else if (replacementKey.isNotBlank()) {
            context.noBackupFilesDir.mkdirs()
            keyFile.writeText(key, Charsets.UTF_8)
        }
        val t = saved.thresholds
        val committed = context.getSharedPreferences(VOICE_PREFS, Context.MODE_PRIVATE).edit()
            .putString("engine", saved.engine.wireName)
            .putInt("local_threads", saved.localThreads)
            .putString("dashscope_endpoint", saved.dashscopeEndpoint)
            .putString("dashscope_model", saved.dashscopeModel)
            .putBoolean("auto_submit", saved.autoSubmit)
            .putInt("history_messages", saved.historyMessages)
            .putBoolean("echo_cancel", saved.echoCancel)
            .putBoolean("barge_in", saved.bargeIn)
            .putBoolean("hand_over_chime", saved.handOverChime)
            .putBoolean("jev_turns", saved.jevTurns)
            .putBoolean("near_gate", saved.nearGate)
            .putInt("near_gate_below_db", saved.nearGateBelowDb)
            .putString("hotwords", saved.hotwords.joinToString("\n"))
            .putString("barge_in_score", t.bargeIn.toString())
            .putString("eos", t.eos.toString())
            .putString("eos_floor", t.eosFloor.toString())
            .putLong("eos_decay_ms", t.eosDecayMillis)
            .putString("holding_min", t.holdingFloorMin.toString())
            .putString("addressee_agent", t.addresseeAgent.toString())
            .putString("addressee_side", t.addresseeSide.toString())
            .putLong("silence_cap_ms", t.silenceCapMillis)
            .putLong("holding_silence_cap_ms", t.holdingSilenceCapMillis)
            .commit()
        if (committed) null else "无法保存语音设置。"
    } catch (_: java.io.IOException) {
        "无法保存 API key。"
    }
}

/** The overlay wake switch; applied at once by [DebugWakeWord.setEnabled]. */
internal fun saveDebugOverlayWake(context: Context, enabled: Boolean): Boolean =
    context.getSharedPreferences(VOICE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(OVERLAY_WAKE_KEY, enabled).commit()

internal fun saveDebugWakeSensitivity(context: Context, sensitivity: WakeSensitivity): Boolean =
    context.getSharedPreferences(VOICE_PREFS, Context.MODE_PRIVATE).edit().putString(WAKE_SENSITIVITY_KEY, sensitivity.wireName).commit()

internal fun loadDebugDashscopeKey(context: Context): String =
    runCatching { File(context.noBackupFilesDir, DASHSCOPE_KEY_FILE).readText(Charsets.UTF_8).trim() }.getOrDefault("")

/**
 * An APK built with voice values in local.properties (embed scope `all`) arrives with 语音 configured: the first load
 * applies each once, only where nothing is set yet, and later edits win because the seed never runs again. An APK
 * without any of them seeds nothing and stays unmarked, so a later APK that carries them still can.
 * - `DASHSCOPE_API_KEY`: the 百炼 key, unless one is stored;
 * - `VOICE_ASR_ENGINE` (`local` or `dashscope`): the recognizer. Without it a 百炼 key picks 百炼; 百炼 is never
 *   picked without a 百炼 key, since the session could not start;
 * - `DASHSCOPE_ASR_ENDPOINT` (wss://), `DASHSCOPE_ASR_MODEL`, `VOICE_HOTWORDS` (comma separated);
 * - `VOICE_OVERLAY_WAKE` (on / off): 语音唤醒, off when nothing says otherwise.
 */
internal fun seedEmbeddedVoice(context: Context, embedded: () -> Map<String, String> = ::buildConfigValues) {
    val prefs = context.getSharedPreferences(VOICE_PREFS, Context.MODE_PRIVATE)
    if (prefs.getBoolean(KEY_SEEDED, false)) return
    val values = embedded().filterKeys { it in VOICE_EMBEDDED_KEYS }.mapValues { it.value.trim() }.filterValues(String::isNotEmpty)
    if (values.isEmpty()) return
    values[EMBEDDED_DASHSCOPE_API_KEY]?.takeIf { loadDebugDashscopeKey(context).isEmpty() && it.none(Char::isWhitespace) }?.let { key ->
        runCatching {
            context.noBackupFilesDir.mkdirs()
            File(context.noBackupFilesDir, DASHSCOPE_KEY_FILE).writeText(key, Charsets.UTF_8)
        }.onFailure { android.util.Log.w("DebugVoice", "Embedded 百炼 key not stored: ${it.message}") }
    }
    val engine = values[EMBEDDED_VOICE_ENGINE]?.let { wire -> DebugVoiceEngine.entries.firstOrNull { it.wireName == wire } }
        ?: DebugVoiceEngine.DASHSCOPE.takeIf { EMBEDDED_DASHSCOPE_API_KEY in values }
    val edit = prefs.edit()
    fun seed(pref: String, value: String?) {
        if (value != null && !prefs.contains(pref)) edit.putString(pref, value)
    }
    seed("engine", engine?.takeIf { it != DebugVoiceEngine.DASHSCOPE || loadDebugDashscopeKey(context).isNotEmpty() }?.wireName)
    seed("dashscope_endpoint", values[EMBEDDED_DASHSCOPE_ENDPOINT]?.takeIf { it.startsWith("wss://") })
    seed("dashscope_model", values[EMBEDDED_DASHSCOPE_MODEL]?.takeIf { id -> DashScopeAsrModel.entries.any { it.id == id } })
    seed("hotwords", values[EMBEDDED_HOTWORDS]?.let(::parseHotwords)?.takeIf { it.isNotEmpty() }?.joinToString("\n"))
    values[EMBEDDED_OVERLAY_WAKE]?.lowercase()?.let { it in TRUE_WORDS }
        ?.takeIf { !prefs.contains(OVERLAY_WAKE_KEY) }?.let { edit.putBoolean(OVERLAY_WAKE_KEY, it) }
    edit.putBoolean(KEY_SEEDED, true).apply()
}

private fun android.content.SharedPreferences.double(name: String, fallback: Double): Double =
    getString(name, null)?.toDoubleOrNull() ?: fallback

private const val VOICE_PREFS = "debug_voice"
private const val OVERLAY_WAKE_KEY = "overlay_wake"
private const val WAKE_SENSITIVITY_KEY = "wake_sensitivity"
private const val DASHSCOPE_KEY_FILE = "debug_voice_dashscope_key"
private const val KEY_SEEDED = "embedded_seeded"
private const val EMBEDDED_DASHSCOPE_API_KEY = "DASHSCOPE_API_KEY"
private const val EMBEDDED_VOICE_ENGINE = "VOICE_ASR_ENGINE"
private const val EMBEDDED_DASHSCOPE_ENDPOINT = "DASHSCOPE_ASR_ENDPOINT"
private const val EMBEDDED_DASHSCOPE_MODEL = "DASHSCOPE_ASR_MODEL"
private const val EMBEDDED_HOTWORDS = "VOICE_HOTWORDS"
private const val EMBEDDED_OVERLAY_WAKE = "VOICE_OVERLAY_WAKE"
private val TRUE_WORDS = setOf("on", "true", "1", "yes")

/** The local.properties keys the build may embed for 语音 recognition; mirrors `VOICE_SERVICE_LOCAL_KEYS` in the build file. */
internal val VOICE_EMBEDDED_KEYS = setOf(
    EMBEDDED_DASHSCOPE_API_KEY,
    EMBEDDED_VOICE_ENGINE,
    EMBEDDED_DASHSCOPE_ENDPOINT,
    EMBEDDED_DASHSCOPE_MODEL,
    EMBEDDED_HOTWORDS,
    EMBEDDED_OVERLAY_WAKE,
)
private const val MAX_LOCAL_THREADS = 4
private const val MAX_HISTORY_MESSAGES = 12
internal val NEAR_GATE_BELOW_RANGE = 4..30
private const val MAX_HOTWORDS = 200
internal val DEFAULT_HOTWORDS = listOf("小博", "你好小博")

/** Words typed with commas, 、 or new lines between them. */
internal fun parseHotwords(text: String): List<String> =
    text.split(',', '，', '、', '\n').map(String::trim).filter(String::isNotEmpty).distinct()

/**
 * The 百炼 hotword list for [model] holding [words], created or updated only when the words changed since the last
 * call (the id and the words are kept per model). Network on the caller's thread; null when there are no words.
 */
internal fun debugDashScopeVocabulary(
    context: Context,
    model: String,
    apiKey: String,
    words: List<String>,
    clientFor: (String) -> DashScopeVocabularyClient = { DashScopeVocabularyClient(it) },
): String? {
    val clean = words.map(String::trim).filter(String::isNotEmpty).distinct()
    if (clean.isEmpty()) return null
    val prefs = context.getSharedPreferences(VOICE_PREFS, Context.MODE_PRIVATE)
    val idKey = "hotword_vocabulary.$model"
    val wordsKey = "hotword_words.$model"
    val joined = clean.joinToString("\n")
    val saved = prefs.getString(idKey, null)
    if (saved != null && prefs.getString(wordsKey, null) == joined) return saved
    val client = clientFor(apiKey)
    val id = saved?.let { runCatching { client.update(it, clean); it }.getOrNull() } ?: client.create(model, clean)
    prefs.edit().putString(idKey, id).putString(wordsKey, joined).apply()
    return id
}
