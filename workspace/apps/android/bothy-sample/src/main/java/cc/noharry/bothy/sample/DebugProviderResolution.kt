package cc.noharry.bothy.sample

import cc.noharry.agent.model.ModelVariantRegistry

internal fun debugProviderProfiles(values: Map<String, String>): List<DebugProviderProfile> {
    val explicit = values[PROFILES_KEY].csvValues()
        .mapNotNull { profileFromValues(values, it) }
    if (explicit.isNotEmpty()) return explicit.distinctBy { it.id }.withSharedDeepSeekApiKeys()
    val legacy = DebugProvider.entries.map { provider -> legacyProfile(values, provider) }
    return legacy.distinctBy { it.id }
}

/**
 * DeepSeek OpenAI-compatible and Anthropic-compatible ports share one API key.
 * When [DEEPSEEK_ANTHROPIC_PROFILE_ID] has a blank key, inherit from [DEEPSEEK_API_PROFILE_ID].
 */
internal fun List<DebugProviderProfile>.withSharedDeepSeekApiKeys(): List<DebugProviderProfile> {
    val openaiKey = firstOrNull { it.id.equals(DEEPSEEK_API_PROFILE_ID, ignoreCase = true) }
        ?.apiKey
        ?.takeIf { it.isNotBlank() }
        ?: return this
    return map { profile ->
        if (
            profile.id.equals(DEEPSEEK_ANTHROPIC_PROFILE_ID, ignoreCase = true) &&
            profile.apiKey.isBlank()
        ) {
            profile.copy(apiKey = openaiKey)
        } else {
            profile
        }
    }
}

internal const val DEEPSEEK_API_PROFILE_ID = "deepseek-api"
internal const val DEEPSEEK_ANTHROPIC_PROFILE_ID = "deepseek-anthropic"

internal fun resolveDebugProviderSelection(
    values: Map<String, String>,
    profiles: List<DebugProviderProfile>,
    manualProfileEnabled: Boolean,
    selectedProfileId: String?,
): DebugProviderSelection {
    val defaultId = requestedDefaultProfileId(values, profiles) ?: profiles.first().id
    val selectedId = selectedProfileId.matchProfileId(profiles)
    val activeId = if (manualProfileEnabled && selectedId != null) selectedId else defaultId
    val activeProfile = profiles.firstOrNull { it.id == activeId } ?: profiles.first()
    return DebugProviderSelection(defaultId, activeProfile)
}

internal fun resolveDebugStreamEnabled(values: Map<String, String>): Boolean =
    values["AGENT_RUNTIME_LIVE_PROVIDER_STREAM"].toStreamOverride()
        ?: values["AGENT_RUNTIME_MODEL_PROVIDER_STREAM"].toStreamOverride()
        ?: DEFAULT_STREAM_ENABLED

internal data class SavedProfileState(
    val selectedProfileId: String?,
    val manualProfileEnabled: Boolean,
    val overrides: Map<String, DebugProviderProfileOverride>,
) {
    val hasSavedState: Boolean =
        manualProfileEnabled || !selectedProfileId.isNullOrBlank() || overrides.isNotEmpty()
}

internal data class DebugProviderProfileOverride(
    val label: String? = null,
    val provider: DebugProvider? = null,
    val endpoint: String? = null,
    val apiKey: String? = null,
    val model: String? = null,
    val baseUrl: String? = null,
    val variant: String? = null,
    val serverSideSearch: String? = null,
    val searchCapability: String? = null,
)

private fun profileFromValues(values: Map<String, String>, rawId: String): DebugProviderProfile? {
    val id = rawId.normalizedProfileId().takeIf { it.isNotBlank() } ?: return null
    val prefix = "$PROFILE_ENV_PREFIX${id.envProfileKey()}_"
    val provider = DebugProvider.from(values["${prefix}PROVIDER"] ?: values["${prefix}KIND"])
    val endpoint = DebugProviderEndpoint.from(values["${prefix}ENDPOINT"], provider)
    val baseUrl = values["${prefix}BASE_URL"].orDefault(defaultBaseUrl(provider))
    return DebugProviderProfile(
        id = id,
        label = values["${prefix}LABEL"].orDefault(id),
        provider = provider,
        apiKey = values["${prefix}API_KEY"].orEmpty(),
        model = values["${prefix}MODEL"].orDefault(defaultModel(provider)),
        baseUrl = baseUrl,
        endpoint = endpoint,
        variant = values["${prefix}VARIANT"].orEmpty(),
        streamEnabled = values["${prefix}STREAM"].toStreamOverride(),
        serverSideSearch = values["${prefix}SERVER_SIDE_SEARCH"].toBooleanOverride()
            ?: values["${prefix}SERVER_SIDE_WEB_SEARCH"].toBooleanOverride()
            ?: true,
        searchCapability = values["${prefix}SEARCH_CAPABILITY"]
            ?: values["${prefix}SERVER_SIDE_SEARCH_CAPABILITY"]
            ?: defaultSearchCapability(provider, endpoint),
    )
}

