package cc.noharry.bothy.app

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ProviderCreateParams
import cc.noharry.agent.appserver.protocol.ProviderEditState
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.SettingsRequests
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleTheme
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.frontend.ServerAccess
import cc.noharry.bothy.app.model.DEEPSEEK
import cc.noharry.bothy.app.model.FakeModelServer
import cc.noharry.bothy.app.model.MOONSHOT
import cc.noharry.bothy.app.model.OPENROUTER
import cc.noharry.bothy.protocol.OnboardingRequests
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The setup's first step, as a person takes it: the provider, its region, the key, 保存并检查. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "w600dp-h1400dp-port", application = Application::class)
class OnboardingModelKeyUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val preset = ProviderEditState(
        model = "deepseek-flash", baseUrl = "https://api.deepseek.com/anthropic", profileId = "deepseek-anthropic",
        profileLabel = "deepseek anthropic", provider = ModelProvider.ANTHROPIC, endpoint = ProviderEndpoint.MESSAGES, service = "deepseek",
    )
    private val server = FakeModelServer(listOf(DEEPSEEK, MOONSHOT, OPENROUTER))

    @Before
    fun noKeyTyped() = OnboardingDraft.clear()

    private fun show() {
        compose.setContent {
            ConsoleTheme {
                CompositionLocalProvider(LocalServer provides ServerAccess(ServerState(configEditor = preset), server)) {
                    Column(Modifier.background(ConsoleColors.bg).padding(Dimens.spaceLg).testTag("step")) { ModelKeyStep() }
                }
            }
        }
    }

    @Test
    fun leftAsItIsTheKeyGoesToTheSelectedPreset() {
        show()
        compose.onNodeWithText("Create an API key at https://platform.deepseek.com/api_keys.").assertExists()
        compose.onNode(hasSetTextAction() and hasText("DeepSeek API key")).performTextInput("sk-deepseek")
        compose.onNodeWithText("Save and check").performClick()

        assertEquals(emptyList<ProviderCreateParams>(), server.sentFor(SettingsRequests.PROVIDER_CREATE))
        assertEquals(listOf("sk-deepseek"), server.sentFor(OnboardingRequests.SAVE_MODEL_KEY).map { it.text })
        compose.onNodeWithText("Ready to go", substring = true).assertExists()
    }

    @Test
    fun anotherProviderGetsItsOwnProfileThenTheKeyIsChecked() {
        show()
        compose.onNodeWithText("DeepSeek").performClick()
        compose.onNodeWithText("Moonshot (Kimi)").performClick()
        compose.onNodeWithText("Create an API key at https://platform.kimi.com/console/api-keys.", substring = true).assertExists()
        compose.onNodeWithText("International").performClick()
        compose.onNodeWithText(
            "Create an API key at https://platform.kimi.ai/console/api-keys. Each region has its own site, and a key works only in the region it was made in.",
        ).assertExists()
        save("onboarding-model-key.png")
        compose.onNode(hasSetTextAction() and hasText("Moonshot (Kimi) API key")).performTextInput("sk-kimi")
        compose.onNodeWithText("Save and check").performClick()

        assertEquals(
            listOf(ProviderCreateParams("Moonshot (Kimi)", ModelProvider.OPENAI, ProviderEndpoint.CHAT_COMPLETIONS, "sk-kimi", "kimi-k3.1-pro", "https://api.moonshot.ai/v1")),
            server.sentFor(SettingsRequests.PROVIDER_CREATE),
        )
        val models = server.sentFor(SettingsRequests.PROVIDER_SAVE_MODELS).single()
        assertEquals("manual-created", models.editor.profileId)
        assertEquals(listOf("kimi-k3.1-pro", "kimi-k3.1-flash"), models.models)
        assertEquals(listOf("sk-kimi"), server.sentFor(OnboardingRequests.SAVE_MODEL_KEY).map { it.text })
    }

    private fun save(name: String) {
        val bitmap = compose.onNodeWithTag("step").captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
