package cc.noharry.bothy.sample

import org.junit.Assert.*
import org.junit.Test

class DebugProviderFormStateTest {
    @Test fun manualModelAddsToCatalogWithoutSelectingIt() {
        val initial = ProviderDraft.new()
        val form = ProviderFormState(initial)
        form.label = "Manual provider"
        form.updateBaseUrl("https://example.test/v1")
        assertTrue(form.canSave(isNew = true))
        form.pendingModel = " private-model "
        assertEquals(listOf("private-model"), form.toDraft(initial).modelOptions)
        assertEquals("", form.toDraft(initial).model)
        form.addPendingModel()
        assertEquals(listOf("private-model"), form.modelOptions)
        assertEquals("", form.pendingModel)
    }

    @Test fun connectionEditsPreserveManualModelsAndComposerSelection() {
        val initial = ProviderDraft.new().copy(model = "current")
        val form = ProviderFormState(initial)
        form.pendingModel = "manual"
        form.addPendingModel()
        form.addModels(listOf("public", "public", " "))
        form.updateApiKey("placeholder")
        form.updateBaseUrl("https://example.test/v1")
        form.syncModelOptions(initial.copy(apiKey = "placeholder", baseUrl = form.baseUrl, modelOptions = listOf("latest")))
        assertEquals(listOf("current", "manual", "public", "latest"), form.modelOptions)
        assertEquals("current", form.toDraft(initial).model)
    }

    @Test fun fetchedModelsReplaceTheListAndARetiredSelectionIsUnselected() {
        // DeepSeek's official list on 2026-10-19: deepseek-flash and deepseek-v4-pro; deepseek-v4-flash is gone.
        val initial = ProviderDraft.new().copy(model = "deepseek-v4-flash", modelOptions = listOf("deepseek-v4-flash"))
        val form = ProviderFormState(initial)
        form.pendingModel = "manual"
        form.addPendingModel()

        assertEquals("deepseek-v4-flash", form.useFetchedModels(listOf("deepseek-flash", "deepseek-v4-pro", "deepseek-flash", " ")))
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), form.modelOptions)
        assertEquals("", form.toDraft(initial).model)
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), form.toDraft(initial).modelOptions)
        assertTrue(form.windowInputs.containsKey("deepseek-flash"))

        val listed = ProviderFormState(ProviderDraft.new().copy(model = "deepseek-v4-pro", modelOptions = listOf("old", "deepseek-v4-pro")))
        assertNull(listed.useFetchedModels(listOf("deepseek-flash", "deepseek-v4-pro")))
        assertEquals("deepseek-v4-pro", listed.model)
        // A service that lists nothing keeps the list it had.
        assertNull(listed.useFetchedModels(emptyList()))
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), listed.modelOptions)
    }

    @Test fun invalidCapacityInAnyRowPreventsSaveAndValuesStayIndependent() {
        val form = ProviderFormState(ProviderDraft.new().copy(label = "test", baseUrl = "https://example.test"))
        form.addModels(listOf("a", "b"))
        form.windowInputs["a"] = "64K"
        form.windowInputs["b"] = "oops"
        assertFalse(form.canSave(true))
        form.windowInputs["b"] = "1M"
        assertTrue(form.canSave(true))
        assertEquals("64K", form.windowInputs["a"])
    }

    @Test fun createdProfileRouteUsesReturnedIdentityAndCancelReturnsToList() {
        val adding = DebugSettingsNavigationState().openNewProvider()
        assertEquals(DebugSettingsPage.PROVIDERS, adding.back()?.page)
        val saved = adding.openProvider("manual-created")
        assertEquals(DebugSettingsPage.PROVIDER_DETAIL, saved.page)
        assertEquals("manual-created", saved.requestedProfileId)
    }
    @Test fun typeChangesChooseCompatibleEndpointWithoutDiscardingModelDrafts() {
        val initial = ProviderDraft.new().copy(model = "current", endpoint = DebugProviderEndpoint.RESPONSES)
        val form = ProviderFormState(initial)
        form.pendingModel = "pending"
        form.windowInputs["current"] = "1M"
        form.selectProvider(DebugProvider.OPENAI)
        assertEquals(DebugProviderEndpoint.RESPONSES, form.endpoint)
        form.selectProvider(DebugProvider.ANTHROPIC)
        assertEquals(DebugProviderEndpoint.MESSAGES, form.endpoint)
        assertEquals(listOf("current"), form.modelOptions)
        assertEquals("pending", form.pendingModel)
        assertEquals("1M", form.windowInputs["current"])
        assertEquals("current", form.model)
        form.selectProvider(DebugProvider.OPENAI)
        assertEquals(DebugProviderEndpoint.CHAT_COMPLETIONS, form.endpoint)
    }

}
