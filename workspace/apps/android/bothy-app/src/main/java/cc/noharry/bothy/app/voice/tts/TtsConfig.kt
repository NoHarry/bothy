package cc.noharry.bothy.app.voice.tts
import android.content.Context
import androidx.annotation.StringRes
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.model.ProviderConfig
import cc.noharry.bothy.app.model.orDefault
import cc.noharry.bothy.app.model.toStreamOverride
import java.io.File
import java.io.IOException
import java.net.URI

internal data class TtsConfig(
    val enabled: Boolean = false,
    /** Who speaks the replies: MiMo, 豆包 ([doubao]) or the device's own voice; unless chosen, the first with a key. */
    val provider: TtsProvider = TtsProvider.MIMO,
    val doubao: DoubaoTtsSettings = DoubaoTtsSettings(),
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_MIMO_TTS_BASE_URL,
    val model: String = DEFAULT_MIMO_TTS_MODEL,
    val voice: String = DEFAULT_MIMO_TTS_VOICE,
    val format: String = DEFAULT_MIMO_TTS_FORMAT,
    val style: String = DEFAULT_MIMO_TTS_STYLE,
    val optimizeTextPreview: Boolean = DEFAULT_MIMO_TTS_OPTIMIZE_TEXT_PREVIEW,
    val stream: Boolean = DEFAULT_MIMO_TTS_STREAM,
) {
    val ready: Boolean = enabled && when (provider) {
        TtsProvider.MIMO -> apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
        TtsProvider.DOUBAO -> doubao.ready
        TtsProvider.SYSTEM -> true
    }

    /** The voice now speaking, as one line in [context]'s language: provider, model and voice. */
    fun label(context: Context): String = when (provider) {
        TtsProvider.MIMO -> "$model · $voice"
        TtsProvider.DOUBAO ->
            "${context.getString(R.string.tts_provider_doubao)} · ${doubao.resourceId} · ${doubao.voiceLabel(context)}"
        TtsProvider.SYSTEM -> context.getString(R.string.tts_system_voice_label)
    }

    /** What a cached clip (the wake answer) was synthesized with; a change means synthesizing it again. */
    val voiceIdentity: String
        get() = when (provider) {
            TtsProvider.MIMO -> "${model.trim()}\n${voice.trim()}"
            TtsProvider.DOUBAO -> "doubao\n${doubao.resourceId}\n${doubao.voice}\n${doubao.speechRate}"
            TtsProvider.SYSTEM -> "system"
        }

    companion object {
        fun from(values: Map<String, String>): TtsConfig {
            val resolvedApiKey = values[MIMO_TTS_API_KEY]
                .orDefault(values[MIMO_PROVIDER_PROFILE_API_KEY].orEmpty())
            val doubao = DoubaoTtsSettings.from(values)
            // Chosen by hand, it stays (its key may come next); else the first service with a key, else the device's voice.
            val provider = TtsProvider.parse(values[TTS_PROVIDER]) ?: when {
                resolvedApiKey.isNotBlank() -> TtsProvider.MIMO
                doubao.key.isNotBlank() -> TtsProvider.DOUBAO
                else -> TtsProvider.SYSTEM
            }
            val providerKey = when (provider) {
                TtsProvider.DOUBAO -> doubao.key
                TtsProvider.MIMO -> resolvedApiKey
                TtsProvider.SYSTEM -> SYSTEM_NEEDS_NO_KEY
            }
            return TtsConfig(
                enabled = values[MIMO_TTS_ENABLED].toStreamOverride() ?: providerKey.isNotBlank(),
                provider = provider,
                doubao = doubao,
                apiKey = resolvedApiKey,
                baseUrl = values[MIMO_TTS_BASE_URL].orDefault(DEFAULT_MIMO_TTS_BASE_URL),
                model = values[MIMO_TTS_MODEL].orDefault(DEFAULT_MIMO_TTS_MODEL),
                voice = values[MIMO_TTS_VOICE].orDefault(DEFAULT_MIMO_TTS_VOICE),
                format = values[MIMO_TTS_FORMAT].orDefault(DEFAULT_MIMO_TTS_FORMAT),
                style = values[MIMO_TTS_STYLE].orDefault(DEFAULT_MIMO_TTS_STYLE),
                optimizeTextPreview = values[MIMO_TTS_OPTIMIZE_TEXT_PREVIEW].toStreamOverride()
                    ?: DEFAULT_MIMO_TTS_OPTIMIZE_TEXT_PREVIEW,
                // The device's voice comes as one WAV per line, never as a stream.
                stream = provider != TtsProvider.SYSTEM && (values[MIMO_TTS_STREAM].toStreamOverride() ?: DEFAULT_MIMO_TTS_STREAM),
            )
        }
    }
}

