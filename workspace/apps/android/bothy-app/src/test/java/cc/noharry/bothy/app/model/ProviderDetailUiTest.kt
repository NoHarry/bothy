package cc.noharry.bothy.app.model

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ModelSettingsRequests
import cc.noharry.agent.appserver.protocol.ProviderCreateParams
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.ServiceView
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.bothy.app.console.ConsoleTheme
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.frontend.ServerAccess
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 设置 → 模型 → 添加服务 and a saved service's page, as a person fills them in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1280dp-h2000dp-land", application = Application::class)
class ProviderDetailUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val recorded = RecordedActions()

    private fun show(services: List<ServiceView>, draft: ProviderDraft = ProviderDraft.new()): FakeModelServer {
        val server = FakeModelServer(services)
        compose.setContent {
            ConsoleTheme {
                CompositionLocalProvider(LocalServer provides ServerAccess(ServerState(connected = true), server)) {
                    ProviderDetail(draft, recorded.actions, isNew = draft.id == "new", onDone = {})
                }
            }
        }
        return server
    }

    @Test
    fun pickingAServiceAndItsRegionLeavesOnlyTheKeyToPaste() {
        val server = show(listOf(DEEPSEEK, MOONSHOT))

        compose.onNodeWithText("Choose a provider").performClick()
        compose.onNodeWithText("Moonshot (Kimi)").performClick()
        compose.onNodeWithText("https://api.moonshot.cn/v1").assertExists()
        compose.onNodeWithText("International").performClick()
        compose.onNodeWithText("https://api.moonshot.ai/v1").assertExists()
        compose.onNodeWithText("Create an API key at", substring = true).assertExists()
        compose.onNode(hasSetTextAction() and hasText("API key")).performTextInput("sk-kimi")
        compose.onNodeWithText("Save").performClick()

        assertEquals(
            listOf(ProviderCreateParams("Moonshot (Kimi)", ModelProvider.OPENAI, ProviderEndpoint.CHAT_COMPLETIONS, "sk-kimi", "kimi-k3.1-pro", "https://api.moonshot.ai/v1")),
            recorded.created,
        )
        val (editor, models) = recorded.savedModels.single()
        assertEquals("manual-created", editor.profileId)
        assertEquals(listOf("kimi-k3.1-pro", "kimi-k3.1-flash"), models)
        assertEquals(listOf("kimi-k3.1-pro", "kimi-k3.1-flash"), server.sentFor(ModelSettingsRequests.MODELS_SAVE).single().models.map { it.model })
    }

    @Test
    fun customStillTypesEverything() {
        show(listOf(DEEPSEEK))

        compose.onNodeWithText("Choose a provider").performClick()
        compose.onNodeWithText("Custom").performClick()
        compose.onNode(hasSetTextAction() and hasText("Service name")).performTextInput("Bench relay")
        compose.onNode(hasSetTextAction() and hasText("Base URL")).performTextInput("https://relay.example.test/v1")
        compose.onNode(hasSetTextAction() and hasText("API key")).performTextInput("sk-relay")
        compose.onNodeWithText("Save").performClick()

        assertEquals(
            listOf(ProviderCreateParams("Bench relay", ModelProvider.OPENAI, ProviderEndpoint.CHAT_COMPLETIONS, "sk-relay", "", "https://relay.example.test/v1")),
            recorded.created,
        )
    }

    @Test
    fun withNoServicesTheFormOpensOnCustom() {
        show(emptyList())

        compose.onNodeWithText("Choose a provider").assertDoesNotExist()
        compose.onNodeWithText("Provider").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("Service name")).performTextInput("Local")
        compose.onNode(hasSetTextAction() and hasText("Base URL")).performTextInput("http://localhost:18317/v1")
        compose.onNodeWithText("Save").performClick()

        assertEquals("http://localhost:18317/v1", recorded.created.single().baseUrl)
    }

    @Test
    fun aSavedProfileNamesItsServiceAndSavesTheOutputLimit() {
        val saved = ProviderDraft.new().copy(
            id = "deepseek-api", label = "deepseek api", baseUrl = "https://api.deepseek.com", model = "deepseek-flash",
            modelOptions = listOf("deepseek-flash"), service = "deepseek", hasSavedKey = true,
        )
        val server = show(listOf(DEEPSEEK, MOONSHOT), saved)

        compose.onNodeWithText("DeepSeek").assertExists()
        compose.onNodeWithText("A DeepSeek key can check its balance").assertExists()
        compose.onNodeWithText("Default 32,000 · at most 64,000").assertExists()
        compose.onNode(hasSetTextAction() and hasText("Output limit (tokens)")).performTextReplacement("8K")
        compose.onNodeWithText("Save").performClick()

        assertEquals(listOf("https://api.deepseek.com"), recorded.savedConnections)
        val settings = server.sentFor(ModelSettingsRequests.MODELS_SAVE).single()
        assertEquals("deepseek-api", settings.profile.profileId)
        assertEquals(8_000L, settings.models.single().outputOverride)
    }
}
