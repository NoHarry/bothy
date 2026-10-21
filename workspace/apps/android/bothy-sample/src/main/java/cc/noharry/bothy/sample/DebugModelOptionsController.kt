package cc.noharry.bothy.sample

import android.content.Context

internal class DebugModelOptionsController(private val onChanged: () -> Unit) {
    private val optionsByProfile = linkedMapOf<String, List<String>>()

    fun options(profile: DebugProviderProfile, selectedModel: String): List<String> =
        normalizeProviderModels(listOf(selectedModel) + optionsByProfile[profile.id].orEmpty())

    fun refresh(context: Context, profile: DebugProviderProfile, config: DebugProviderConfig) {
        optionsByProfile[profile.id] = loadDebugProviderModels(context, config.editState(profile))
        onChanged()
    }
}
