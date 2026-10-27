package cc.noharry.bothy.app.runtime
import android.content.Context
import cc.noharry.agent.model.ModelVariantRegistry
import cc.noharry.bothy.app.model.ModelOptionsController
import cc.noharry.bothy.app.model.ProviderConfig
import cc.noharry.bothy.app.model.ProviderEditState
import cc.noharry.bothy.app.model.ProviderEndpoint
import cc.noharry.bothy.app.model.ProviderProfile
import cc.noharry.bothy.app.session.ModelProvider

internal fun saveSelectedModel(
    context: Context,
    profile: ProviderProfile,
    config: ProviderConfig,
    model: String,
): ProviderConfig? {
    val selectedModel = model.trim()
    if (selectedModel.isEmpty()) return null
    val editor = config.editState(profile)
    if (editor.model == selectedModel) return null
    ProviderConfig.saveProfileOverride(
        context,
        profile,
        apiKey = editor.apiKey,
        model = selectedModel,
        baseUrl = editor.baseUrl,
        endpoint = editor.endpoint,
    )
    return ProviderConfig.load(context)
}

internal fun saveSelectedVariant(
    context: Context,
    profile: ProviderProfile,
    config: ProviderConfig,
    variant: String,
): ProviderConfig? {
    val selectedVariant = variant.trim().takeUnless { it.equals(DEFAULT_DEBUG_VARIANT_ID, ignoreCase = true) }.orEmpty()
    val editor = config.editState(profile)
    if (editor.variant == selectedVariant) return null
    ProviderConfig.saveVariantOverride(context, profile.withModel(editor.model), selectedVariant)
    return ProviderConfig.load(context)
}

internal fun saveProviderOverrideChoice(
    context: Context,
    profile: ProviderProfile,
    config: ProviderConfig?,
    apiKey: String? = null,
    model: String? = null,
    baseUrl: String? = null,
    endpoint: ProviderEndpoint? = null,
    selectedProvider: ModelProvider? = null,
): ProviderConfig {
    val editor = config?.editState(profile) ?: ProviderEditState("", "", "", "")
    val targetProvider = selectedProvider ?: profile.provider
    val targetEndpoint = (endpoint ?: editor.endpoint).takeIf { it.provider == targetProvider }
        ?: ProviderEndpoint.defaultFor(targetProvider)
    ProviderConfig.saveProfileOverride(
        context,
        profile.copy(provider = targetProvider),
        apiKey = apiKey ?: editor.apiKey,
        model = model ?: editor.model,
        baseUrl = baseUrl ?: editor.baseUrl,
        endpoint = targetEndpoint,
    )
    return ProviderConfig.load(context)
}

internal fun appConfigEditor(
    profile: ProviderProfile?,
    config: ProviderConfig?,
    modelOptions: ModelOptionsController,
): ProviderEditState {
    val activeProfile = profile ?: config?.activeProfile
    val editor = activeProfile?.let { config?.editState(it) } ?: ProviderEditState("", "", "", "")
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
internal fun ProviderEditState.variantChoices(): Pair<List<String>, String> {
    val modelDefault = ModelVariantRegistry.defaultVariant(provider.wireName, model)?.takeIf { it in variantOptions }
        ?: return (listOf(DEFAULT_DEBUG_VARIANT_ID) + variantOptions) to variant.ifBlank { DEFAULT_DEBUG_VARIANT_ID }
    return variantOptions to variant.ifBlank { modelDefault }
}

internal const val DEFAULT_DEBUG_VARIANT_ID = "default"
