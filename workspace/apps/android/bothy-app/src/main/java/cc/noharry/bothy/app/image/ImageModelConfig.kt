package cc.noharry.bothy.app.image
import android.content.Context
import cc.noharry.bothy.app.evidence.BenchHttpTransport
import cc.noharry.bothy.app.model.ModelCatalogClient
import cc.noharry.bothy.app.model.buildConfigValues
import cc.noharry.bothy.app.plans.matches
import cc.noharry.bothy.app.session.ModelProvider
import cc.noharry.bothy.app.voice.tts.validationError
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The image generation model, configured apart from the chat providers: any OpenAI-compatible
 * `POST {baseUrl}/images/generations` endpoint. The key lives in a no-backup file and is never
 * echoed back to the UI; [hasApiKey] only says whether one is stored.
 */
internal data class ImageModelConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val model: String = "",
    /** `WIDTHxHEIGHT` for providers that honour it; blank sends no size and takes the provider default. */
    val size: String = "",
    val timeoutMillis: Long = DEFAULT_IMAGE_TIMEOUT_MS,
    val hasApiKey: Boolean = false,
) {
    /** Whether the assistant may call the image tool on the next run. */
    val ready: Boolean get() = enabled && hasApiKey && validationError() == null

    fun validationError(): String? {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
        val loopbackHttp = uri != null && uri.scheme == "http" && uri.host in LOOPBACK_HOSTS
        return when {
            baseUrl.isBlank() && !enabled -> null
            uri == null || uri.host.isNullOrBlank() || (uri.scheme != "https" && !loopbackHttp) ->
                "Base URL must be https://, or http:// on 127.0.0.1 (adb reverse)."
            uri.userInfo != null || uri.query != null || uri.fragment != null ->
                "Base URL cannot contain credentials, query or fragment."
            model.any(Char::isWhitespace) -> "Model ID cannot contain whitespace."
            // Base URL and key are saved first; the model is then picked from the provider's list.
            enabled && model.isBlank() -> "Pick a model before enabling image generation."
            size.isNotBlank() && !IMAGE_SIZE.matches(size.trim()) -> "Size must look like 1024x1024, or stay blank."
            timeoutMillis !in MIN_IMAGE_TIMEOUT_MS..MAX_IMAGE_TIMEOUT_MS -> "Timeout must be 10–300 s."
            enabled && !hasApiKey -> "Add an API key before enabling image generation."
            else -> null
        }
    }
}

internal fun loadImageModelConfig(context: Context): ImageModelConfig {
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

/** Persists [config]; returns a validation error and leaves storage unchanged when invalid. */
internal fun saveImageModelConfig(
    context: Context,
    config: ImageModelConfig,
    replacementKey: String = "",
    clearKey: Boolean = false,
): String? {
    val key = if (clearKey) "" else replacementKey.trim().ifEmpty { loadImageModelKey(context) }
    if (key.any(Char::isWhitespace)) return "API key cannot contain whitespace."
    val saved = config.copy(
        baseUrl = config.baseUrl.trim().trimEnd('/'),
        model = config.model.trim(),
        size = config.size.trim(),
        hasApiKey = key.isNotEmpty(),
    )
    saved.validationError()?.let { return it }
    return try {
        val keyFile = File(context.noBackupFilesDir, IMAGE_MODEL_KEY_FILE)
        if (clearKey) {
            if (keyFile.exists() && !keyFile.delete()) return "Could not remove the stored key."
        } else if (replacementKey.isNotBlank()) {
            context.noBackupFilesDir.mkdirs()
            keyFile.writeText(key, Charsets.UTF_8)
        }
        val committed = context.getSharedPreferences(IMAGE_MODEL_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", saved.enabled).putString("base_url", saved.baseUrl)
            .putString("model", saved.model).putString("size", saved.size)
            .putLong("timeout_ms", saved.timeoutMillis).commit()
        if (committed) null else "Could not save image model settings."
    } catch (_: java.io.IOException) {
        "Could not save the API key."
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
            android.util.Log.w("DebugImageModel", "Embedded image model not applied: $error")
        }
    }
    prefs.edit().putBoolean(KEY_SEEDED, true).apply()
}

internal fun loadImageModelKey(context: Context): String =
    runCatching { File(context.noBackupFilesDir, IMAGE_MODEL_KEY_FILE).readText(Charsets.UTF_8).trim() }.getOrDefault("")

/**
 * The model IDs the image provider lists (`GET {baseUrl}/models`, OpenAI style, with the saved key). Settings → 生图模型
 * picks from these instead of a typed ID.
 */
internal suspend fun fetchImageModels(context: Context, baseUrl: String): Result<List<String>> = withContext(Dispatchers.IO) {
    val key = loadImageModelKey(context)
    runCatching { ModelCatalogClient(BenchHttpTransport(context.applicationContext)).fetch(ModelProvider.OPENAI, key, baseUrl) }
}

/** Whether an ID reads like an image model, so gateways listing every model show these first. */
internal fun looksLikeImageModel(id: String): Boolean {
    val lower = id.lowercase()
    if (IMAGE_MODEL_HINTS.any { it in lower }) return true
    return lower.split('-', '_', '/', '.', ':', ' ').any { it in IMAGE_MODEL_TOKENS }
}

private val IMAGE_MODEL_HINTS = listOf(
    "image", "dall-e", "dalle", "flux", "seedream", "seededit", "imagen", "midjourney", "stable-diffusion", "kolors",
    "cogview", "wanx", "recraft", "ideogram", "jimeng", "nano-banana", "hidream", "playground",
)
private val IMAGE_MODEL_TOKENS = setOf("sd", "sdxl", "sd3", "mj")

/** Generations take 15–30 s on the providers tried; two minutes leaves room for a slow one. */
internal const val DEFAULT_IMAGE_TIMEOUT_MS = 120_000L
private const val MIN_IMAGE_TIMEOUT_MS = 10_000L
private const val MAX_IMAGE_TIMEOUT_MS = 300_000L
private const val IMAGE_MODEL_PREFS = "debug_image_model"
private const val IMAGE_MODEL_KEY_FILE = "image-model-api-key"
private const val KEY_SEEDED = "embedded_seeded"
private const val EMBEDDED_ENABLED = "IMAGE_MODEL_ENABLED"
private const val EMBEDDED_BASE_URL = "IMAGE_MODEL_BASE_URL"
private const val EMBEDDED_MODEL = "IMAGE_MODEL_NAME"
private const val EMBEDDED_API_KEY = "IMAGE_MODEL_API_KEY"
private const val EMBEDDED_SIZE = "IMAGE_MODEL_SIZE"

/** The local.properties keys the build may embed for 生图模型; mirrors `IMAGE_MODEL_LOCAL_KEYS` in the build file. */
internal val IMAGE_MODEL_EMBEDDED_KEYS = setOf(EMBEDDED_ENABLED, EMBEDDED_BASE_URL, EMBEDDED_MODEL, EMBEDDED_API_KEY, EMBEDDED_SIZE)
private val IMAGE_SIZE = Regex("""\d{2,5}x\d{2,5}""")
private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost")
