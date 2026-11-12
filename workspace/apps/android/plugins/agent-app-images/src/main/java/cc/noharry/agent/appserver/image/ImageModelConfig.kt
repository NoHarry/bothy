package cc.noharry.agent.appserver.image
import android.content.Context
import cc.noharry.agent.appserver.http.AppServerHttpTransport
import cc.noharry.agent.appserver.model.ModelCatalogClient
import cc.noharry.agent.appserver.model.buildConfigValues
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.ImageModelConfig
import cc.noharry.agent.appserver.protocol.ImageModelProblem
import cc.noharry.agent.appserver.protocol.ModelProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A problem as the checks report it. */
val ImageModelProblem.code: FindingCode
    get() = when (this) {
        ImageModelProblem.BASE_URL_SCHEME -> FindingCode.IMAGE_PROBLEM_BASE_URL_SCHEME
        ImageModelProblem.BASE_URL_PARTS -> FindingCode.IMAGE_PROBLEM_BASE_URL_PARTS
        ImageModelProblem.MODEL_WHITESPACE -> FindingCode.IMAGE_PROBLEM_MODEL_WHITESPACE
        ImageModelProblem.NO_MODEL -> FindingCode.IMAGE_PROBLEM_NO_MODEL
        ImageModelProblem.SIZE -> FindingCode.IMAGE_PROBLEM_SIZE
        ImageModelProblem.TIMEOUT -> FindingCode.IMAGE_PROBLEM_TIMEOUT
        ImageModelProblem.NO_KEY -> FindingCode.IMAGE_PROBLEM_NO_KEY
        ImageModelProblem.KEY_WHITESPACE -> FindingCode.IMAGE_PROBLEM_KEY_WHITESPACE
        ImageModelProblem.KEY_NOT_REMOVED -> FindingCode.IMAGE_PROBLEM_KEY_NOT_REMOVED
        ImageModelProblem.NOT_SAVED -> FindingCode.IMAGE_PROBLEM_NOT_SAVED
        ImageModelProblem.KEY_NOT_SAVED -> FindingCode.IMAGE_PROBLEM_KEY_NOT_SAVED
    }

fun loadImageModelConfig(context: Context): ImageModelConfig {
    seedEmbeddedImageModel(context)
    val prefs = context.getSharedPreferences(IMAGE_MODEL_PREFS, Context.MODE_PRIVATE)
    val defaults = ImageModelConfig()
    return ImageModelConfig(
        enabled = prefs.getBoolean("enabled", defaults.enabled),
        baseUrl = prefs.getString("base_url", defaults.baseUrl) ?: defaults.baseUrl,
        model = prefs.getString("model", defaults.model) ?: defaults.model,
        size = prefs.getString("size", defaults.size) ?: defaults.size,
        timeoutMillis = prefs.getLong("timeout_ms", defaults.timeoutMillis),
        hasApiKey = loadImageModelKey(context).isNotEmpty(),
    )
}

