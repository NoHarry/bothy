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
class DebugProviderModelCatalogTest {
    @Test fun catalogPersistsForComposerAndNeverSelectsAModelByItself() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = DebugProviderConfig.saveNewProfile(context, "Catalog", DebugProvider.OPENAI,
            DebugProviderEndpoint.CHAT_COMPLETIONS, "", "", "http://127.0.0.1:9/v1")
        val editor = config.editState(config.activeProfile)
        assertEquals("", editor.model)
        assertFalse(config.isReady(config.activeProfile))
        assertTrue(config.missingMessage(config.activeProfile).contains("选择模型"))
        saveDebugProviderModels(context, editor, listOf(" fetched ", "manual", "fetched", ""))
        val restored = DebugProviderConfig.load(context)
        val controller = DebugModelOptionsController {}
        controller.refresh(context, restored.activeProfile, restored)
        assertEquals(listOf("fetched", "manual"), controller.options(restored.activeProfile, restored.activeProfile.model))
        assertEquals("", restored.activeProfile.model)
        val selected = requireNotNull(saveSelectedDebugModel(context, restored.activeProfile, restored, "manual"))
        assertEquals("manual", selected.activeProfile.model)
        assertTrue(selected.isReady(selected.activeProfile))
        assertEquals(listOf("fetched", "manual"), loadDebugProviderModels(context, editor))
        DebugProviderConfig.deleteSavedProfile(context, editor.profileId)
        deleteDebugProviderModels(context, editor.profileId)
    }

    @Test fun catalogIsScopedByProviderConnectionAndAliasDeletionRemovesOnlyItsCatalogs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val a = DebugProviderEditState("", "", "", "https://example.test", profileId = "manual-one")
        val b = a.copy(profileId = "manual-two")
        saveDebugProviderModels(context, a, listOf("a"))
        saveDebugProviderModels(context, b, listOf("b"))
        assertEquals(listOf("a"), loadDebugProviderModels(context, a.copy(profileId = "manual_one")))
        assertTrue(loadDebugProviderModels(context, a.copy(baseUrl = "https://other.test")).isEmpty())
        assertTrue(loadDebugProviderModels(context, a.copy(endpoint = DebugProviderEndpoint.RESPONSES)).isEmpty())
        deleteDebugProviderModels(context, "manual_one")
        assertTrue(loadDebugProviderModels(context, a).isEmpty())
        assertEquals(listOf("b"), loadDebugProviderModels(context, b))
    }
}