internal fun ProviderConfig.ttsConfig(): TtsConfig =
    TtsConfig.from(values)

/**
 * Null when the address, model and voice can be used, else the message saying what is wrong (for `getString`); the
 * key is checked when replies are spoken.
 */
@StringRes
internal fun TtsConfig.validationError(): Int? {
    val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
    val loopback = uri?.scheme == "http" && uri.host in TTS_LOOPBACK_HOSTS
    return when {
        uri == null || (uri.scheme != "https" && !loopback) || uri.host.isNullOrBlank() -> R.string.tts_endpoint_https
        uri.userInfo != null || uri.query != null || uri.fragment != null -> R.string.tts_endpoint_parts
        model.isBlank() || model.trim().any(Char::isWhitespace) -> R.string.tts_model_required
        voice.isBlank() -> R.string.tts_voice_required
        else -> null
    }
}

/**
 * Settings → 语音 → 播报 (and the key on 密钥), as saved in the app. [ProviderConfig.load] lays these over local.properties
 * and the provider file, so whatever was set here wins; nothing saved here leaves those in charge. The key sits under
 * no_backup; without one, the MiMo model service's key is used.
 */
internal fun savedTtsValues(context: Context): Map<String, String> {
    val prefs = context.getSharedPreferences(TTS_PREFS, Context.MODE_PRIVATE)
    val values = SAVED_TTS_KEYS.mapNotNull { name -> prefs.getString(name, null)?.takeIf(String::isNotBlank)?.let { name to it } }
    val key = loadTtsKey(context)
    val doubaoKey = loadDoubaoTtsKey(context)
    return values.toMap() + listOfNotNull(
        key.takeIf(String::isNotEmpty)?.let { MIMO_TTS_API_KEY to it },
        doubaoKey.takeIf(String::isNotEmpty)?.let { DOUBAO_TTS_API_KEY to it },
    )
}

internal fun loadTtsKey(context: Context): String =
    runCatching { File(context.noBackupFilesDir, TTS_KEY_FILE).readText(Charsets.UTF_8).trim() }.getOrDefault("")

/**
 * The typed values (address, model, style) of [config], saved after validation; null when saved, else why not. The
 * choices (播报回复, 音色, 流式) are left alone: they are saved when tapped ([saveTtsChoice]), and 播报回复 stays
 * "on when there is a key" until it is switched by hand.
 */
internal fun saveTtsSettings(context: Context, config: TtsConfig): String? {
    when (config.provider) {
        TtsProvider.DOUBAO -> config.doubao.validationError()
        TtsProvider.MIMO -> config.validationError()
        TtsProvider.SYSTEM -> null
    }?.let { return context.getString(it) }
    val committed = context.getSharedPreferences(TTS_PREFS, Context.MODE_PRIVATE).edit()
        .putString(MIMO_TTS_BASE_URL, config.baseUrl.trim())
        .putString(MIMO_TTS_MODEL, config.model.trim())
        .putString(MIMO_TTS_STYLE, config.style.trim())
        .putString(DOUBAO_TTS_APP_ID, config.doubao.appId.trim())
        .putString(DOUBAO_TTS_RESOURCE_ID, config.doubao.resourceId.trim())
        .commit()
    return if (committed) null else context.getString(R.string.tts_save_failed)
}

/** Settings → 语音 → 密钥 → MiMo: the key on its own; null when saved, else why not. */
internal fun saveTtsKey(context: Context, key: String): String? {
    val trimmed = key.trim()
    if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return context.getString(R.string.tts_key_invalid)
    return try {
        context.noBackupFilesDir.mkdirs()
        File(context.noBackupFilesDir, TTS_KEY_FILE).writeText(trimmed, Charsets.UTF_8)
        null
    } catch (_: IOException) {
        context.getString(R.string.tts_key_save_failed)
    }
}

/** One choice (播报回复, 音色, 流式) saved on its own, at once; typed values keep coming from where they come from. */
internal fun saveTtsChoice(context: Context, name: String, value: String): Boolean {
    require(name in TTS_CHOICE_KEYS) { "$name is not a 播报 choice" }
    return context.getSharedPreferences(TTS_PREFS, Context.MODE_PRIVATE).edit().putString(name, value).commit()
}

/** Removes the key saved in the app; replies then use the packaged or MiMo model service key, if any. */
internal fun clearTtsKey(context: Context): Boolean =
    File(context.noBackupFilesDir, TTS_KEY_FILE).let { !it.exists() || it.delete() }