internal fun legacyProfile(values: Map<String, String>, provider: DebugProvider): DebugProviderProfile {
    val model = values[providerModelName(provider)].orDefault(defaultModel(provider))
    val endpoint = DebugProviderEndpoint.from(values[providerEndpointName(provider)], provider)
    val baseUrl = values[providerBaseUrlName(provider)].orDefault(defaultBaseUrl(provider))
    return DebugProviderProfile(
        id = provider.wireName,
        label = provider.label,
        provider = provider,
        apiKey = values[providerKeyName(provider)].orEmpty(),
        model = model,
        baseUrl = baseUrl,
        endpoint = endpoint,
        variant = variant(values, provider, model),
        streamEnabled = null,
        serverSideSearch = values[providerServerSideSearchName(provider)].toBooleanOverride() ?: true,
        searchCapability = defaultSearchCapability(provider, endpoint),
    )
}

internal fun List<DebugProviderProfile>.withSavedProfiles(
    overrides: Map<String, DebugProviderProfileOverride>,
): List<DebugProviderProfile> =
    (map { profile -> profile.withOverride(overrides[profile.id.preferenceKey()] ?: overrides[profile.id]) } +
        overrides.filterKeys { id -> none { it.id.preferenceKey() == id.preferenceKey() } }
            .mapNotNull { (id, override) -> override.toProfile(id) })
        .distinctBy { it.id }

private fun DebugProviderProfile.withOverride(override: DebugProviderProfileOverride?): DebugProviderProfile {
    if (override == null) return this
    val activeProvider = override.provider ?: provider
    val activeEndpoint = DebugProviderEndpoint.from(override.endpoint, activeProvider)
    val activeBaseUrl = override.baseUrl ?: baseUrl
    val profileShapeChanged = override.provider != null || override.endpoint != null || override.baseUrl != null
    return copy(
        label = override.label ?: label,
        provider = activeProvider,
        endpoint = activeEndpoint,
        apiKey = override.apiKey ?: apiKey,
        model = override.model ?: model,
        baseUrl = activeBaseUrl,
        variant = override.variant ?: variant,
        serverSideSearch = override.serverSideSearch.toBooleanOverride() ?: serverSideSearch,
        searchCapability = override.searchCapability
            ?: if (profileShapeChanged) defaultSearchCapability(activeProvider, activeEndpoint) else searchCapability,
    )
}

private fun DebugProviderProfileOverride.toProfile(id: String): DebugProviderProfile? {
    val provider = provider ?: return null
    val model = model ?: defaultModel(provider)
    val baseUrl = baseUrl?.takeIf { it.isNotBlank() } ?: return null
    return DebugProviderProfile(
        id = id.normalizedProfileId().takeIf { it.isNotBlank() } ?: return null,
        label = label?.takeIf { it.isNotBlank() } ?: id,
        provider = provider,
        apiKey = apiKey.orEmpty(),
        model = model,
        baseUrl = baseUrl,
        endpoint = DebugProviderEndpoint.from(endpoint, provider),
        variant = variant.orEmpty(),
        serverSideSearch = serverSideSearch.toBooleanOverride() ?: true,
        searchCapability = searchCapability
            ?: defaultSearchCapability(provider, DebugProviderEndpoint.from(endpoint, provider)),
    )
}

internal fun defaultSearchCapability(
    provider: DebugProvider,
    endpoint: DebugProviderEndpoint,
): String =
    when (provider) {
        DebugProvider.ANTHROPIC -> "anthropic-server-tool"
        DebugProvider.OPENAI -> when {
            endpoint == DebugProviderEndpoint.RESPONSES -> "openai-responses-tool"
            else -> "openai-chat-options"
        }
    }

private fun requestedDefaultProfileId(
    values: Map<String, String>,
    profiles: List<DebugProviderProfile>,
): String? =
    listOf(
        values["AGENT_RUNTIME_ACTIVE_PROFILE"],
        values["AGENT_RUNTIME_DEFAULT_PROFILE"],
        values["AGENT_RUNTIME_LIVE_PROVIDER"],
        values["AGENT_RUNTIME_MODEL_PROVIDER"],
    ).firstNotNullOfOrNull { requested ->
        requested.matchProfileId(profiles)
            ?: profiles.firstOrNull { it.provider.wireName == requested?.trim()?.lowercase() }?.id
    }

