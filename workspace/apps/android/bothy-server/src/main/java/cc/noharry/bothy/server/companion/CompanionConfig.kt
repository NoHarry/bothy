package cc.noharry.bothy.server.companion
import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import cc.noharry.agent.appserver.model.buildConfigValues
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FindingKind
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.bothy.protocol.BothyFindingCode
import cc.noharry.bothy.protocol.CompanionConfig
import cc.noharry.bothy.protocol.CompanionEndpoint
import cc.noharry.bothy.protocol.CompanionMode
import cc.noharry.bothy.protocol.CompanionService
import cc.noharry.bothy.protocol.DEFAULT_COMPANION_PERSONA
import cc.noharry.bothy.protocol.DEFAULT_COMPANION_THRESHOLD
import cc.noharry.bothy.server.R
import cc.noharry.bothy.server.voice.loadDashscopeKey
import cc.noharry.bothy.server.voice.tts.validationError
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** What the transcript and the logs call a service. */
@get:StringRes
internal val CompanionService.label: Int
    get() = when (this) {
        CompanionService.QWEN -> R.string.companion_service_qwen
        CompanionService.DOUBAO -> R.string.companion_service_doubao
        CompanionService.HUNYUAN -> R.string.companion_service_hunyuan
        CompanionService.CUSTOM -> R.string.companion_service_custom
    }

/** A service's name, in a finding. */
internal val CompanionService.code: FindingKind
    get() = when (this) {
        CompanionService.QWEN -> BothyFindingCode.COMPANION_SERVICE_QWEN
        CompanionService.DOUBAO -> BothyFindingCode.COMPANION_SERVICE_DOUBAO
        CompanionService.HUNYUAN -> BothyFindingCode.COMPANION_SERVICE_HUNYUAN
        CompanionService.CUSTOM -> BothyFindingCode.COMPANION_SERVICE_CUSTOM
    }

/** The mode's name in 检查 → 陪聊. */
internal val CompanionMode.shortCode: FindingKind
    get() = when (this) {
        CompanionMode.OBSERVE -> BothyFindingCode.COMPANION_MODE_OBSERVE_SHORT
        CompanionMode.ROUTE -> BothyFindingCode.COMPANION_MODE_ROUTE
    }

/**
 * What is filled in must be well formed. Whether the service is complete is [readinessError], checked when a turn is
 * sent: refusing to save an incomplete one kept 豆包 (no model yet) from being picked at all while 陪聊 was on.
 */
internal fun CompanionConfig.validationError(context: Context): Finding? {
    val endpoint = endpoint()
    return when {
        !threshold.isFinite() || threshold !in 0.5..1.0 -> finding(BothyFindingCode.COMPANION_ERROR_THRESHOLD)
        endpoint.baseUrl.isNotBlank() ->
            validateCompanionUrl(context, endpoint.baseUrl) ?: validateExtraBody(context, endpoint.extraBody)
        else -> validateExtraBody(context, endpoint.extraBody)
    }
}

/** Why the chosen service cannot be asked yet; until then its turns go to the agent. */
internal fun CompanionConfig.readinessError(context: Context): Finding? {
    val endpoint = endpoint()
    val name = finding(service.code)
    return when {
        endpoint.baseUrl.isBlank() -> Finding(BothyFindingCode.COMPANION_ERROR_NO_ADDRESS, more = listOf(name))
        endpoint.model.isBlank() -> Finding(BothyFindingCode.COMPANION_ERROR_NO_MODEL, more = listOf(name))
        else -> null
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
internal fun saveCompanionConfig(context: Context, config: CompanionConfig, key: String = ""): Finding? {
    config.validationError(context)?.let { return it }
    if (key.any(Char::isWhitespace)) return finding(BothyFindingCode.COMPANION_ERROR_KEY_WHITESPACE)
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

    fun save(context: Context, service: CompanionService, key: String): Finding? = try {
        file(context, service).apply { parentFile?.mkdirs() }.writeText(key, Charsets.UTF_8)
        null
    } catch (_: IOException) {
        finding(BothyFindingCode.COMPANION_ERROR_KEY_NOT_SAVED)
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

private fun validateCompanionUrl(context: Context, url: String): Finding? {
    val uri = try {
        URI(url.trim())
    } catch (_: URISyntaxException) {
        return finding(BothyFindingCode.COMPANION_ERROR_URL_INVALID)
    }
    return when {
        uri.scheme == "https" && !uri.host.isNullOrBlank() -> null
        uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost") -> null
        else -> finding(BothyFindingCode.COMPANION_ERROR_URL_HTTPS)
    }
}

private fun validateExtraBody(context: Context, text: String): Finding? {
    if (text.isBlank()) return null
    return try {
        if (Json.parseToJsonElement(text) is JsonObject) null
        else finding(BothyFindingCode.COMPANION_ERROR_EXTRA_NOT_OBJECT)
    } catch (_: SerializationException) {
        finding(BothyFindingCode.COMPANION_ERROR_EXTRA_INVALID)
    }
}

internal val COMPANION_EMBEDDED_KEYS: Set<String> = setOf("COMPANION_SERVICE") +
    CompanionService.entries.flatMap { service -> listOf("API_KEY", "BASE_URL", "MODEL").map { "COMPANION_${service.name}_$it" } }

internal const val COMPANION_TAG = "DebugCompanion"
/** The session scene of a companion turn, and how many exchanges of the session it reads for context. */



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
