package cc.noharry.bothy.sample

import android.content.Context
import cc.noharry.bothy.model.AnthropicModelClient
import cc.noharry.bothy.kernel.api.model.ModelClient
import cc.noharry.bothy.kernel.api.model.ModelRef
import cc.noharry.bothy.model.ModelVariantRegistry
import cc.noharry.bothy.model.OpenAiModelClient
import cc.noharry.bothy.kernel.api.model.ServerSideSearchCapability
import cc.noharry.bothy.transport.OkHttpHttpTransport

internal data class DebugProviderConfig(
    val values: Map<String, String>,
    val sourcePath: String?,
    val expectedPaths: List<String>,
    val profiles: List<DebugProviderProfile>,
    val defaultProfileId: String,
    val activeProfileId: String,
    val manualProfileEnabled: Boolean,
) {
    val activeProfile: DebugProviderProfile =
        profiles.firstOrNull { it.id == activeProfileId } ?: profiles.first()

    val provider: DebugProvider = activeProfile.provider

    val streamEnabled: Boolean =
        activeProfile.streamEnabled ?: activeProfile.endpoint.defaultStream ?: resolveDebugStreamEnabled(values)

    fun client(
        profile: DebugProviderProfile,
        stream: Boolean,
        diagnostics: DebugDiagnostics? = null,
    ): ModelClient =
        when (profile.provider) {
            DebugProvider.ANTHROPIC -> AnthropicModelClient(
                apiKeyProvider = { effectiveApiKey(profile) },
                model = profile.model,
                baseUrl = profile.baseUrl,
                stream = stream,
                serverSideSearch = profile.serverSideSearch,
                searchCapability = ServerSideSearchCapability.fromWireName(profile.searchCapability),
                transport = diagnostics?.transport(profile.provider) ?: OkHttpHttpTransport(),
            )
            DebugProvider.OPENAI -> OpenAiModelClient(
                apiKeyProvider = { effectiveApiKey(profile) },
                model = profile.model,
                baseUrl = profile.baseUrl,
                stream = stream,
                endpoint = profile.endpoint.wireName,
                serverSideSearch = profile.serverSideSearch,
                searchCapability = ServerSideSearchCapability.fromWireName(profile.searchCapability),
                transport = diagnostics?.transport(profile.provider) ?: OkHttpHttpTransport(),
            )
        }

    fun client(
        provider: DebugProvider,
        stream: Boolean,
        diagnostics: DebugDiagnostics? = null,
    ): ModelClient = client(profileForProvider(provider), stream, diagnostics)

    fun isReady(profile: DebugProviderProfile): Boolean =
        profile.model.isNotBlank() && effectiveApiKey(profile).isNotBlank()

    fun isReady(provider: DebugProvider): Boolean =
        isReady(profileForProvider(provider))

    fun effectiveApiKey(profile: DebugProviderProfile): String =
        resolvedApiKey(profile)

    fun summary(profile: DebugProviderProfile): String {
        val source = sourcePath ?: "missing"
        val variant = profile.variant.ifBlank { "default" }
        val providerLabel = if (profile.displayLabel == profile.provider.label) {
            profile.provider.label
        } else {
            "${profile.displayLabel} (${profile.provider.label})"
        }
        val capability = profile.searchCapability.ifBlank { "none" }
        val search = if (profile.serverSideSearch) " web_search=$capability" else " web_search=off"
        return "Config: $source | $providerLabel model=${profile.model} variant=$variant$search"
    }

    fun summary(provider: DebugProvider): String =
        summary(profileForProvider(provider))

    fun editState(profile: DebugProviderProfile): DebugProviderEditState =
        DebugProviderEditState(
            apiKey = profile.apiKey,
            model = profile.model,
            variant = profile.variant,
            baseUrl = profile.baseUrl,
            profileId = profile.id,
            profileLabel = profile.displayLabel,
            provider = profile.provider,
            endpoint = profile.endpoint,
            manualProfileEnabled = manualProfileEnabled,
            profileOptions = profiles.map { it.toOption(activeProfileId) },
        )

    fun editState(provider: DebugProvider): DebugProviderEditState =
        editState(profileForProvider(provider))

    fun editState(provider: DebugProvider, selectedModel: String): DebugProviderEditState =
        editState(profileForProvider(provider).withModel(selectedModel))

    fun modelRef(profile: DebugProviderProfile): ModelRef =
        ModelVariantRegistry.resolve(
            explicit = ModelRef(profile.provider.wireName, profile.model, profile.variant.takeIf { it.isNotBlank() }),
        ) ?: ModelRef(profile.provider.wireName, profile.model)

    fun modelRef(provider: DebugProvider): ModelRef =
        modelRef(profileForProvider(provider))

    fun missingMessage(profile: DebugProviderProfile): String {
        if (profile.model.isBlank()) return "请在输入框旁选择模型；可先在设置中拉取或添加模型。"
        val paths = expectedPaths.joinToString(separator = "\n")
        return "Missing API key for ${profile.displayLabel}. Set it in Config or provide one of:\n$paths"
    }

    fun missingMessage(provider: DebugProvider): String =
        missingMessage(profileForProvider(provider))

    fun profileForProvider(provider: DebugProvider): DebugProviderProfile =
        if (activeProfile.provider == provider) {
            activeProfile
        } else {
            profiles.firstOrNull { it.provider == provider } ?: legacyProfile(values, provider)
        }

    private fun resolvedApiKey(profile: DebugProviderProfile): String =
        profile.apiKey.takeIf { it.isNotBlank() }
            ?: DEFAULT_LOCAL_API_KEY.takeIf { profile.baseUrl.isLocalDebugBaseUrl() }
            ?: ""

    companion object {
        fun load(context: Context): DebugProviderConfig {
            val paths = configFiles(context)
            val file = paths.firstOrNull { it.isFile }
            val buildValues = buildConfigValues()
            val fileValues = file?.readEnvFile().orEmpty()
            val savedProfileState = savedProfileState(context)
            val savedValues = savedProviderValues(context)
            // Settings → 语音 (播报 and the MiMo key on 密钥) saves its own values; they win over local.properties and the file.
            val baseValues = buildValues + fileValues + savedValues + savedTtsValues(context)
            val profiles = debugProviderProfiles(baseValues)
                .withSavedProfiles(savedProfileState.overrides)
                .withSharedDeepSeekApiKeys()
            val selection = resolveDebugProviderSelection(
                values = baseValues,
                profiles = profiles,
                manualProfileEnabled = savedProfileState.manualProfileEnabled,
                selectedProfileId = savedProfileState.selectedProfileId,
            )
            val values = baseValues + selection.activeProfile.toActiveValues()
            return DebugProviderConfig(
                values = values,
                sourcePath = configSource(buildValues, file, savedValues, savedProfileState),
                expectedPaths = listOf(BUILD_CONFIG_EXPECTED_SOURCE) + paths.map { it.absolutePath },
                profiles = profiles,
                defaultProfileId = selection.defaultProfileId,
                activeProfileId = selection.activeProfile.id,
                manualProfileEnabled = savedProfileState.manualProfileEnabled,
            )
        }

        fun saveProviderOverride(
            context: Context,
            provider: DebugProvider,
            apiKey: String,
            model: String,
            baseUrl: String,
        ) {
            val profile = load(context).profileForProvider(provider)
            saveProfileOverride(context, profile, apiKey, model, baseUrl)
        }

        fun saveProfileOverride(
            context: Context,
            profile: DebugProviderProfile,
            apiKey: String,
            model: String,
            baseUrl: String,
            endpoint: DebugProviderEndpoint = profile.endpoint,
        ) {
            val profileId = profile.id
            val inferredSearchCapability = profile.searchCapability
                .takeIf {
                    it.isNotBlank() &&
                        endpoint == profile.endpoint &&
                        baseUrl.trim() == profile.baseUrl.trim()
                }
                ?: defaultSearchCapability(profile.provider, endpoint)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_MANUAL_PROFILE_ENABLED, true)
                .putString(PREF_SELECTED_PROFILE_ID, profileId)
                .putString(profilePrefName(profileId, PROFILE_FIELD_LABEL), profile.displayLabel)
                .putString(profilePrefName(profileId, PROFILE_FIELD_PROVIDER), profile.provider.wireName)
                .putString(profilePrefName(profileId, PROFILE_FIELD_API_KEY), apiKey.trim())
                .putString(profilePrefName(profileId, PROFILE_FIELD_MODEL), model.trim())
                .putString(profilePrefName(profileId, PROFILE_FIELD_BASE_URL), baseUrl.trim())
                .putString(profilePrefName(profileId, PROFILE_FIELD_ENDPOINT), endpoint.wireName)
                .putString(profilePrefName(profileId, PROFILE_FIELD_SERVER_SIDE_SEARCH), true.toString())
                .putString(profilePrefName(profileId, PROFILE_FIELD_SEARCH_CAPABILITY), inferredSearchCapability)
                .putString(providerKeyName(profile.provider), apiKey.trim())
                .putString(providerModelName(profile.provider), model.trim())
                .putString(providerBaseUrlName(profile.provider), baseUrl.trim())
                .putString(providerEndpointName(profile.provider), endpoint.wireName)
                .putString(providerServerSideSearchName(profile.provider), true.toString())
                .putString(providerSearchCapabilityName(profile.provider), inferredSearchCapability)
                .apply()
        }

        fun rememberHostedSearchDefaults(context: Context, profile: DebugProviderProfile) {
            val searchCapability = profile.searchCapability.takeIf { it.isNotBlank() }
                ?: defaultSearchCapability(profile.provider, profile.endpoint)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(profilePrefName(profile.id, PROFILE_FIELD_SERVER_SIDE_SEARCH), true.toString())
                .putString(profilePrefName(profile.id, PROFILE_FIELD_SEARCH_CAPABILITY), searchCapability)
                .putString(providerServerSideSearchName(profile.provider), true.toString())
                .putString(providerSearchCapabilityName(profile.provider), searchCapability)
                .apply()
        }

        fun saveNewProfile(
            context: Context,
            label: String,
            provider: DebugProvider,
            endpoint: DebugProviderEndpoint,
            apiKey: String,
            model: String,
            baseUrl: String,
        ): DebugProviderConfig {
            val profile = DebugProviderProfile(
                id = manualProfileId(label, provider),
                label = label.trim(),
                provider = provider,
                apiKey = apiKey.trim(),
                model = model.trim(),
                baseUrl = baseUrl.trim(),
                endpoint = endpoint,
            )
            saveProfileOverride(context, profile, profile.apiKey, profile.model, profile.baseUrl, profile.endpoint)
            return load(context)
        }

        fun deleteSavedProfile(context: Context, profileId: String): DebugProviderConfig {
            if (!profileId.isManualProfileId()) return load(context)
            val loaded = load(context)
            val selectedId = loaded.activeProfileId
            val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            PROFILE_FIELDS.forEach { field -> editor.remove(profilePrefName(profileId, field)) }
            if (selectedId == profileId) editor.putString(PREF_SELECTED_PROFILE_ID, loaded.defaultProfileId)
            editor.apply()
            return load(context)
        }

        fun saveVariantOverride(context: Context, profile: DebugProviderProfile, variant: String) {
            val selected = variant.trim()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_MANUAL_PROFILE_ENABLED, true)
                .putString(PREF_SELECTED_PROFILE_ID, profile.id)
                .let { editor ->
                    if (selected.isBlank()) {
                        editor.remove(profilePrefName(profile.id, PROFILE_FIELD_VARIANT))
                    } else {
                        editor.putString(profilePrefName(profile.id, PROFILE_FIELD_VARIANT), selected)
                    }
                }
                .remove(providerVariantName(profile.provider))
                .apply()
        }

        fun saveVariantOverride(context: Context, provider: DebugProvider, model: String, variant: String) {
            val profile = load(context).profileForProvider(provider).withModel(model)
            saveVariantOverride(context, profile, variant)
        }

        fun saveSelectedProfile(context: Context, profileId: String): DebugProviderConfig {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_MANUAL_PROFILE_ENABLED, true)
                .putString(PREF_SELECTED_PROFILE_ID, profileId.trim())
                .apply()
            return load(context)
        }

        fun saveManualProfileEnabled(context: Context, enabled: Boolean): DebugProviderConfig {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit().putBoolean(PREF_MANUAL_PROFILE_ENABLED, enabled)
            if (enabled && prefs.getString(PREF_SELECTED_PROFILE_ID, null).isNullOrBlank()) {
                editor.putString(PREF_SELECTED_PROFILE_ID, load(context).activeProfile.id)
            }
            editor.apply()
            return load(context)
        }
    }
}
