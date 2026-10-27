package cc.noharry.bothy.app.model

import cc.noharry.bothy.app.a2a.endpointLabel
import cc.noharry.bothy.app.session.ModelProvider
internal data class ProviderProfile(
    val id: String,
    val label: String,
    val provider: ModelProvider,
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val endpoint: ProviderEndpoint = ProviderEndpoint.defaultFor(provider),
    val variant: String = "",
    val streamEnabled: Boolean? = null,
    val serverSideSearch: Boolean = true,
    val searchCapability: String = "",
) {
    val displayLabel: String = label.ifBlank { provider.label }

    fun withModel(model: String): ProviderProfile =
        copy(model = model.trim().ifBlank { this.model })

    fun toOption(activeProfileId: String): ProviderProfileOption =
        ProviderProfileOption(
            id = id,
            label = displayLabel,
            providerLabel = provider.label,
            endpointLabel = endpoint.label,
            model = model,
            selected = id == activeProfileId,
            canDelete = id.isManualProfileId(),
        )
}

internal data class ProviderProfileOption(
    val id: String,
    val label: String,
    val providerLabel: String,
    val endpointLabel: String,
    val model: String,
    val selected: Boolean,
    val canDelete: Boolean,
)

internal data class ProviderEditState(
    val apiKey: String,
    val model: String,
    val variant: String,
    val baseUrl: String,
    val profileId: String = "",
    val profileLabel: String = "",
    val provider: ModelProvider = ModelProvider.OPENAI,
    val endpoint: ProviderEndpoint = ProviderEndpoint.CHAT_COMPLETIONS,
    val manualProfileEnabled: Boolean = false,
    val profileOptions: List<ProviderProfileOption> = emptyList(),
    val modelOptions: List<String> = emptyList(),
    val variantOptions: List<String> = emptyList(),
)

internal enum class ProviderEndpoint(
    val wireName: String,
    val label: String,
    val provider: ModelProvider,
    val defaultStream: Boolean? = null,
) {
    CHAT_COMPLETIONS("chat_completions", "Chat", ModelProvider.OPENAI),
    RESPONSES("responses", "Responses", ModelProvider.OPENAI, defaultStream = true),
    MESSAGES("messages", "Messages", ModelProvider.ANTHROPIC),
    ;

    companion object {
        fun options(provider: ModelProvider): List<ProviderEndpoint> =
            entries.filter { it.provider == provider }

        fun defaultFor(provider: ModelProvider): ProviderEndpoint =
            options(provider).firstOrNull() ?: CHAT_COMPLETIONS

        fun from(value: String?, provider: ModelProvider): ProviderEndpoint =
            options(provider).firstOrNull { it.wireName == value?.trim()?.lowercase() }
                ?: defaultFor(provider)
    }
}

internal data class ProviderSelection(
    val defaultProfileId: String,
    val activeProfile: ProviderProfile,
)
