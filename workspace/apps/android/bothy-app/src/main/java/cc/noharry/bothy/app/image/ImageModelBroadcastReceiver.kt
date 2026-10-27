package cc.noharry.bothy.app.image
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import cc.noharry.bothy.app.a2a.describe
import cc.noharry.bothy.app.model.describe
import cc.noharry.bothy.app.plans.describe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * adb-only (DUMP) setup for 生图模型, so a key need not be typed on the device or written to a tracked file:
 * extras override the stored settings field by field, and `test` runs one generation and logs the outcome.
 * The key is never logged.
 */
class ImageModelBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != IMAGE_MODEL_ACTION) return
        val pending = goAsync()
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val saved = try {
                save(appContext, intent)
            } finally {
                pending.finish()
            }
            // A generation outlives the broadcast's time budget, so it runs after the receiver has finished.
            val prompt = intent.getStringExtra(EXTRA_TEST)
            if (saved && prompt != null) test(appContext, prompt)
        }
    }

    /** Returns false when the extras did not validate; storage is then unchanged. */
    private fun save(context: Context, intent: Intent): Boolean {
        val stored = loadImageModelConfig(context)
        val next = stored.copy(
            enabled = intent.getStringExtra(EXTRA_ENABLED)?.let { it == "on" || it == "true" } ?: stored.enabled,
            baseUrl = intent.getStringExtra(EXTRA_BASE_URL) ?: stored.baseUrl,
            model = intent.getStringExtra(EXTRA_MODEL) ?: stored.model,
            size = intent.getStringExtra(EXTRA_SIZE) ?: stored.size,
        )
        if (next != stored || intent.hasExtra(EXTRA_API_KEY)) {
            val error = saveImageModelConfig(context, next, intent.getStringExtra(EXTRA_API_KEY).orEmpty())
            val saved = loadImageModelConfig(context)
            Log.i(TAG, "IMAGE_MODEL saved=${error == null} error=${error.orEmpty()} ${saved.describe()}")
            return error == null
        }
        return true
    }

    private suspend fun test(context: Context, prompt: String) {
        val config = loadImageModelConfig(context)
        when (val result = ImageGenerator(context).generate(config, prompt, IMAGE_TEST_SESSION, keep = TEST_IMAGES_KEPT)) {
            is ImageResult.Success -> with(result.image) {
                Log.i(TAG, "IMAGE_MODEL test=ok ${width}x$height bytes=$bytes elapsedMs=$elapsedMillis reference=$reference")
            }
            is ImageResult.Failure ->
                Log.w(TAG, "IMAGE_MODEL test=failed elapsedMs=${result.elapsedMillis} message=${result.message}")
        }
    }
}

private fun ImageModelConfig.describe(): String =
    "enabled=$enabled ready=$ready baseUrl=$baseUrl model=$model size=${size.ifBlank { "default" }} key=${if (hasApiKey) "stored" else "none"}"

internal const val IMAGE_MODEL_ACTION = "cc.noharry.bothy.DEBUG_IMAGE_MODEL"

private const val TAG = "DebugImageModel"
private const val EXTRA_ENABLED = "enabled"
private const val EXTRA_BASE_URL = "base_url"
private const val EXTRA_MODEL = "model"
private const val EXTRA_SIZE = "size"
private const val EXTRA_API_KEY = "api_key"
private const val EXTRA_TEST = "test"