internal const val MIMO_TTS_ENABLED = "MIMO_TTS_ENABLED"
internal const val MIMO_TTS_API_KEY = "MIMO_TTS_API_KEY"
internal const val MIMO_TTS_BASE_URL = "MIMO_TTS_BASE_URL"
internal const val MIMO_TTS_MODEL = "MIMO_TTS_MODEL"
internal const val MIMO_TTS_VOICE = "MIMO_TTS_VOICE"
internal const val MIMO_TTS_FORMAT = "MIMO_TTS_FORMAT"
internal const val MIMO_TTS_STYLE = "MIMO_TTS_STYLE"
internal const val MIMO_TTS_OPTIMIZE_TEXT_PREVIEW = "MIMO_TTS_OPTIMIZE_TEXT_PREVIEW"
internal const val MIMO_TTS_STREAM = "MIMO_TTS_STREAM"
internal const val MIMO_PROVIDER_PROFILE_API_KEY = "AGENT_RUNTIME_PROVIDER_PROFILE_MIMO_API_KEY"

internal val MIMO_TTS_CONFIG_KEYS = setOf(
    MIMO_TTS_ENABLED,
    MIMO_TTS_API_KEY,
    MIMO_TTS_BASE_URL,
    MIMO_TTS_MODEL,
    MIMO_TTS_VOICE,
    MIMO_TTS_FORMAT,
    MIMO_TTS_STYLE,
    MIMO_TTS_OPTIMIZE_TEXT_PREVIEW,
    MIMO_TTS_STREAM,
)

/**
 * The preset voices of `mimo-v2.5-tts` (MiMo speech synthesis v2.5 guide), Chinese then English. The voice design and
 * voice clone models take none of these. [id] is MiMo's name for the voice; [description] says what it sounds like.
 */
internal enum class MimoTtsVoice(val id: String, @StringRes val description: Int, val chinese: Boolean) {
    BING_TANG("冰糖", R.string.tts_voice_lively_female, true),
    MO_LI("茉莉", R.string.tts_voice_refined_female, true),
    SU_DA("苏打", R.string.tts_voice_sunny_male, true),
    BAI_HUA("白桦", R.string.tts_voice_mature_male, true),
    MIA("Mia", R.string.tts_voice_english_female, false),
    CHLOE("Chloe", R.string.tts_voice_english_female, false),
    MILO("Milo", R.string.tts_voice_english_male, false),
    DEAN("Dean", R.string.tts_voice_english_male, false),
    ;

    /** The chip's label: the voice's name, then what it sounds like. */
    fun label(context: Context): String = "$id · ${context.getString(description)}"
}

internal const val TTS_PREFS = "debug_tts"
private const val TTS_KEY_FILE = "mimo-tts-api-key"
private val SAVED_TTS_KEYS = listOf(MIMO_TTS_ENABLED, MIMO_TTS_BASE_URL, MIMO_TTS_MODEL, MIMO_TTS_VOICE, MIMO_TTS_STYLE, MIMO_TTS_STREAM) +
    listOf(TTS_PROVIDER, DOUBAO_TTS_APP_ID, DOUBAO_TTS_RESOURCE_ID, DOUBAO_TTS_VOICE, DOUBAO_TTS_SPEECH_RATE)
private val TTS_CHOICE_KEYS = setOf(MIMO_TTS_ENABLED, MIMO_TTS_VOICE, MIMO_TTS_STREAM, TTS_PROVIDER, DOUBAO_TTS_VOICE, DOUBAO_TTS_SPEECH_RATE)
private val TTS_LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost")

private const val DEFAULT_MIMO_TTS_BASE_URL = "https://api.xiaomimimo.com/v1"
private const val DEFAULT_MIMO_TTS_MODEL = "mimo-v2.5-tts"
private const val DEFAULT_MIMO_TTS_VOICE = "冰糖"
private const val DEFAULT_MIMO_TTS_FORMAT = "pcm16"
private const val DEFAULT_MIMO_TTS_STYLE =
    "使用接近 Grok 语音模式的实时对话风格，但不要模仿任何特定真人声音。声音自然、聪明、轻松，有一点俏皮和调侃感；像反应很快的 AI 伙伴在近距离聊天。语速稍快但咬字清楚，语调有微笑感和轻微上扬，避免播音腔、客服腔、机械感和过度夸张。"
private const val DEFAULT_MIMO_TTS_OPTIMIZE_TEXT_PREVIEW = false
private const val DEFAULT_MIMO_TTS_STREAM = true
/** Stands for the key the device's voice does not need, so 播报回复 is on by default with it as with a keyed service. */
private const val SYSTEM_NEEDS_NO_KEY = "system"
