package cc.noharry.bothy.app

import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ProviderCreateParams
import cc.noharry.agent.appserver.protocol.ProviderEditState
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.bothy.app.model.CUSTOM_SERVICE
import cc.noharry.bothy.app.model.DEEPSEEK
import cc.noharry.bothy.app.model.MOONSHOT
import cc.noharry.bothy.app.model.OPENROUTER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The first launch's model key: the selected preset as it is, or a profile of its own for another provider. */
class OnboardingModelKeyTest {
    /** Bothy's DeepSeek preset, selected on a fresh install. */
    private val preset = ProviderEditState(
        model = "deepseek-flash", baseUrl = "https://api.deepseek.com/anthropic", profileId = "deepseek-anthropic",
        profileLabel = "deepseek anthropic", provider = ModelProvider.ANTHROPIC, endpoint = ProviderEndpoint.MESSAGES, service = "deepseek",
    )
    private val services = listOf(DEEPSEEK, MOONSHOT, OPENROUTER)

    @Test
    fun leftAsItIsTheKeyGoesToTheSelectedPreset() {
        val form = onboardingForm(preset, services)

        assertEquals("deepseek", form.service)
        assertTrue(form.readyToSave())
        assertEquals(OnboardingSave.Selected, form.onboardingSave(preset))
    }

    @Test
    fun anotherProviderGetsAProfileOfItsOwn() {
        val form = onboardingForm(preset, services)
        form.chooseService(MOONSHOT, "global")

        assertEquals(
            OnboardingSave.Create(
                ProviderCreateParams("Moonshot (Kimi)", ModelProvider.OPENAI, ProviderEndpoint.CHAT_COMPLETIONS, "", "kimi-k3.1-pro", "https://api.moonshot.ai/v1"),
                listOf("kimi-k3.1-pro", "kimi-k3.1-flash"),
            ),
            form.onboardingSave(preset),
        )
    }

    @Test
    fun aServiceWithoutADefaultModelAsksForOne() {
        val form = onboardingForm(preset, services)
        form.chooseService(OPENROUTER)
        assertFalse(form.readyToSave())

        form.typeModel("deepseek/deepseek-v4-flash")

        assertTrue(form.readyToSave())
        val save = form.onboardingSave(preset) as OnboardingSave.Create
        assertEquals("deepseek/deepseek-v4-flash", save.params.model)
        assertEquals(listOf("deepseek/deepseek-v4-flash"), save.models)
    }

    @Test
    fun withNoServicesThePresetShowsAsCustomAndStillTakesTheKey() {
        val form = onboardingForm(preset, emptyList())

        assertEquals(CUSTOM_SERVICE, form.service)
        assertEquals("https://api.deepseek.com/anthropic", form.baseUrl)
        assertEquals(OnboardingSave.Selected, form.onboardingSave(preset))
    }
}
