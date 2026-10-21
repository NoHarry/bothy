package cc.noharry.bothy.sample

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * adb-only (DUMP) setup for 生图模型, so a key need not be typed on the device or written to a tracked file:
 * extras override the stored settings field by field, and `test` runs one generation and logs the outcome.
 * The key is never logged.
 */
class DebugImageModelBroadcastReceiver : BroadcastReceiver() {
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
        val stored = loadDebugImageModelConfig(context)
        val next = stored.copy(
            enabled = intent.getStringExtra(EXTRA_ENABLED)?.let { it == "on" || it == "true" } ?: stored.enabled,
            baseUrl = intent.getStringExtra(EXTRA_BASE_URL) ?: stored.baseUrl,
            model = intent.getStringExtra(EXTRA_MODEL) ?: stored.model,
            size = intent.getStringExtra(EXTRA_SIZE) ?: stored.size,
        )
        if (next != stored || intent.hasExtra(EXTRA_API_KEY)) {
            val error = saveDebugImageModelConfig(context, next, intent.getStringExtra(EXTRA_API_KEY).orEmpty())
            val saved = loadDebugImageModelConfig(context)
            Log.i(TAG, "IMAGE_MODEL saved=${error == null} error=${error.orEmpty()} ${saved.describe()}")
            return error == null
        }
        return true
    }

    private suspend fun test(context: Context, prompt: String) {
        val config = loadDebugImageModelConfig(context)
        when (val result = DebugImageGenerator(context).generate(config, prompt, IMAGE_TEST_SESSION, keep = TEST_IMAGES_KEPT)) {
            is DebugImageResult.Success -> with(result.image) {
                Log.i(TAG, "IMAGE_MODEL test=ok ${width}x$height bytes=$bytes elapsedMs=$elapsedMillis reference=$reference")
            }
            is DebugImageResult.Failure ->
                Log.w(TAG, "IMAGE_MODEL test=failed elapsedMs=${result.elapsedMillis} message=${result.message}")
        }
    }
}

private fun DebugImageModelConfig.describe(): String =
    "enabled=$enabled ready=$ready baseUrl=$baseUrl model=$model size=${size.ifBlank { "default" }} key=${if (hasApiKey) "stored" else "none"}"

internal const val IMAGE_MODEL_ACTION = "cc.noharry.bothy.sample.DEBUG_IMAGE_MODEL"

private const val TAG = "DebugImageModel"
private const val EXTRA_ENABLED = "enabled"
private const val EXTRA_BASE_URL = "base_url"
private const val EXTRA_MODEL = "model"
private const val EXTRA_SIZE = "size"
private const val EXTRA_API_KEY = "api_key"
private const val EXTRA_TEST = "test"
