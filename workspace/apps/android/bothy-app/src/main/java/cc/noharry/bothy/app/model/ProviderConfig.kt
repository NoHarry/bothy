package cc.noharry.bothy.app.model
import android.content.Context
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelRef
import cc.noharry.agent.kernel.api.model.ServerSideSearchCapability
import cc.noharry.agent.model.AnthropicModelClient
import cc.noharry.agent.model.ModelVariantRegistry
import cc.noharry.agent.model.OpenAiModelClient
import cc.noharry.agent.transport.OkHttpHttpTransport
import cc.noharry.bothy.app.evidence.AppDiagnostics
import cc.noharry.bothy.app.session.ModelProvider
import cc.noharry.bothy.app.voice.tts.savedTtsValues

internal data class ProviderConfig(
    val values: Map<String, String>,
    val sourcePath: String?,
    val expectedPaths: List<String>,
    val profiles: List<ProviderProfile>,
    val defaultProfileId: String,
    val activeProfileId: String,
    val manualProfileEnabled: Boolean,
) {
    val activeProfile: ProviderProfile =
        profiles.firstOrNull { it.id == activeProfileId } ?: profiles.first()

    val provider: ModelProvider = activeProfile.provider

    val streamEnabled: Boolean =
        activeProfile.streamEnabled ?: activeProfile.endpoint.defaultStream ?: resolveStreamEnabled(values)

    fun client(
        profile: ProviderProfile,
        stream: Boolean,
        diagnostics: AppDiagnostics? = null,
    ): ModelClient =
        if (profile.isOnDevice()) onDeviceClient(profile, stream, diagnostics) else when (profile.provider) {
            ModelProvider.ANTHROPIC -> AnthropicModelClient(
                apiKeyProvider = { effectiveApiKey(profile) },
                model = profile.model,
                baseUrl = profile.baseUrl,
                stream = stream,
                serverSideSearch = profile.serverSideSearch,
                searchCapability = ServerSideSearchCapability.fromWireName(profile.searchCapability),
                transport = diagnostics?.transport(profile.provider) ?: OkHttpHttpTransport(),
            )
            ModelProvider.OPENAI -> OpenAiModelClient(
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
        provider: ModelProvider,
        stream: Boolean,
        diagnostics: AppDiagnostics? = null,
    ): ModelClient = client(profileForProvider(provider), stream, diagnostics)

    fun isReady(profile: ProviderProfile): Boolean =
        if (profile.isOnDevice()) {
            OnDeviceModel.isDownloaded(OnDeviceModel.spec(profile.model))
        } else {
            profile.model.isNotBlank() && effectiveApiKey(profile).isNotBlank()
        }

    fun isReady(provider: ModelProvider): Boolean =
        isReady(profileForProvider(provider))

    fun effectiveApiKey(profile: ProviderProfile): String =
        resolvedApiKey(profile)

    fun summary(profile: ProviderProfile): String {
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

    fun summary(provider: ModelProvider): String =
        summary(profileForProvider(provider))

    fun editState(profile: ProviderProfile): ProviderEditState =
        ProviderEditState(
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

    fun editState(provider: ModelProvider): ProviderEditState =
        editState(profileForProvider(provider))

    fun editState(provider: ModelProvider, selectedModel: String): ProviderEditState =
        editState(profileForProvider(provider).withModel(selectedModel))

    fun modelRef(profile: ProviderProfile): ModelRef =
        ModelVariantRegistry.resolve(
            explicit = ModelRef(profile.provider.wireName, profile.model, profile.variant.takeIf { it.isNotBlank() }),
        ) ?: ModelRef(profile.provider.wireName, profile.model)

    fun modelRef(provider: ModelProvider): ModelRef =
        modelRef(profileForProvider(provider))

    fun missingMessage(profile: ProviderProfile): String {
        if (profile.isOnDevice()) return OnDeviceModel.missingMessage(OnDeviceModel.spec(profile.model))
        if (profile.model.isBlank()) return "请在输入框旁选择模型；可先在设置中拉取或添加模型。"
        val paths = expectedPaths.joinToString(separator = "\n")
        return "Missing API key for ${profile.displayLabel}. Set it in Config or provide one of:\n$paths"
    }

    fun missingMessage(provider: ModelProvider): String =
        missingMessage(profileForProvider(provider))

    fun profileForProvider(provider: ModelProvider): ProviderProfile =
        if (activeProfile.provider == provider) {
            activeProfile
        } else {
            profiles.firstOrNull { it.provider == provider } ?: legacyProfile(values, provider)
        }

    /** OpenAI chat to the on-device server, at the address and key of whichever start of it is running. */
    private fun onDeviceClient(profile: ProviderProfile, stream: Boolean, diagnostics: AppDiagnostics?): ModelClient =
        OnDeviceModelClient(ready = { OnDeviceModel.ensureRunning(OnDeviceModel.spec(profile.model)) }) { server ->
            OpenAiModelClient(
                apiKeyProvider = { server.apiKey },
                model = profile.model,
                baseUrl = server.baseUrl,
                stream = stream,
                endpoint = ProviderEndpoint.CHAT_COMPLETIONS.wireName,
                serverSideSearch = false,
                searchCapability = ServerSideSearchCapability.NONE,
                transport = diagnostics?.transport(profile.provider) ?: OkHttpHttpTransport(),
            )
        }

    private fun resolvedApiKey(profile: ProviderProfile): String =
        profile.apiKey.takeIf { it.isNotBlank() }
            ?: DEFAULT_LOCAL_API_KEY.takeIf { profile.baseUrl.isLocalBaseUrl() }
            ?: ""

    companion object {
        fun load(context: Context): ProviderConfig {
            val paths = configFiles(context)
            val file = paths.firstOrNull { it.isFile }
            val buildValues = buildConfigValues()
            val fileValues = file?.readEnvFile().orEmpty()
            val savedProfileState = savedProfileState(context)
            val savedValues = savedProviderValues(context)
            // Settings → 语音 (播报 and the MiMo key on 密钥) saves its own values; they win over local.properties and the file.
            val baseValues = buildValues + fileValues + savedValues + savedTtsValues(context)
            val profiles = providerProfiles(baseValues)
                .withSavedProfiles(savedProfileState.overrides)
                .withSharedDeepSeekApiKeys()
                .withOnDeviceProfile()
            val selection = resolveProviderSelection(
                values = baseValues,
                profiles = profiles,
                manualProfileEnabled = savedProfileState.manualProfileEnabled,
                selectedProfileId = savedProfileState.selectedProfileId,
            )
            val values = baseValues + selection.activeProfile.toActiveValues()
            return ProviderConfig(
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
            provider: ModelProvider,
            apiKey: String,
            model: String,
            baseUrl: String,
        ) {
            val profile = load(context).profileForProvider(provider)
            saveProfileOverride(context, profile, apiKey, model, baseUrl)
        }

        fun saveProfileOverride(
            context: Context,
            profile: ProviderProfile,
            apiKey: String,
            model: String,
            baseUrl: String,
            endpoint: ProviderEndpoint = profile.endpoint,
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

        fun rememberHostedSearchDefaults(context: Context, profile: ProviderProfile) {
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
            provider: ModelProvider,
            endpoint: ProviderEndpoint,
            apiKey: String,
            model: String,
            baseUrl: String,
        ): ProviderConfig {
            val profile = ProviderProfile(
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

        fun deleteSavedProfile(context: Context, profileId: String): ProviderConfig {
            if (!profileId.isManualProfileId()) return load(context)
            val loaded = load(context)
            val selectedId = loaded.activeProfileId
            val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            PROFILE_FIELDS.forEach { field -> editor.remove(profilePrefName(profileId, field)) }
            if (selectedId == profileId) editor.putString(PREF_SELECTED_PROFILE_ID, loaded.defaultProfileId)
            editor.apply()
            return load(context)
        }

        fun saveVariantOverride(context: Context, profile: ProviderProfile, variant: String) {
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

        fun saveVariantOverride(context: Context, provider: ModelProvider, model: String, variant: String) {
            val profile = load(context).profileForProvider(provider).withModel(model)
            saveVariantOverride(context, profile, variant)
        }

        fun saveSelectedProfile(context: Context, profileId: String): ProviderConfig {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_MANUAL_PROFILE_ENABLED, true)
                .putString(PREF_SELECTED_PROFILE_ID, profileId.trim())
                .apply()
            return load(context)
        }

        fun saveManualProfileEnabled(context: Context, enabled: Boolean): ProviderConfig {
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
