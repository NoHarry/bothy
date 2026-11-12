package cc.noharry.bothy.app.model

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleTheme
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.frontend.ServerAccess
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The service form at a phone's width and a tablet pane's: a model's two limits stay readable side by side on a phone
 * and share the model's line on a tablet. The shots land in build/screenshots for a look.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], application = Application::class)
class ProviderFormShotsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(draft: ProviderDraft) {
        val server = FakeModelServer(listOf(DEEPSEEK, MOONSHOT, OPENROUTER))
        compose.setContent {
            ConsoleTheme {
                CompositionLocalProvider(LocalServer provides ServerAccess(ServerState(connected = true), server)) {
                    Box(Modifier.fillMaxSize().background(ConsoleColors.bg).padding(Dimens.spaceMd).testTag("form")) {
                        ProviderDetail(draft, RecordedActions().actions, isNew = draft.id == "new", onDone = {})
                    }
                }
            }
        }
    }

    private val saved = ProviderDraft.new().copy(
        id = "kimi", label = "Moonshot (Kimi)", baseUrl = "https://api.moonshot.cn/v1", model = "kimi-k3.1-pro",
        modelOptions = listOf("kimi-k3.1-pro", "kimi-k3.1-flash"), service = "moonshot", hasSavedKey = true,
    )

    @Test
    @Config(qualifiers = "w360dp-h1600dp-port")
    fun addingAServiceOnAPhone() {
        show(ProviderDraft.new())
        compose.onNodeWithText("Choose a provider").performClick()
        compose.onNodeWithText("Moonshot (Kimi)").performClick()
        compose.onNodeWithText("China").assertExists()
        save("provider-new-phone.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h1600dp-port")
    fun aSavedServiceOnAPhone() {
        show(saved)
        val output = compose.onAllNodes(hasSetTextAction() and hasText("Output limit (tokens)")).onFirst().fetchSemanticsNode().boundsInRoot
        assertTrue("the output limit field is ${output.width} px wide", with(compose.density) { output.width.toDp() } >= 120.dp)
        save("provider-saved-phone.png")
    }

    @Test
    @Config(qualifiers = "w900dp-h1400dp-land")
    fun onATablet() {
        show(saved)
        save("provider-saved-tablet.png")
    }

    private fun save(name: String) {
        val bitmap = compose.onNodeWithTag("form").captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
