package cc.noharry.bothy.sample

import android.content.Context
import cc.noharry.agent.model.ModelVariantRegistry

internal fun saveSelectedDebugModel(
    context: Context,
    profile: DebugProviderProfile,
    config: DebugProviderConfig,
    model: String,
): DebugProviderConfig? {
    val selectedModel = model.trim()
    if (selectedModel.isEmpty()) return null
    val editor = config.editState(profile)
    if (editor.model == selectedModel) return null
    DebugProviderConfig.saveProfileOverride(
        context,
        profile,
        apiKey = editor.apiKey,
        model = selectedModel,
        baseUrl = editor.baseUrl,
        endpoint = editor.endpoint,
    )
    return DebugProviderConfig.load(context)
}

internal fun saveSelectedDebugVariant(
    context: Context,
    profile: DebugProviderProfile,
    config: DebugProviderConfig,
    variant: String,
): DebugProviderConfig? {
    val selectedVariant = variant.trim().takeUnless { it.equals(DEFAULT_DEBUG_VARIANT_ID, ignoreCase = true) }.orEmpty()
    val editor = config.editState(profile)
    if (editor.variant == selectedVariant) return null
    DebugProviderConfig.saveVariantOverride(context, profile.withModel(editor.model), selectedVariant)
    return DebugProviderConfig.load(context)
}

internal fun saveDebugProviderOverride(
    context: Context,
    profile: DebugProviderProfile,
    config: DebugProviderConfig?,
    apiKey: String? = null,
    model: String? = null,
    baseUrl: String? = null,
    endpoint: DebugProviderEndpoint? = null,
    selectedProvider: DebugProvider? = null,
): DebugProviderConfig {
    val editor = config?.editState(profile) ?: DebugProviderEditState("", "", "", "")
    val targetProvider = selectedProvider ?: profile.provider
    val targetEndpoint = (endpoint ?: editor.endpoint).takeIf { it.provider == targetProvider }
        ?: DebugProviderEndpoint.defaultFor(targetProvider)
    DebugProviderConfig.saveProfileOverride(
        context,
        profile.copy(provider = targetProvider),
        apiKey = apiKey ?: editor.apiKey,
        model = model ?: editor.model,
        baseUrl = baseUrl ?: editor.baseUrl,
        endpoint = targetEndpoint,
    )
    return DebugProviderConfig.load(context)
}

internal fun debugConfigEditor(
    profile: DebugProviderProfile?,
    config: DebugProviderConfig?,
    modelOptions: DebugModelOptionsController,
): DebugProviderEditState {
    val activeProfile = profile ?: config?.activeProfile
    val editor = activeProfile?.let { config?.editState(it) } ?: DebugProviderEditState("", "", "", "")
    return editor.copy(
        modelOptions = activeProfile?.let { modelOptions.options(it, editor.model) }.orEmpty(),
        variantOptions = activeProfile
            ?.let { ModelVariantRegistry.variants(it.provider.wireName, editor.model).map { variant -> variant.id } }
            .orEmpty(),
    )
}

/**
 * The variant menu's options and the one shown chosen. A model with a default of its own (DeepSeek: none, owner
 * 2026-10-19: 思考强度的选择器默认选 none) shows that default chosen and offers no separate Default, which would send the
 * same; other models keep Default (no variant sent).
 */
internal fun DebugProviderEditState.variantChoices(): Pair<List<String>, String> {
    val modelDefault = ModelVariantRegistry.defaultVariant(provider.wireName, model)?.takeIf { it in variantOptions }
        ?: return (listOf(DEFAULT_DEBUG_VARIANT_ID) + variantOptions) to variant.ifBlank { DEFAULT_DEBUG_VARIANT_ID }
    return variantOptions to variant.ifBlank { modelDefault }
}

internal const val DEFAULT_DEBUG_VARIANT_ID = "default"