private fun String?.matchProfileId(profiles: List<DebugProviderProfile>): String? {
    val requested = this?.normalizedProfileId() ?: return null
    return profiles.firstOrNull { it.id.equals(requested, ignoreCase = true) }?.id
        ?: profiles.firstOrNull { it.id.preferenceKey() == requested.preferenceKey() }?.id
}

internal fun DebugProviderProfile.toActiveValues(): Map<String, String> =
    buildMap {
        put("AGENT_RUNTIME_LIVE_PROVIDER", provider.wireName)
        put(providerKeyName(provider), apiKey)
        put(providerModelName(provider), model)
        put(providerBaseUrlName(provider), baseUrl)
        put(providerEndpointName(provider), endpoint.wireName)
        if (variant.isNotBlank()) put(providerVariantName(provider), variant)
        streamEnabled?.let { put("AGENT_RUNTIME_LIVE_PROVIDER_STREAM", it.toString()) }
        if (serverSideSearch) put(providerServerSideSearchName(provider), "true")
        if (searchCapability.isNotBlank()) put(providerSearchCapabilityName(provider), searchCapability)
    }

internal fun debugProviderFromOrNull(value: String?): DebugProvider? =
    value?.let { raw -> DebugProvider.entries.firstOrNull { it.wireName == raw.trim().lowercase() } }

internal fun manualProfileId(label: String, provider: DebugProvider): String {
    val base = label.normalizedProfileId().ifBlank { provider.wireName }
    return "$MANUAL_PROFILE_ID_PREFIX-$base-${System.currentTimeMillis()}"
}

internal fun providerKeyName(provider: DebugProvider): String =
    when (provider) {
        DebugProvider.ANTHROPIC -> "ANTHROPIC_API_KEY"
        DebugProvider.OPENAI -> "OPENAI_API_KEY"
    }

internal fun providerModelName(provider: DebugProvider): String =
    when (provider) {
        DebugProvider.ANTHROPIC -> "ANTHROPIC_MODEL"
        DebugProvider.OPENAI -> "OPENAI_MODEL"
    }

internal fun providerBaseUrlName(provider: DebugProvider): String =
    when (provider) {
        DebugProvider.ANTHROPIC -> "ANTHROPIC_BASE_URL"
        DebugProvider.OPENAI -> "OPENAI_BASE_URL"
    }

internal fun providerEndpointName(provider: DebugProvider): String =
    when (provider) {
        DebugProvider.ANTHROPIC -> "ANTHROPIC_ENDPOINT"
        DebugProvider.OPENAI -> "OPENAI_ENDPOINT"
    }

internal fun providerVariantName(provider: DebugProvider): String =
    when (provider) {
        DebugProvider.ANTHROPIC -> "ANTHROPIC_VARIANT"
        DebugProvider.OPENAI -> "OPENAI_VARIANT"
    }

internal fun providerServerSideSearchName(provider: DebugProvider): String =
    when (provider) {
        DebugProvider.ANTHROPIC -> "ANTHROPIC_SERVER_SIDE_SEARCH"
        DebugProvider.OPENAI -> "OPENAI_SERVER_SIDE_SEARCH"
    }

internal fun providerSearchCapabilityName(provider: DebugProvider): String =
    when (provider) {
        DebugProvider.ANTHROPIC -> "ANTHROPIC_SEARCH_CAPABILITY"
        DebugProvider.OPENAI -> "OPENAI_SEARCH_CAPABILITY"
    }

private fun variant(values: Map<String, String>, provider: DebugProvider, model: String): String {
    val configured = values[providerModelVariantName(provider, model)]
        ?: values[providerVariantName(provider)]?.takeIf { it.isNotBlank() }
    val variants = ModelVariantRegistry.variants(provider.wireName, model).map { it.id }
    return configured?.takeIf { it in variants }.orEmpty()
}

private fun providerModelVariantName(provider: DebugProvider, model: String): String =
    "${providerVariantName(provider)}$MODEL_VARIANT_KEY_SEPARATOR${model.preferenceKey()}"

private fun defaultModel(provider: DebugProvider): String =
    when (provider) {
        DebugProvider.ANTHROPIC -> DEFAULT_ANTHROPIC_MODEL
        DebugProvider.OPENAI -> DEFAULT_OPENAI_MODEL
    }

private fun defaultBaseUrl(provider: DebugProvider): String =
    when (provider) {
        DebugProvider.ANTHROPIC -> DEFAULT_ANTHROPIC_BASE_URL
        DebugProvider.OPENAI -> DEFAULT_OPENAI_BASE_URL
    }
