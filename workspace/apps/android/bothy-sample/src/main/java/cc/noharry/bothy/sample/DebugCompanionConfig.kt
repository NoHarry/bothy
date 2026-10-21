package cc.noharry.bothy.sample

import android.content.Context
import android.util.Log
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
internal enum class DebugCompanionService(
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    /** Merged into each request: Qwen's omni model thinks by default, far too slow for a chat line. */
    val defaultExtraBody: String,
    val hint: String,
) {
    QWEN(
        "千问",
        "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "qwen3.8-omni-flash",
        """{"enable_thinking": false}""",
        "百炼 API key（不填就用语音识别的百炼 key）。sk-ws- 开头的工作空间 key 要把地址换成 https://<WorkspaceId>.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
    ),
    DOUBAO(
        "豆包",
        "https://ark.cn-beijing.volces.com/api/v3",
        "",
        "",
        "火山方舟 API key；模型填方舟里开通的模型名或接入点 ID（角色对话可选 doubao-seed-character 系列）",
    ),
    HUNYUAN("混元", "https://api.hunyuan.cloud.tencent.com/v1", "hunyuan-turbos-latest", "", "腾讯混元 API key"),
    CUSTOM("自定义（OpenAI 兼容）", "", "", "", "任何 OpenAI 兼容的 /chat/completions 服务"),
    ;

    val wireName: String get() = name.lowercase()

    companion object {
        fun from(value: String?): DebugCompanionService = entries.firstOrNull { it.wireName == value?.trim()?.lowercase() } ?: QWEN
    }
}

/** Observe first (owner's rollout): score and log where each turn would go, while everything still reaches the agent. */
internal enum class DebugCompanionMode(val wireName: String, val label: String) {
    OBSERVE("observe", "只记录，不分流（先看判断准不准）"),
    ROUTE("route", "闲聊交给陪聊服务"),
    ;

    companion object {
        fun from(value: String?): DebugCompanionMode = entries.firstOrNull { it.wireName == value } ?: OBSERVE
    }
}

/** One service's settings; the key lives in no-backup storage ([DebugCompanionKeys]), never in these prefs. */
internal data class DebugCompanionEndpoint(
    val baseUrl: String,
    val model: String,
    val extraBody: String,
)

internal data class DebugCompanionConfig(
    val enabled: Boolean = false,
    val mode: DebugCompanionMode = DebugCompanionMode.OBSERVE,
    /** Jev's `small_talk` score from which a turn is small talk. High: a request misread as chat goes undone. */
    val threshold: Double = DEFAULT_COMPANION_THRESHOLD,
    val service: DebugCompanionService = DebugCompanionService.QWEN,
    val persona: String = DEFAULT_COMPANION_PERSONA,
    val endpoints: Map<DebugCompanionService, DebugCompanionEndpoint> = emptyMap(),
) {
    fun endpoint(service: DebugCompanionService = this.service): DebugCompanionEndpoint =
        endpoints[service] ?: DebugCompanionEndpoint(service.defaultBaseUrl, service.defaultModel, service.defaultExtraBody)

    /** Whether Jev should score small talk at all: on, in either mode. */
    val scoring: Boolean get() = enabled

    /**
     * What is filled in must be well formed. Whether the service is complete is [readinessError], checked when a turn is
     * sent: refusing to save an incomplete one kept 豆包 (no model yet) from being picked at all while 陪聊 was on.
     */
    fun validationError(): String? {
        val endpoint = endpoint()
        return when {
            !threshold.isFinite() || threshold !in 0.5..1.0 -> "灵敏度要在 0.5 到 1 之间"
            endpoint.baseUrl.isNotBlank() -> validateCompanionUrl(endpoint.baseUrl) ?: validateExtraBody(endpoint.extraBody)
            else -> validateExtraBody(endpoint.extraBody)
        }
    }

    /** Why the chosen service cannot be asked yet; until then its turns go to the agent. */
    fun readinessError(): String? {
        val endpoint = endpoint()
        return when {
            endpoint.baseUrl.isBlank() -> "${service.label}还没填地址"
            endpoint.model.isBlank() -> "${service.label}还没填模型"
            else -> null
        }
    }
}

