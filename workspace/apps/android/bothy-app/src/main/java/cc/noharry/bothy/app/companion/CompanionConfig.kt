package cc.noharry.bothy.app.companion
import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.model.buildConfigValues
import cc.noharry.bothy.app.voice.loadDashscopeKey
import cc.noharry.bothy.app.voice.tts.validationError
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 陪聊 (owner 2026-10-13: 「我准备接入一个陪聊的api,也就是用户闲聊的时候走那边」; 「元宝/混元、豆包、qwen3.8-omni-flash
 * 都可以适配下，设置里面可以配置」): small talk in a voice session goes to a companion chat service over the
 * OpenAI-compatible `/chat/completions`, everything else to the agent. Each service keeps its own address, model,
 * key and extra request fields, so switching between them keeps what was filled in.
 */
internal enum class CompanionService(
    @StringRes val label: Int,
    val defaultBaseUrl: String,
    val defaultModel: String,
    /** Merged into each request: Qwen's omni model thinks by default, far too slow for a chat line. */
    val defaultExtraBody: String,
    @StringRes val hint: Int,
) {
    QWEN(
        R.string.companion_service_qwen,
        "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "qwen3.8-omni-flash",
        """{"enable_thinking": false}""",
        R.string.companion_service_qwen_hint,
    ),
    DOUBAO(
        R.string.companion_service_doubao,
        "https://ark.cn-beijing.volces.com/api/v3",
        "",
        "",
        R.string.companion_service_doubao_hint,
    ),
    HUNYUAN(
        R.string.companion_service_hunyuan,
        "https://api.hunyuan.cloud.tencent.com/v1",
        "hunyuan-turbos-latest",
        "",
        R.string.companion_service_hunyuan_hint,
    ),
    CUSTOM(R.string.companion_service_custom, "", "", "", R.string.companion_service_custom_hint),
    ;

    val wireName: String get() = name.lowercase()

    companion object {
        fun from(value: String?): CompanionService = entries.firstOrNull { it.wireName == value?.trim()?.lowercase() } ?: QWEN
    }
}

/**
 * Observe first (owner's rollout): score and log where each turn would go, while everything still reaches the agent.
 * [shortLabel] names the mode in 检查 → 陪聊.
 */
internal enum class CompanionMode(val wireName: String, @StringRes val label: Int, @StringRes val shortLabel: Int) {
    OBSERVE("observe", R.string.companion_mode_observe, R.string.companion_mode_observe_short),
    ROUTE("route", R.string.companion_mode_route, R.string.companion_mode_route),
    ;

    companion object {
        fun from(value: String?): CompanionMode = entries.firstOrNull { it.wireName == value } ?: OBSERVE
    }
}

/** One service's settings; the key lives in no-backup storage ([CompanionKeys]), never in these prefs. */
internal data class CompanionEndpoint(
    val baseUrl: String,
    val model: String,
    val extraBody: String,
)

internal data class CompanionConfig(
    val enabled: Boolean = false,
    val mode: CompanionMode = CompanionMode.OBSERVE,
    /** Jev's `small_talk` score from which a turn is small talk. High: a request misread as chat goes undone. */
    val threshold: Double = DEFAULT_COMPANION_THRESHOLD,
    val service: CompanionService = CompanionService.QWEN,
    val persona: String = DEFAULT_COMPANION_PERSONA,
    val endpoints: Map<CompanionService, CompanionEndpoint> = emptyMap(),
) {
    fun endpoint(service: CompanionService = this.service): CompanionEndpoint =
        endpoints[service] ?: CompanionEndpoint(service.defaultBaseUrl, service.defaultModel, service.defaultExtraBody)

    /** Whether Jev should score small talk at all: on, in either mode. */
    val scoring: Boolean get() = enabled

    /**
     * What is filled in must be well formed. Whether the service is complete is [readinessError], checked when a turn is
     * sent: refusing to save an incomplete one kept 豆包 (no model yet) from being picked at all while 陪聊 was on.
     */
    fun validationError(context: Context): String? {
        val endpoint = endpoint()
        return when {
            !threshold.isFinite() || threshold !in 0.5..1.0 -> context.getString(R.string.companion_error_threshold)
            endpoint.baseUrl.isNotBlank() ->
                validateCompanionUrl(context, endpoint.baseUrl) ?: validateExtraBody(context, endpoint.extraBody)
            else -> validateExtraBody(context, endpoint.extraBody)
        }
    }

    /** Why the chosen service cannot be asked yet; until then its turns go to the agent. */
    fun readinessError(context: Context): String? {
        val endpoint = endpoint()
        val name = context.getString(service.label)
        return when {
            endpoint.baseUrl.isBlank() -> context.getString(R.string.companion_error_no_address, name)
            endpoint.model.isBlank() -> context.getString(R.string.companion_error_no_model, name)
            else -> null
        }
    }
}

