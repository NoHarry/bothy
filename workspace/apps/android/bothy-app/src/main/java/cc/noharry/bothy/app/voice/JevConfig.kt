package cc.noharry.bothy.app.voice
import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.model.buildConfigValues
import cc.noharry.bothy.app.voice.tts.validationError
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
    /** Null when usable, else the message saying what is wrong, shown with `getString`. */
    @StringRes
    fun validationError(): Int? {
        val uri = runCatching { URI(endpoint) }.getOrNull()
        return when {
            uri?.scheme != "https" || uri.host.isNullOrBlank() -> R.string.voice_jev_endpoint_https
            uri.userInfo != null || uri.query != null || uri.fragment != null -> R.string.voice_jev_endpoint_parts
            model.isBlank() || model.any(Char::isWhitespace) -> R.string.voice_jev_model_required
            timeoutMillis !in MIN_JEV_TIMEOUT_MS..MAX_JEV_TIMEOUT_MS -> R.string.voice_jev_timeout_range
            !minScore.isFinite() || minScore !in 0.0..1.0 -> R.string.voice_jev_min_score_range
            !domainMinScore.isFinite() || domainMinScore !in 0.0..1.0 -> R.string.voice_jev_domain_score_range
            maxTools !in 1..MAX_JEV_TOOLS -> R.string.voice_jev_max_tools_range
            enabled && !hasApiKey -> R.string.voice_jev_key_needed_for_search
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
): String? {
    val key = if (clearKey) "" else replacementKey.trim().ifEmpty { loadJevKey(context) }
    if (key.any(Char::isWhitespace)) return context.getString(R.string.voice_key_whitespace)
    val saved = config.copy(endpoint = config.endpoint.trim(), model = config.model.trim(), hasApiKey = key.isNotEmpty())
    saved.validationError()?.let { return context.getString(it) }
    return try {
        val keyFile = File(context.noBackupFilesDir, JEV_KEY_FILE)
        if (clearKey) {
            if (keyFile.exists() && !keyFile.delete()) return context.getString(R.string.voice_jev_key_delete_failed)
        } else if (replacementKey.isNotBlank()) {
            context.noBackupFilesDir.mkdirs()
            keyFile.writeText(key, Charsets.UTF_8)
        }
        val committed = context.getSharedPreferences(JEV_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", saved.enabled).putString("endpoint", saved.endpoint)
            .putString("model", saved.model).putLong("timeout_ms", saved.timeoutMillis)
            .putString("min_score", saved.minScore.toString()).putInt("max_tools", saved.maxTools)
            .putString("mode", saved.mode.wireName).putString("domain_min_score", saved.domainMinScore.toString()).commit()
        if (committed) null else context.getString(R.string.voice_jev_save_failed)
    } catch (_: IOException) {
        context.getString(R.string.voice_jev_key_save_failed)
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
): String? {
    val saved = loadJevConfig(context)
    val next = saved.copy(endpoint = endpoint, model = model, timeoutMillis = timeoutMillis, enabled = saved.enabled && !clearKey)
    return saveJevConfig(context, next, replacementKey, clearKey)
}

/** Settings → 工具与技能 → 工具检索: everything but the connection, which stays as saved. */
internal fun saveJevToolSearch(context: Context, search: JevConfig): String? {
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
            ?.let { context.getString(it) }
            ?: context.getString(R.string.voice_key_whitespace).takeIf { key.any(Char::isWhitespace) }
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