internal fun loadDebugCompanionConfig(context: Context): DebugCompanionConfig {
    seedEmbeddedCompanion(context)
    val prefs = context.getSharedPreferences(COMPANION_PREFS, Context.MODE_PRIVATE)
    val endpoints = DebugCompanionService.entries.associateWith { service ->
        DebugCompanionEndpoint(
            baseUrl = prefs.getString("${service.wireName}.base_url", null) ?: service.defaultBaseUrl,
            model = prefs.getString("${service.wireName}.model", null) ?: service.defaultModel,
            extraBody = prefs.getString("${service.wireName}.extra_body", null) ?: service.defaultExtraBody,
        )
    }
    return DebugCompanionConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        mode = DebugCompanionMode.from(prefs.getString(KEY_MODE, null)),
        threshold = prefs.getFloat(KEY_THRESHOLD, DEFAULT_COMPANION_THRESHOLD.toFloat()).toDouble(),
        service = DebugCompanionService.from(prefs.getString(KEY_SERVICE, null)),
        persona = prefs.getString(KEY_PERSONA, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_COMPANION_PERSONA,
        endpoints = endpoints,
    )
}

/** Returns why it was refused, or null once saved. A blank [key] keeps the stored one. */
internal fun saveDebugCompanionConfig(context: Context, config: DebugCompanionConfig, key: String = ""): String? {
    config.validationError()?.let { return it }
    if (key.any(Char::isWhitespace)) return "API key 不能包含空白字符"
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
    if (key.isNotBlank()) DebugCompanionKeys.save(context, config.service, key.trim())?.let { return it }
    return null
}

/** Each service's API key, in no-backup storage. Qwen falls back to the 百炼 key speech recognition uses. */
internal object DebugCompanionKeys {
    fun read(context: Context, service: DebugCompanionService): String {
        val own = runCatching { file(context, service).readText(Charsets.UTF_8).trim() }.getOrDefault("")
        return own.ifEmpty { if (service == DebugCompanionService.QWEN) loadDebugDashscopeKey(context) else "" }
    }

    fun hasOwn(context: Context, service: DebugCompanionService): Boolean = file(context, service).isFile

    fun save(context: Context, service: DebugCompanionService, key: String): String? = try {
        file(context, service).apply { parentFile?.mkdirs() }.writeText(key, Charsets.UTF_8)
        null
    } catch (_: IOException) {
        "API key 没能保存"
    }

    private fun file(context: Context, service: DebugCompanionService): File =
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
    values["COMPANION_SERVICE"]?.let { edit.putString(KEY_SERVICE, DebugCompanionService.from(it).wireName) }
    DebugCompanionService.entries.forEach { service ->
        val prefix = "COMPANION_${service.name}_"
        values["${prefix}BASE_URL"]?.let { edit.putString("${service.wireName}.base_url", it) }
        values["${prefix}MODEL"]?.let { edit.putString("${service.wireName}.model", it) }
        values["${prefix}API_KEY"]?.takeIf { !DebugCompanionKeys.hasOwn(context, service) }?.let { key ->
            DebugCompanionKeys.save(context, service, key)?.let { Log.w(COMPANION_TAG, "Embedded $prefix key not stored: $it") }
        }
    }
    edit.putBoolean(KEY_SEEDED, true).apply()
}

private fun validateCompanionUrl(url: String): String? {
    val uri = try {
        URI(url.trim())
    } catch (_: URISyntaxException) {
        return "地址格式不对"
    }
    return when {
        uri.scheme == "https" && !uri.host.isNullOrBlank() -> null
        uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost") -> null
        else -> "地址要以 https:// 开头（http:// 只能连本机）"
    }
}

private fun validateExtraBody(text: String): String? {
    if (text.isBlank()) return null
    return try {
        if (Json.parseToJsonElement(text) is JsonObject) null else "额外参数要是一个 JSON 对象"
    } catch (_: SerializationException) {
        "额外参数不是合法的 JSON"
    }
}

internal val COMPANION_EMBEDDED_KEYS: Set<String> = setOf("COMPANION_SERVICE") +
    DebugCompanionService.entries.flatMap { service -> listOf("API_KEY", "BASE_URL", "MODEL").map { "COMPANION_${service.name}_$it" } }

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
