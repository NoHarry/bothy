package cc.noharry.bothy.app.model

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import cc.noharry.agent.appserver.protocol.ModelFacts
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ModelSettingsView
import cc.noharry.agent.appserver.protocol.ProviderCreateParams
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adding a service by its provider: what a choice fills in, and the profile it creates. */
class ServiceChoiceTest {
    @Test
    fun pickingAServiceFillsTheFormInAndCreatesThatProfile() {
        val form = ProviderFormState(ProviderDraft.new())
        assertNull("nothing chosen yet", form.service)

        form.chooseService(DEEPSEEK)
        form.updateApiKey(" sk-typed ")

        assertEquals("deepseek", form.service)
        assertEquals(listOf("deepseek-flash", "deepseek-pro"), form.modelOptions)
        assertTrue(form.canSave(isNew = true))
        assertEquals(
            ProviderCreateParams("DeepSeek", ModelProvider.OPENAI, ProviderEndpoint.CHAT_COMPLETIONS, "sk-typed", "deepseek-flash", "https://api.deepseek.com"),
            form.createParams(),
        )
    }

    @Test
    fun theRegionChoiceChangesTheBaseUrl() {
        val form = ProviderFormState(ProviderDraft.new())
        form.chooseService(MOONSHOT)
        assertEquals(listOf("cn", "global"), MOONSHOT.regions())
        assertEquals("cn", form.region)
        assertEquals("https://api.moonshot.cn/v1", form.baseUrl)

        form.chooseRegion("global")

        assertEquals("global", form.region)
        assertEquals("https://api.moonshot.ai/v1", form.createParams().baseUrl)
        assertEquals("kimi-k3.1-pro", form.createParams().model)
        assertEquals(listOf(""), DEEPSEEK.regions())
    }

    @Test
    fun theEndpointKindFollowsTheServicesAddressUntilItIsTypedOver() {
        val form = ProviderFormState(ProviderDraft.new())
        form.chooseService(DEEPSEEK)

        form.selectProvider(ModelProvider.ANTHROPIC)
        assertEquals(ProviderEndpoint.MESSAGES, form.endpoint)
        assertEquals("https://api.deepseek.com/anthropic", form.baseUrl)

        form.updateBaseUrl("https://relay.example.test/v1")
        form.selectProvider(ModelProvider.OPENAI)
        assertEquals(ProviderEndpoint.CHAT_COMPLETIONS, form.endpoint)
        assertEquals("a relay's address stays", "https://relay.example.test/v1", form.baseUrl)
    }

    @Test
    fun customIsTheFormAsTypedAndANameTypedIsKept() {
        val form = ProviderFormState(ProviderDraft.new())
        form.chooseService(DEEPSEEK)
        form.chooseService(MOONSHOT)
        assertEquals("the next choice replaces the name the last one filled in", "Moonshot (Kimi)", form.label)

        form.chooseCustom()
        assertEquals("", form.service)
        assertEquals("", form.label)
        assertEquals("", form.baseUrl)
        assertEquals(emptyList<String>(), form.modelOptions)
        assertFalse(form.canSave(isNew = true))

        form.label = "My relay"
        form.updateBaseUrl(" https://relay.example.test/v1 ")
        form.updateApiKey("sk-relay")
        form.chooseService(DEEPSEEK)
        assertEquals("a typed name stays", "My relay", form.label)
        form.chooseCustom()
        form.updateBaseUrl(" https://relay.example.test/v1 ")
        assertTrue(form.canSave(isNew = true))
        assertEquals(
            ProviderCreateParams("My relay", ModelProvider.OPENAI, ProviderEndpoint.CHAT_COMPLETIONS, "sk-relay", "", "https://relay.example.test/v1"),
            form.createParams(),
        )
    }

    @Test
    fun aSavedProfileFollowsTheServiceTheServerFoundForIt() {
        val saved = ProviderDraft.new().copy(id = "kimi", baseUrl = "https://api.moonshot.ai/v1", service = "moonshot", model = "kimi-k3.1-pro")
        val form = ProviderFormState(saved)

        form.knownAs(listOf(DEEPSEEK, MOONSHOT).named(form.service))

        assertEquals("global", form.region)
        assertEquals("https://api.moonshot.ai/v1", form.baseUrl)
        assertNull(listOf(DEEPSEEK).named(""))
        assertNull(emptyList<cc.noharry.agent.appserver.protocol.ServiceView>().named("deepseek"))
    }

    @Test
    fun theOutputLimitIsReadAndSavedPerModel() {
        val form = ProviderFormState(ProviderDraft.new())
        form.chooseService(DEEPSEEK)
        form.applySettings(
            listOf(
                ModelSettingsView("deepseek-flash", outputOverride = 8_000, facts = ModelFacts(maxOutputTokens = 64_000, requestOutputTokens = 32_000)),
                ModelSettingsView("deepseek-pro"),
            ),
        )
        assertEquals("8000", form.outputInputs["deepseek-flash"])
        assertEquals(32_000L, form.defaultOutputs["deepseek-flash"])
        assertEquals(64_000L, form.maxOutputs["deepseek-flash"])
        assertNull(form.defaultOutputs["deepseek-pro"])

        form.outputInputs["deepseek-flash"] = "16K"
        assertEquals(listOf(16_000L, null), form.modelSettings().map { it.outputOverride })

        form.outputInputs["deepseek-pro"] = "lots"
        assertFalse(form.canSave(isNew = true))
        form.outputInputs["deepseek-pro"] = ""
        assertTrue(form.canSave(isNew = true))
    }

    @Test
    fun aKeyHintsAddressesAreLinksWithoutTheSentencesPunctuation() {
        val hint = linkedText(MOONSHOT.keyHint, SpanStyle())
        val links = hint.getLinkAnnotations(0, hint.length).map { (it.item as LinkAnnotation.Url).url }

        assertEquals(listOf("https://platform.kimi.com/console/api-keys", "https://platform.kimi.ai/console/api-keys"), links)
        assertEquals(MOONSHOT.keyHint, hint.text)
    }
}