internal fun loadCompanionConfig(context: Context): CompanionConfig {
    seedEmbeddedCompanion(context)
    val prefs = context.getSharedPreferences(COMPANION_PREFS, Context.MODE_PRIVATE)
    val endpoints = CompanionService.entries.associateWith { service ->
        CompanionEndpoint(
            baseUrl = prefs.getString("${service.wireName}.base_url", null) ?: service.defaultBaseUrl,
            model = prefs.getString("${service.wireName}.model", null) ?: service.defaultModel,
            extraBody = prefs.getString("${service.wireName}.extra_body", null) ?: service.defaultExtraBody,
        )
    }
    return CompanionConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        mode = CompanionMode.from(prefs.getString(KEY_MODE, null)),
        threshold = prefs.getFloat(KEY_THRESHOLD, DEFAULT_COMPANION_THRESHOLD.toFloat()).toDouble(),
        service = CompanionService.from(prefs.getString(KEY_SERVICE, null)),
        persona = prefs.getString(KEY_PERSONA, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_COMPANION_PERSONA,
        endpoints = endpoints,
    )
}

/** Returns why it was refused, or null once saved. A blank [key] keeps the stored one. */
internal fun saveCompanionConfig(context: Context, config: CompanionConfig, key: String = ""): String? {
    config.validationError(context)?.let { return it }
    if (key.any(Char::isWhitespace)) return context.getString(R.string.companion_error_key_whitespace)
    val prefs = context.getSharedPreferences(COMPANION_PREFS, Context.MODE_PRIVATE).edit()
        .putBoolean(KEY_ENABLED, config.enabled)
        .putString(KEY_MODE, config.mode.wireName)
        .putFloat(KEY_THRESHOLD, config.threshold.toFloat())
        .putString(KEY_SERVICE, config.service.wireName)
        .putString(KEY_PERSONA, config.persona.trim())
    config.endpoints.forEach { (service, endpoint) ->
        prefs.putString("${service.wireName}.base_url", endpoint.baseUrl.trim())
            .putString("${service.wireName}.model", endpoint.model.trim())
            .putString("${service.wireName}.extra_body", endpoint.extraBody.trim())
    }
    prefs.apply()
    if (key.isNotBlank()) CompanionKeys.save(context, config.service, key.trim())?.let { return it }
    return null
}

/** Each service's API key, in no-backup storage. Qwen falls back to the 百炼 key speech recognition uses. */
internal object CompanionKeys {
    fun read(context: Context, service: CompanionService): String {
        val own = runCatching { file(context, service).readText(Charsets.UTF_8).trim() }.getOrDefault("")
        return own.ifEmpty { if (service == CompanionService.QWEN) loadDashscopeKey(context) else "" }
    }

    fun hasOwn(context: Context, service: CompanionService): Boolean = file(context, service).isFile

    fun save(context: Context, service: CompanionService, key: String): String? = try {
        file(context, service).apply { parentFile?.mkdirs() }.writeText(key, Charsets.UTF_8)
        null
    } catch (_: IOException) {
        context.getString(R.string.companion_error_key_not_saved)
    }

