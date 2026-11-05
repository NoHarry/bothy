package cc.noharry.bothy.server.voice
import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import cc.noharry.agent.appserver.model.buildConfigValues
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FindingKind
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.bothy.protocol.BothyFindingCode
import cc.noharry.bothy.protocol.JevSearchMode
import cc.noharry.bothy.protocol.JevSettings
import cc.noharry.bothy.server.R
import cc.noharry.bothy.server.voice.tts.validationError
import java.io.File
import java.io.IOException
import java.net.URI

internal data class JevConfig(
    val enabled: Boolean = false,
    val endpoint: String = "https://api.typesafe.ai/v1/systemone",
    val model: String = "jev-1.13.0",
    val timeoutMillis: Long = 5_000L,
    val minScore: Double = 0.5,
    val maxTools: Int = 20,
    val hasApiKey: Boolean = false,
    val mode: JevMode = JevMode.FLAT,
    val domainMinScore: Double = 0.35,
) {
    /** Null when usable, else what is wrong. */
    fun validationError(): FindingKind? {
        val uri = runCatching { URI(endpoint) }.getOrNull()
        return when {
            uri?.scheme != "https" || uri.host.isNullOrBlank() -> BothyFindingCode.VOICE_JEV_ENDPOINT_HTTPS
            uri.userInfo != null || uri.query != null || uri.fragment != null -> BothyFindingCode.VOICE_JEV_ENDPOINT_PARTS
            model.isBlank() || model.any(Char::isWhitespace) -> BothyFindingCode.VOICE_JEV_MODEL_REQUIRED
            timeoutMillis !in MIN_JEV_TIMEOUT_MS..MAX_JEV_TIMEOUT_MS -> BothyFindingCode.VOICE_JEV_TIMEOUT_RANGE
            !minScore.isFinite() || minScore !in 0.0..1.0 -> BothyFindingCode.VOICE_JEV_MIN_SCORE_RANGE
            !domainMinScore.isFinite() || domainMinScore !in 0.0..1.0 -> BothyFindingCode.VOICE_JEV_DOMAIN_SCORE_RANGE
            maxTools !in 1..MAX_JEV_TOOLS -> BothyFindingCode.VOICE_JEV_MAX_TOOLS_RANGE
            enabled && !hasApiKey -> BothyFindingCode.VOICE_JEV_KEY_NEEDED_FOR_SEARCH
            else -> null
        }
    }
}

internal fun loadJevConfig(context: Context): JevConfig {
    seedEmbeddedJev(context)
    val prefs = context.getSharedPreferences(JEV_PREFS, Context.MODE_PRIVATE)
    val defaults = JevConfig()
    return JevConfig(
        enabled = prefs.getBoolean("enabled", defaults.enabled),
        endpoint = prefs.getString("endpoint", defaults.endpoint) ?: defaults.endpoint,
        model = prefs.getString("model", defaults.model) ?: defaults.model,
        timeoutMillis = prefs.getLong("timeout_ms", defaults.timeoutMillis),
        minScore = prefs.getString("min_score", null)?.toDoubleOrNull() ?: defaults.minScore,
        maxTools = prefs.getInt("max_tools", defaults.maxTools),
        hasApiKey = loadJevKey(context).isNotEmpty(),
        mode = JevMode.entries.firstOrNull { it.wireName == prefs.getString("mode", null) } ?: defaults.mode,
        domainMinScore = prefs.getString("domain_min_score", null)?.toDoubleOrNull() ?: defaults.domainMinScore,
    )
}

