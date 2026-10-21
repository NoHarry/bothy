package cc.noharry.bothy.sample

import android.content.Context

/**
 * 图片输入 per saved model: on (文本+图片) by default, the owner's choice on 2026-10-09; off means 仅文本.
 * Off folds every request image into text and keeps Android Use to elements without screenshots.
 */
internal fun loadDebugModelImageInput(context: Context, profile: DebugProviderEditState): Boolean =
    context.getSharedPreferences(MODEL_IMAGE_INPUT_PREFS, Context.MODE_PRIVATE)
        .getBoolean(debugModelIdentityKey(profile), DEFAULT_MODEL_IMAGE_INPUT)

internal fun saveDebugModelImageInput(context: Context, profile: DebugProviderEditState, enabled: Boolean) {
    context.getSharedPreferences(MODEL_IMAGE_INPUT_PREFS, Context.MODE_PRIVATE).edit().apply {
        if (enabled == DEFAULT_MODEL_IMAGE_INPUT) remove(debugModelIdentityKey(profile)) else putBoolean(debugModelIdentityKey(profile), enabled)
    }.apply()
}

internal const val MODEL_IMAGE_INPUT_PREFS = "debug_model_image_input"
private const val DEFAULT_MODEL_IMAGE_INPUT = true