    private fun file(context: Context, service: CompanionService): File =
        File(File(context.noBackupFilesDir, KEYS_DIR), "${service.wireName}-api-key")
}

/**
 * An APK built with `COMPANION_<SERVICE>_API_KEY` (and optionally `_BASE_URL`, `_MODEL`; `COMPANION_SERVICE` picks the
 * service) in local.properties (embed scope `all`) arrives with them filled in, once: later edits win, as for Jev.
 * The companion stays off until switched on in 设置 → 陪聊.
 */
internal fun seedEmbeddedCompanion(context: Context, embedded: () -> Map<String, String> = ::buildConfigValues) {
    val prefs = context.getSharedPreferences(COMPANION_PREFS, Context.MODE_PRIVATE)
    if (prefs.getBoolean(KEY_SEEDED, false)) return
    val values = embedded().filterKeys { it in COMPANION_EMBEDDED_KEYS }.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
    if (values.isEmpty()) return
    val edit = prefs.edit()
    values["COMPANION_SERVICE"]?.let { edit.putString(KEY_SERVICE, CompanionService.from(it).wireName) }
    CompanionService.entries.forEach { service ->
        val prefix = "COMPANION_${service.name}_"
        values["${prefix}BASE_URL"]?.let { edit.putString("${service.wireName}.base_url", it) }
        values["${prefix}MODEL"]?.let { edit.putString("${service.wireName}.model", it) }
        values["${prefix}API_KEY"]?.takeIf { !CompanionKeys.hasOwn(context, service) }?.let { key ->
            CompanionKeys.save(context, service, key)?.let { Log.w(COMPANION_TAG, "Embedded $prefix key not stored: $it") }
        }
    }
    edit.putBoolean(KEY_SEEDED, true).apply()
}

private fun validateCompanionUrl(context: Context, url: String): String? {
    val uri = try {
        URI(url.trim())
    } catch (_: URISyntaxException) {
        return context.getString(R.string.companion_error_url_invalid)
    }
    return when {
        uri.scheme == "https" && !uri.host.isNullOrBlank() -> null
        uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost") -> null
        else -> context.getString(R.string.companion_error_url_https)
    }
}

private fun validateExtraBody(context: Context, text: String): String? {
    if (text.isBlank()) return null
    return try {
        if (Json.parseToJsonElement(text) is JsonObject) null
        else context.getString(R.string.companion_error_extra_not_object)
    } catch (_: SerializationException) {
        context.getString(R.string.companion_error_extra_invalid)
    }
}

internal val COMPANION_EMBEDDED_KEYS: Set<String> = setOf("COMPANION_SERVICE") +
    CompanionService.entries.flatMap { service -> listOf("API_KEY", "BASE_URL", "MODEL").map { "COMPANION_${service.name}_$it" } }

internal const val DEFAULT_COMPANION_THRESHOLD = 0.75
internal const val COMPANION_TAG = "DebugCompanion"
/** The session scene of a companion turn, and how many exchanges of the session it reads for context. */
internal const val COMPANION_SCENE = "companion"
internal const val COMPANION_CONTEXT_TURNS = 4

/** 小博 chatting: short, spoken, warm; a request to do something is handed back to the agent with [COMPANION_HANDOFF]. */
internal const val DEFAULT_COMPANION_PERSONA =
    "你是小博，一个语音助手，现在在陪用户闲聊。回答像朋友说话一样口语化、温暖，一般一两句，不超过 60 个字；不用列表、" +
        "标题、表情符号和括号说明，因为会被读出来。不知道的事就直说，不编造。"

/** The reply that hands the words back to the agent: the companion was asked to do something, not to chat. */
internal const val COMPANION_HANDOFF = "【交回】"

private const val COMPANION_PREFS = "debug_companion"
private const val KEYS_DIR = "companion-keys"
private const val KEY_ENABLED = "enabled"
private const val KEY_MODE = "mode"
private const val KEY_THRESHOLD = "threshold"
private const val KEY_SERVICE = "service"
private const val KEY_PERSONA = "persona"
private const val KEY_SEEDED = "embedded_seeded"
