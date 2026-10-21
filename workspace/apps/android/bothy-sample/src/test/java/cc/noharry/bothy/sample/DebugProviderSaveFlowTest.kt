package cc.noharry.bothy.sample

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DebugProviderSaveFlowTest {
    @Test fun keylessManualProfileIsSelectedAfterSaveReloadAndCanBeDeleted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val before = DebugProviderConfig.load(context)
        val created = DebugProviderConfig.saveNewProfile(
            context, "Manual smoke", DebugProvider.OPENAI, DebugProviderEndpoint.CHAT_COMPLETIONS,
            "", "private-model", "http://127.0.0.1:9/v1",
        )
        val profile = created.activeProfile
        assertEquals("Manual smoke", profile.label)
        assertEquals("private-model", profile.model)
        assertTrue(profile.id.isManualProfileId())
        assertEquals(profile.id, DebugProviderConfig.load(context).activeProfile.id)
        assertEquals(before.profiles.size + 1, created.profiles.size)
        assertEquals(before.profiles.size, DebugProviderConfig.deleteSavedProfile(context, profile.id).profiles.size)
    }

    @Test fun savedPreferenceAliasDoesNotDuplicatePackagedProfile() {
        val profile = DebugProviderProfile("deepseek-api", "DeepSeek", DebugProvider.OPENAI, "", "model", "https://example.test")
        val profiles = listOf(profile).withSavedProfiles(mapOf(
            "deepseek_api" to DebugProviderProfileOverride(provider = DebugProvider.OPENAI, apiKey = "placeholder", baseUrl = "https://example.test"),
        ))
        assertEquals(1, profiles.size)
        assertEquals("deepseek-api", profiles.single().id)
        assertEquals("placeholder", profiles.single().apiKey)
        val selection = resolveDebugProviderSelection(emptyMap(), profiles, true, "deepseek_api")
        assertEquals("deepseek-api", selection.activeProfile.id)
    }
    @Test fun savedProviderTypeAndEndpointRoundTripWithoutChangingItsIdentityOrModel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val created = DebugProviderConfig.saveNewProfile(context, "Switch protocol", DebugProvider.OPENAI,
            DebugProviderEndpoint.CHAT_COMPLETIONS, "placeholder", "existing-model", "https://example.test")
        val original = created.activeProfile
        val anthropic = saveDebugProviderOverride(context, original, created,
            endpoint = DebugProviderEndpoint.MESSAGES, selectedProvider = DebugProvider.ANTHROPIC)
        assertEquals(original.id, anthropic.activeProfile.id)
        assertEquals(DebugProvider.ANTHROPIC, anthropic.activeProfile.provider)
        assertEquals(DebugProviderEndpoint.MESSAGES, anthropic.activeProfile.endpoint)
        assertEquals("anthropic-server-tool", anthropic.activeProfile.searchCapability)
        assertEquals(original.apiKey, anthropic.activeProfile.apiKey)
        assertEquals(original.baseUrl, anthropic.activeProfile.baseUrl)
        assertEquals(original.model, anthropic.activeProfile.model)
        saveDebugProviderOverride(context, anthropic.activeProfile, anthropic,
            endpoint = DebugProviderEndpoint.RESPONSES, selectedProvider = DebugProvider.OPENAI)
        val restored = DebugProviderConfig.load(context).activeProfile
        assertEquals(original.id, restored.id)
        assertEquals(DebugProvider.OPENAI, restored.provider)
        assertEquals(DebugProviderEndpoint.RESPONSES, restored.endpoint)
        assertEquals("openai-responses-tool", restored.searchCapability)
        assertEquals(original.model, restored.model)
        DebugProviderConfig.deleteSavedProfile(context, original.id)
    }

}
