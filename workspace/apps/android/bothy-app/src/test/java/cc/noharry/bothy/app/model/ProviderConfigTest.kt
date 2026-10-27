package cc.noharry.bothy.app.model
import cc.noharry.bothy.app.BuildConfig
import cc.noharry.bothy.app.a2a.endpointLabel
import cc.noharry.bothy.app.image.IMAGE_MODEL_EMBEDDED_KEYS
import cc.noharry.bothy.app.mcp.AGENT_RELAY_URL_KEY
import cc.noharry.bothy.app.runtime.usesVisibleWebSearchTool
import cc.noharry.bothy.app.session.ModelProvider
import cc.noharry.bothy.app.voice.JEV_EMBEDDED_KEYS
import cc.noharry.bothy.app.voice.VOICE_EMBEDDED_KEYS
import cc.noharry.bothy.app.voice.tts.MIMO_TTS_CONFIG_KEYS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigTest {
    @Test
    fun buildConfigProviderConfigDeclaresMultipleProfiles() {
        val values = parseProviderConfigText(BuildConfig.ENV_MODEL_PROVIDER_CONFIG)
        val profiles = providerProfiles(values)
        val byId = profiles.associateBy { it.id }
        val configuredIds = values.getValue("AGENT_RUNTIME_PROVIDER_PROFILES").split(",")
        val selection = resolveProviderSelection(
            values = values,
            profiles = profiles,
            manualProfileEnabled = false,
            selectedProfileId = configuredIds.last(),
        )

        assertEquals(configuredIds.toSet(), byId.keys)
        assertEquals(values.getValue("AGENT_RUNTIME_DEFAULT_PROFILE"), selection.activeProfile.id)
        assertTrue(profiles.all { it.model.isNotBlank() && it.baseUrl.isNotBlank() })
        assertTrue(profiles.all { it.apiKey.isBlank() })
        assertEquals("openai-chat-options", byId.getValue("deepseek-api").searchCapability)
        assertEquals("anthropic-server-tool", byId.getValue("deepseek-anthropic").searchCapability)
        assertEquals("none", byId.getValue("no-hosted-search").searchCapability)
    }

    @Test
    fun buildConfigLocalOverridesOnlyDeclareProviderApiKeys() {
        val values = parseProviderConfigText(BuildConfig.ENV_MODEL_PROVIDER_LOCAL_OVERRIDES)
        val allowedLegacyKeys = setOf("OPENAI_API_KEY", "ANTHROPIC_API_KEY")

        assertTrue(values.keys.all { key ->
            key in allowedLegacyKeys ||
                key in MIMO_TTS_CONFIG_KEYS ||
                key in IMAGE_MODEL_EMBEDDED_KEYS ||
                key in VOICE_EMBEDDED_KEYS ||
                key in JEV_EMBEDDED_KEYS ||
                key == AGENT_RELAY_URL_KEY ||
                key == "AGENT_RUNTIME_DEFAULT_PROFILE" ||
                (key.startsWith("AGENT_RUNTIME_PROVIDER_PROFILE_") &&
                    listOf("LABEL", "PROVIDER", "ENDPOINT", "BASE_URL", "MODEL", "API_KEY", "STREAM", "SEARCH_CAPABILITY").any { key.endsWith("_$it") })
        })
    }

    @Test
    fun aWholeServiceFromLocalPropertiesJoinsTheListAndMayBecomeTheDefault() {
        val base = mapOf("AGENT_RUNTIME_PROVIDER_PROFILES" to "deepseek-api,deepseek-anthropic", "AGENT_RUNTIME_DEFAULT_PROFILE" to "deepseek-anthropic")
        val local = mapOf(
            "AGENT_RUNTIME_PROVIDER_PROFILE_NOHARRY_PROVIDER" to "openai",
            "AGENT_RUNTIME_PROVIDER_PROFILE_NOHARRY_BASE_URL" to "https://gateway.example.com/v1",
            "AGENT_RUNTIME_PROVIDER_PROFILE_NOHARRY_MODEL" to "deepseek-v4-flash",
            "AGENT_RUNTIME_PROVIDER_PROFILE_NOHARRY_API_KEY" to "sk-local",
            "AGENT_RUNTIME_DEFAULT_PROFILE" to "noharry",
        )
        val values = withLocalProfiles(base, local)
        assertEquals("deepseek-api,deepseek-anthropic,noharry", values["AGENT_RUNTIME_PROVIDER_PROFILES"])
        val profiles = providerProfiles(values)
        val noharry = profiles.single { it.id == "noharry" }
        assertEquals("https://gateway.example.com/v1", noharry.baseUrl)
        assertEquals("sk-local", noharry.apiKey)
        assertEquals("noharry", resolveProviderSelection(values, profiles, false, null).activeProfile.id)
    }

    @Test
    fun aHalfFilledLocalServiceNeverBecomesTheDefault() {
        val base = mapOf("AGENT_RUNTIME_PROVIDER_PROFILES" to "deepseek-api,deepseek-anthropic", "AGENT_RUNTIME_DEFAULT_PROFILE" to "deepseek-anthropic")
        val local = mapOf(
            "AGENT_RUNTIME_PROVIDER_PROFILE_NOHARRY_BASE_URL" to "https://gateway.example.com/v1",
            "AGENT_RUNTIME_DEFAULT_PROFILE" to "noharry",
        )
        val values = withLocalProfiles(base, local)
        assertEquals("deepseek-api,deepseek-anthropic", values["AGENT_RUNTIME_PROVIDER_PROFILES"])
        assertEquals("deepseek-anthropic", values["AGENT_RUNTIME_DEFAULT_PROFILE"])
    }

    @Test
    fun parsesJsonProviderProfiles() {
        val values = parseProviderConfigText(
            """
                {
                  "defaultProfile": "relay-openai",
                  "profiles": [
                    {
                      "id": "relay-openai",
                      "label": "Relay",
                      "provider": "openai",
                      "endpoint": "responses",
                      "baseUrl": "https://relay.example.com/v1",
                      "model": "relay-model",
                      "apiKey": "",
                      "serverSideSearch": true
                    }
                  ]
                }
            """.trimIndent(),
        )
        val profiles = providerProfiles(values)
        val selection = resolveProviderSelection(values, profiles, false, null)

        assertEquals("relay-openai", selection.activeProfile.id)
        assertEquals(ModelProvider.OPENAI, selection.activeProfile.provider)
        assertEquals(ProviderEndpoint.RESPONSES, selection.activeProfile.endpoint)
        assertEquals("https://relay.example.com/v1", selection.activeProfile.baseUrl)
        assertEquals("relay-model", selection.activeProfile.model)
        assertTrue(selection.activeProfile.serverSideSearch)
        assertEquals("openai-responses-tool", selection.activeProfile.searchCapability)
        assertEquals(true, ProviderConfig(
            values = values,
            sourcePath = null,
            expectedPaths = emptyList(),
            profiles = profiles,
            defaultProfileId = selection.defaultProfileId,
            activeProfileId = selection.activeProfile.id,
            manualProfileEnabled = false,
        ).streamEnabled)
    }

    @Test
    fun parsesExplicitProviderProfilesWithoutLegacyFallbacks() {
        val profiles = providerProfiles(profileValues())

        val byId = profiles.associateBy { it.id }
        assertEquals(setOf("pc-local", "deepseek"), byId.keys)
        assertEquals("PC Local", byId.getValue("pc-local").displayLabel)
        assertEquals(ModelProvider.OPENAI, byId.getValue("pc-local").provider)
        assertEquals(ProviderEndpoint.CHAT_COMPLETIONS, byId.getValue("pc-local").endpoint)
        assertEquals("http://localhost:18317/v1", byId.getValue("pc-local").baseUrl)
        assertEquals("local-model", byId.getValue("pc-local").model)
        assertEquals(true, byId.getValue("pc-local").streamEnabled)
        assertTrue(byId.getValue("pc-local").serverSideSearch)
        assertEquals("openai-chat-options", byId.getValue("pc-local").searchCapability)
        assertEquals(ModelProvider.ANTHROPIC, byId.getValue("deepseek").provider)
        assertEquals(ProviderEndpoint.MESSAGES, byId.getValue("deepseek").endpoint)
        assertTrue(byId.getValue("deepseek").serverSideSearch)
        assertEquals("anthropic-server-tool", byId.getValue("deepseek").searchCapability)
    }

    @Test
    fun legacyProviderEnvParsesHostedSearchAsDefaultOn() {
        val defaultProfile = legacyProfile(emptyMap(), ModelProvider.OPENAI)
        val deepSeekOpenAiProfile = legacyProfile(
            mapOf("OPENAI_BASE_URL" to "https://api.deepseek.com/v1"),
            ModelProvider.OPENAI,
        )
        val enabledProfile = legacyProfile(
            mapOf("OPENAI_SERVER_SIDE_SEARCH" to "true"),
            ModelProvider.OPENAI,
        )

        assertTrue(defaultProfile.serverSideSearch)
        assertEquals("openai-chat-options", deepSeekOpenAiProfile.searchCapability)
        assertTrue(enabledProfile.serverSideSearch)
        assertEquals(
            mapOf(
                "AGENT_RUNTIME_LIVE_PROVIDER" to "openai",
                "OPENAI_API_KEY" to "",
                "OPENAI_MODEL" to DEFAULT_OPENAI_MODEL,
                "OPENAI_BASE_URL" to DEFAULT_OPENAI_BASE_URL,
                "OPENAI_ENDPOINT" to "chat_completions",
                "OPENAI_SERVER_SIDE_SEARCH" to "true",
                "OPENAI_SEARCH_CAPABILITY" to "openai-chat-options",
            ),
            enabledProfile.toActiveValues(),
        )
    }

    @Test
    fun webFetchSuppressionOnlyAppliesToVisibleWebSearchTools() {
        val openAiChatOptions = ProviderProfile(
            id = "deepseek-api",
            label = "deepseek api",
            provider = ModelProvider.OPENAI,
            apiKey = "",
            model = "deepseek-v4-flash",
            baseUrl = "https://api.deepseek.com/v1",
            endpoint = ProviderEndpoint.CHAT_COMPLETIONS,
            searchCapability = "openai-chat-options",
        )
        val openAiResponses = openAiChatOptions.copy(
            id = "responses",
            endpoint = ProviderEndpoint.RESPONSES,
            searchCapability = "openai-responses-tool",
        )
        val anthropicServerTool = openAiChatOptions.copy(
            id = "anthropic",
            provider = ModelProvider.ANTHROPIC,
            endpoint = ProviderEndpoint.MESSAGES,
            searchCapability = "anthropic-server-tool",
        )

        assertFalse(openAiChatOptions.usesVisibleWebSearchTool())
        assertTrue(openAiResponses.usesVisibleWebSearchTool())
        assertTrue(anthropicServerTool.usesVisibleWebSearchTool())
        assertFalse(anthropicServerTool.copy(serverSideSearch = false).usesVisibleWebSearchTool())
        assertFalse(openAiChatOptions.copy(searchCapability = "none").usesVisibleWebSearchTool())
    }

    @Test
    fun configDefaultProfileWinsUntilManualSelectionIsEnabled() {
        val profiles = providerProfiles(profileValues())

        val defaultSelection = resolveProviderSelection(
            values = profileValues(),
            profiles = profiles,
            manualProfileEnabled = false,
            selectedProfileId = "deepseek",
        )
        val manualSelection = resolveProviderSelection(
            values = profileValues(),
            profiles = profiles,
            manualProfileEnabled = true,
            selectedProfileId = "deepseek",
        )

        assertEquals("pc-local", defaultSelection.defaultProfileId)
        assertEquals("pc-local", defaultSelection.activeProfile.id)
        assertEquals("deepseek", manualSelection.activeProfile.id)
    }

    @Test
    fun savedProfileOverridesRememberHostedSearchCapability() {
        val profiles = providerProfiles(profileValues())
        val updated = profiles.withSavedProfiles(
            mapOf(
                "deepseek" to ProviderProfileOverride(
                    baseUrl = "https://api.deepseek.com/anthropic",
                    serverSideSearch = "true",
                    searchCapability = "anthropic-server-tool",
                ),
            ),
        ).associateBy { it.id }

        assertTrue(updated.getValue("deepseek").serverSideSearch)
        assertEquals("anthropic-server-tool", updated.getValue("deepseek").searchCapability)
    }

    @Test
    fun explicitNoneSearchCapabilitySurvivesProfileParsing() {
        val profiles = providerProfiles(
            mapOf(
                "AGENT_RUNTIME_PROVIDER_PROFILES" to "no-search",
                "AGENT_RUNTIME_PROVIDER_PROFILE_NO_SEARCH_PROVIDER" to "openai",
                "AGENT_RUNTIME_PROVIDER_PROFILE_NO_SEARCH_BASE_URL" to "https://api.deepseek.com/v1",
                "AGENT_RUNTIME_PROVIDER_PROFILE_NO_SEARCH_MODEL" to "deepseek-v4-flash",
                "AGENT_RUNTIME_PROVIDER_PROFILE_NO_SEARCH_SEARCH_CAPABILITY" to "none",
            ),
        )

        assertTrue(profiles.single().serverSideSearch)
        assertEquals("none", profiles.single().searchCapability)
    }

    @Test
    fun explicitNoneSearchCapabilitySurvivesApiKeyOverride() {
        val profiles = providerProfiles(
            mapOf(
                "AGENT_RUNTIME_PROVIDER_PROFILES" to "no-search",
                "AGENT_RUNTIME_PROVIDER_PROFILE_NO_SEARCH_PROVIDER" to "openai",
                "AGENT_RUNTIME_PROVIDER_PROFILE_NO_SEARCH_BASE_URL" to "https://api.deepseek.com/v1",
                "AGENT_RUNTIME_PROVIDER_PROFILE_NO_SEARCH_MODEL" to "deepseek-v4-flash",
                "AGENT_RUNTIME_PROVIDER_PROFILE_NO_SEARCH_SEARCH_CAPABILITY" to "none",
            ),
        ).withSavedProfiles(
            mapOf("no_search" to ProviderProfileOverride(apiKey = "sk-test")),
        )

        assertEquals("sk-test", profiles.single().apiKey)
        assertEquals("none", profiles.single().searchCapability)
    }

    @Test
    fun legacyProviderEnvCanSelectDefaultProviderProfile() {
        val values = mapOf(
            "AGENT_RUNTIME_MODEL_PROVIDER" to "anthropic",
            "ANTHROPIC_API_KEY" to "key",
            "ANTHROPIC_MODEL" to "claude-test",
        )
        val profiles = providerProfiles(values)

        val selection = resolveProviderSelection(
            values = values,
            profiles = profiles,
            manualProfileEnabled = false,
            selectedProfileId = null,
        )

        assertEquals("anthropic", selection.defaultProfileId)
        assertEquals("claude-test", selection.activeProfile.model)
    }

    @Test
    fun localhostProfileDoesNotRequireUserEnteredApiKey() {
        val profile = ProviderProfile(
            id = "pc-local",
            label = "PC Local",
            provider = ModelProvider.OPENAI,
            apiKey = "",
            model = "local-model",
            baseUrl = "http://localhost:18317/v1",
        )
        val config = ProviderConfig(
            values = emptyMap(),
            sourcePath = null,
            expectedPaths = emptyList(),
            profiles = listOf(profile),
            defaultProfileId = profile.id,
            activeProfileId = profile.id,
            manualProfileEnabled = false,
        )

        assertTrue(config.isReady(profile))
        assertEquals("local", config.effectiveApiKey(profile))
        assertFalse(config.editState(profile).manualProfileEnabled)
    }

    @Test
    fun deepseekAnthropicInheritsApiKeyFromDeepseekApiWhenBlank() {
        val values = mapOf(
            "AGENT_RUNTIME_PROVIDER_PROFILES" to "deepseek-api,deepseek-anthropic",
            "AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_API_PROVIDER" to "openai",
            "AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_API_BASE_URL" to "https://api.deepseek.com/v1",
            "AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_API_MODEL" to "deepseek-v4-flash",
            "AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_API_API_KEY" to "sk-shared-deepseek",
            "AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_ANTHROPIC_PROVIDER" to "anthropic",
            "AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_ANTHROPIC_BASE_URL" to "https://api.deepseek.com/anthropic",
            "AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_ANTHROPIC_MODEL" to "deepseek-v4-flash",
            "AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_ANTHROPIC_API_KEY" to "",
        )
        val byId = providerProfiles(values).associateBy { it.id }

        assertEquals("sk-shared-deepseek", byId.getValue("deepseek-api").apiKey)
        assertEquals("sk-shared-deepseek", byId.getValue("deepseek-anthropic").apiKey)
    }

    @Test
    fun deepseekAnthropicKeepsExplicitApiKeyWhenPresent() {
        val values = mapOf(
            "AGENT_RUNTIME_PROVIDER_PROFILES" to "deepseek-api,deepseek-anthropic",
            "AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_API_PROVIDER" to "openai",
            "AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_API_BASE_URL" to "https://api.deepseek.com/v1",
            "AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_API_MODEL" to "deepseek-v4-flash",
            "AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_API_API_KEY" to "sk-openai-port",
            "AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_ANTHROPIC_PROVIDER" to "anthropic",
            "AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_ANTHROPIC_BASE_URL" to "https://api.deepseek.com/anthropic",
            "AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_ANTHROPIC_MODEL" to "deepseek-v4-flash",
            "AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_ANTHROPIC_API_KEY" to "sk-anthropic-port",
        )
        val byId = providerProfiles(values).associateBy { it.id }

        assertEquals("sk-openai-port", byId.getValue("deepseek-api").apiKey)
        assertEquals("sk-anthropic-port", byId.getValue("deepseek-anthropic").apiKey)
    }

    @Test
    fun profileOptionsExposeEndpointLabelsAndManualDeleteState() {
        val manual = ProviderProfile(
            id = "manual-relay-1",
            label = "Relay",
            provider = ModelProvider.OPENAI,
            apiKey = "key",
            model = "gpt-test",
            baseUrl = "https://relay.example.com/v1",
            endpoint = ProviderEndpoint.RESPONSES,
        )
        val option = manual.toOption(manual.id)

        assertEquals("Responses", option.endpointLabel)
        assertTrue(option.canDelete)
        assertTrue(option.selected)
    }

    private fun profileValues(): Map<String, String> =
        mapOf(
            "AGENT_RUNTIME_PROVIDER_PROFILES" to "pc-local,deepseek",
            "AGENT_RUNTIME_DEFAULT_PROFILE" to "pc-local",
            "AGENT_RUNTIME_PROVIDER_PROFILE_PC_LOCAL_LABEL" to "PC Local",
            "AGENT_RUNTIME_PROVIDER_PROFILE_PC_LOCAL_PROVIDER" to "openai",
            "AGENT_RUNTIME_PROVIDER_PROFILE_PC_LOCAL_ENDPOINT" to "chat_completions",
            "AGENT_RUNTIME_PROVIDER_PROFILE_PC_LOCAL_BASE_URL" to "http://localhost:18317/v1",
            "AGENT_RUNTIME_PROVIDER_PROFILE_PC_LOCAL_MODEL" to "local-model",
            "AGENT_RUNTIME_PROVIDER_PROFILE_PC_LOCAL_STREAM" to "true",
            "AGENT_RUNTIME_PROVIDER_PROFILE_PC_LOCAL_SERVER_SIDE_SEARCH" to "true",
            "AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_LABEL" to "DeepSeek Public",
            "AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_PROVIDER" to "anthropic",
            "AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_ENDPOINT" to "messages",
            "AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_BASE_URL" to "https://api.deepseek.com",
            "AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_MODEL" to "deepseek-reasoner",
            "AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_API_KEY" to "deepseek-key",
        )

}