/** Persists [config]; returns what is wrong and leaves storage unchanged when invalid. */
internal fun saveImageModelConfig(
    context: Context,
    config: ImageModelConfig,
    replacementKey: String = "",
    clearKey: Boolean = false,
): ImageModelProblem? {
    val key = if (clearKey) "" else replacementKey.trim().ifEmpty { loadImageModelKey(context) }
    if (key.any(Char::isWhitespace)) return ImageModelProblem.KEY_WHITESPACE
    val saved = config.copy(
        baseUrl = config.baseUrl.trim().trimEnd('/'),
        model = config.model.trim(),
        size = config.size.trim(),
        hasApiKey = key.isNotEmpty(),
    )
    saved.validationProblem()?.let { return it }
    return try {
        val keyFile = File(context.noBackupFilesDir, IMAGE_MODEL_KEY_FILE)
        if (clearKey) {
            if (keyFile.exists() && !keyFile.delete()) return ImageModelProblem.KEY_NOT_REMOVED
        } else if (replacementKey.isNotBlank()) {
            context.noBackupFilesDir.mkdirs()
            keyFile.writeText(key, Charsets.UTF_8)
        }
        val committed = context.getSharedPreferences(IMAGE_MODEL_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", saved.enabled).putString("base_url", saved.baseUrl)
            .putString("model", saved.model).putString("size", saved.size)
            .putLong("timeout_ms", saved.timeoutMillis).commit()
        if (committed) null else ImageModelProblem.NOT_SAVED
    } catch (_: java.io.IOException) {
        ImageModelProblem.KEY_NOT_SAVED
    }
}

/**
 * An APK built with `IMAGE_MODEL_*` in local.properties (embed scope `all`) arrives with 生图模型 configured: the
 * first load seeds Settings once. Settings the user already saved are kept, and later edits (including removing the
 * key) win, because the seed never runs again.
 */
internal fun seedEmbeddedImageModel(context: Context, embedded: () -> Map<String, String> = ::buildConfigValues) {
    val prefs = context.getSharedPreferences(IMAGE_MODEL_PREFS, Context.MODE_PRIVATE)
    if (prefs.getBoolean(KEY_SEEDED, false)) return
    val values = embedded()
    val baseUrl = values[EMBEDDED_BASE_URL].orEmpty()
    val model = values[EMBEDDED_MODEL].orEmpty()
    val key = values[EMBEDDED_API_KEY].orEmpty()
    // An APK without the values seeds nothing and stays unmarked, so a later APK that carries them still can.
    if (baseUrl.isBlank() || model.isBlank() || key.isBlank()) return
    val userConfigured = prefs.contains("base_url") || loadImageModelKey(context).isNotEmpty()
    if (!userConfigured) {
        val enabled = values[EMBEDDED_ENABLED]?.trim()?.lowercase() != "false"
        val seeded = ImageModelConfig(enabled, baseUrl, model, values[EMBEDDED_SIZE].orEmpty())
        saveImageModelConfig(context, seeded, replacementKey = key)?.let { error ->
            android.util.Log.w("DebugImageModel", "Embedded image model not applied: ${error.message}")
        }
    }
    prefs.edit().putBoolean(KEY_SEEDED, true).apply()
}

internal fun loadImageModelKey(context: Context): String =
    runCatching { File(context.noBackupFilesDir, IMAGE_MODEL_KEY_FILE).readText(Charsets.UTF_8).trim() }.getOrDefault("")

/**
 * The model IDs the image provider lists (`GET {baseUrl}/models`, OpenAI style, with the saved key). The image model
 * settings pick from these instead of a typed ID.
 */
suspend fun fetchImageModels(context: Context, baseUrl: String): Result<List<String>> = withContext(Dispatchers.IO) {
    val key = loadImageModelKey(context)
    runCatching { ModelCatalogClient(AppServerHttpTransport(context.applicationContext)).fetch(ModelProvider.OPENAI, key, baseUrl) }
}

private const val IMAGE_MODEL_PREFS = "app_server_image_model"
private const val IMAGE_MODEL_KEY_FILE = "image-model-api-key"
private const val KEY_SEEDED = "embedded_seeded"
private const val EMBEDDED_ENABLED = "IMAGE_MODEL_ENABLED"
private const val EMBEDDED_BASE_URL = "IMAGE_MODEL_BASE_URL"
private const val EMBEDDED_MODEL = "IMAGE_MODEL_NAME"
private const val EMBEDDED_API_KEY = "IMAGE_MODEL_API_KEY"
private const val EMBEDDED_SIZE = "IMAGE_MODEL_SIZE"

/**
 * The keys a host's build may carry among its presets' values ([cc.noharry.agent.appserver.runtime.ProviderPresets])
 * to set the image model up on a new installation: the image model's settings take them once, and the person's later
 * edits win.
 */
val IMAGE_MODEL_EMBEDDED_KEYS = setOf(EMBEDDED_ENABLED, EMBEDDED_BASE_URL, EMBEDDED_MODEL, EMBEDDED_API_KEY, EMBEDDED_SIZE)