internal fun saveJevConfig(
    context: Context,
    config: JevConfig,
    replacementKey: String = "",
    clearKey: Boolean = false,
): Finding? {
    val key = if (clearKey) "" else replacementKey.trim().ifEmpty { loadJevKey(context) }
    if (key.any(Char::isWhitespace)) return finding(BothyFindingCode.VOICE_KEY_WHITESPACE)
    val saved = config.copy(endpoint = config.endpoint.trim(), model = config.model.trim(), hasApiKey = key.isNotEmpty())
    saved.validationError()?.let { return finding(it) }
    return try {
        val keyFile = File(context.noBackupFilesDir, JEV_KEY_FILE)
        if (clearKey) {
            if (keyFile.exists() && !keyFile.delete()) return finding(BothyFindingCode.VOICE_JEV_KEY_DELETE_FAILED)
        } else if (replacementKey.isNotBlank()) {
            context.noBackupFilesDir.mkdirs()
            keyFile.writeText(key, Charsets.UTF_8)
        }
        val committed = context.getSharedPreferences(JEV_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", saved.enabled).putString("endpoint", saved.endpoint)
            .putString("model", saved.model).putLong("timeout_ms", saved.timeoutMillis)
            .putString("min_score", saved.minScore.toString()).putInt("max_tools", saved.maxTools)
            .putString("mode", saved.mode.wireName).putString("domain_min_score", saved.domainMinScore.toString()).commit()
        if (committed) null else finding(BothyFindingCode.VOICE_JEV_SAVE_FAILED)
    } catch (_: IOException) {
        finding(BothyFindingCode.VOICE_JEV_KEY_SAVE_FAILED)
    }
}

internal fun loadJevKey(context: Context): String =
    runCatching { File(context.noBackupFilesDir, JEV_KEY_FILE).readText(Charsets.UTF_8).trim() }.getOrDefault("")

/**
 * Settings → 语音 (key on 密钥, the rest on 判断与打断): the connection a voice turn's judge and tool search share. Removing the key also turns
 * tool search off, which cannot run without it.
 */
internal fun saveJevConnection(
    context: Context,
    endpoint: String,
    model: String,
    timeoutMillis: Long,
    replacementKey: String = "",
    clearKey: Boolean = false,
): Finding? {
    val saved = loadJevConfig(context)
    val next = saved.copy(endpoint = endpoint, model = model, timeoutMillis = timeoutMillis, enabled = saved.enabled && !clearKey)
    return saveJevConfig(context, next, replacementKey, clearKey)
}

/** Settings → 工具与技能 → 工具检索: everything but the connection, which stays as saved. */
internal fun saveJevToolSearch(context: Context, search: JevConfig): Finding? {
    val saved = loadJevConfig(context)
    return saveJevConfig(context, search.copy(endpoint = saved.endpoint, model = saved.model, timeoutMillis = saved.timeoutMillis))
}

/**
 * An APK built with `JEV_API_KEY` (optionally `JEV_ENDPOINT`, `JEV_MODEL`) in local.properties (embed scope `all`)
 * arrives with Jev connected: the first load stores them once, unless a key is already stored. Tool search stays off;
 * the voice judge only needs the connection. Later edits win, because the seed never runs again; an APK without the
 * key seeds nothing and stays unmarked, so a later APK that carries it still can.
 */
internal fun seedEmbeddedJev(context: Context, embedded: () -> Map<String, String> = ::buildConfigValues) {
    val prefs = context.getSharedPreferences(JEV_PREFS, Context.MODE_PRIVATE)
    if (prefs.getBoolean(KEY_SEEDED, false)) return
    val values = embedded()
    val key = values[EMBEDDED_JEV_API_KEY].orEmpty().trim()
    if (key.isEmpty()) return
    if (loadJevKey(context).isEmpty()) {
        val endpoint = values[EMBEDDED_JEV_ENDPOINT]?.trim()?.ifEmpty { null }
        val model = values[EMBEDDED_JEV_MODEL]?.trim()?.ifEmpty { null }
        val defaults = JevConfig()
        val problem = defaults.copy(endpoint = endpoint ?: defaults.endpoint, model = model ?: defaults.model).validationError()
            ?.name
            ?: "the key has spaces".takeIf { key.any(Char::isWhitespace) }
        try {
            if (problem == null) {
                context.noBackupFilesDir.mkdirs()
                File(context.noBackupFilesDir, JEV_KEY_FILE).writeText(key, Charsets.UTF_8)
                prefs.edit().apply {
                    endpoint?.let { putString("endpoint", it) }
                    model?.let { putString("model", it) }
                }.apply()
            } else {
                Log.w(JEV_TAG, "Embedded Jev not applied: $problem")
            }
        } catch (error: IOException) {
            Log.w(JEV_TAG, "Embedded Jev key not stored: ${error.message}")
        }
    }
    prefs.edit().putBoolean(KEY_SEEDED, true).apply()
}

internal enum class JevMode(val wireName: String, @StringRes val label: Int) {
    FLAT("flat", R.string.voice_jev_mode_flat),
    TWO_STAGE("two_stage", R.string.voice_jev_mode_two_stage),
    TWO_STAGE_SUBDOMAINS("two_stage_subdomains", R.string.voice_jev_mode_two_stage_subdomains),
}

private const val JEV_PREFS = "debug_jev_config"
private const val JEV_KEY_FILE = "jev-api-key"
private const val MIN_JEV_TIMEOUT_MS = 500L
private const val MAX_JEV_TIMEOUT_MS = 15_000L
private const val MAX_JEV_TOOLS = 24
private const val KEY_SEEDED = "embedded_seeded"
private const val JEV_TAG = "DebugJev"
private const val EMBEDDED_JEV_API_KEY = "JEV_API_KEY"
private const val EMBEDDED_JEV_ENDPOINT = "JEV_ENDPOINT"
private const val EMBEDDED_JEV_MODEL = "JEV_MODEL"

/** The local.properties keys the build may embed for Jev; mirrors `VOICE_SERVICE_LOCAL_KEYS` in the build file. */
internal val JEV_EMBEDDED_KEYS = setOf(EMBEDDED_JEV_API_KEY, EMBEDDED_JEV_ENDPOINT, EMBEDDED_JEV_MODEL)

/** Jev's tool search as the settings show it, and back. */
internal fun JevConfig.view() = JevSettings(enabled, endpoint, model, timeoutMillis, minScore, maxTools, hasApiKey, JevSearchMode.valueOf(mode.name), domainMinScore)

internal fun JevSettings.config(current: JevConfig) = current.copy(
    enabled = enabled,
    minScore = minScore,
    maxTools = maxTools,
    mode = JevMode.valueOf(mode.name),
    domainMinScore = domainMinScore,
)
