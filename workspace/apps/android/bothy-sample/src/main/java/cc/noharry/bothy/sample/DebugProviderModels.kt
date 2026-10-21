package cc.noharry.bothy.sample

internal data class DebugProviderProfile(
    val id: String,
    val label: String,
    val provider: DebugProvider,
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val endpoint: DebugProviderEndpoint = DebugProviderEndpoint.defaultFor(provider),
    val variant: String = "",
    val streamEnabled: Boolean? = null,
    val serverSideSearch: Boolean = true,
    val searchCapability: String = "",
) {
    val displayLabel: String = label.ifBlank { provider.label }

    fun withModel(model: String): DebugProviderProfile =
        copy(model = model.trim().ifBlank { this.model })

    fun toOption(activeProfileId: String): DebugProviderProfileOption =
        DebugProviderProfileOption(
            id = id,
            label = displayLabel,
            providerLabel = provider.label,
            endpointLabel = endpoint.label,
            model = model,
            selected = id == activeProfileId,
            canDelete = id.isManualProfileId(),
        )
}

internal data class DebugProviderProfileOption(
    val id: String,
    val label: String,
    val providerLabel: String,
    val endpointLabel: String,
    val model: String,
    val selected: Boolean,
    val canDelete: Boolean,
)

internal data class DebugProviderEditState(
    val apiKey: String,
    val model: String,
    val variant: String,
    val baseUrl: String,
    val profileId: String = "",
    val profileLabel: String = "",
    val provider: DebugProvider = DebugProvider.OPENAI,
    val endpoint: DebugProviderEndpoint = DebugProviderEndpoint.CHAT_COMPLETIONS,
    val manualProfileEnabled: Boolean = false,
    val profileOptions: List<DebugProviderProfileOption> = emptyList(),
    val modelOptions: List<String> = emptyList(),
    val variantOptions: List<String> = emptyList(),
)

internal enum class DebugProviderEndpoint(
    val wireName: String,
    val label: String,
    val provider: DebugProvider,
    val defaultStream: Boolean? = null,
) {
    CHAT_COMPLETIONS("chat_completions", "Chat", DebugProvider.OPENAI),
    RESPONSES("responses", "Responses", DebugProvider.OPENAI, defaultStream = true),
    MESSAGES("messages", "Messages", DebugProvider.ANTHROPIC),
    ;

    companion object {
        fun options(provider: DebugProvider): List<DebugProviderEndpoint> =
            entries.filter { it.provider == provider }

        fun defaultFor(provider: DebugProvider): DebugProviderEndpoint =
            options(provider).firstOrNull() ?: CHAT_COMPLETIONS

        fun from(value: String?, provider: DebugProvider): DebugProviderEndpoint =
            options(provider).firstOrNull { it.wireName == value?.trim()?.lowercase() }
                ?: defaultFor(provider)
    }
}

internal data class DebugProviderSelection(
    val defaultProfileId: String,
    val activeProfile: DebugProviderProfile,
)
