package cc.noharry.bothy.app.usage

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.CNY
import cc.noharry.agent.appserver.protocol.ModelPrice
import cc.noharry.agent.appserver.protocol.ModelUsage
import cc.noharry.agent.appserver.protocol.PriceBook
import cc.noharry.agent.appserver.protocol.USD
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleTheme
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.frontend.ServerAccess
import cc.noharry.bothy.app.model.DEEPSEEK
import cc.noharry.bothy.app.model.FakeModelServer
import cc.noharry.bothy.app.model.MOONSHOT
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 用量 → 模型价格 with a price in each currency: every row says where its price is from in the service's name, with the
 * region in words, and on a phone the fields keep a readable width with the switch and buttons under them. The shots
 * land in build/screenshots for a look.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], application = Application::class)
class PriceTableUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val book = PriceBook(
        mapOf(
            "deepseek-flash" to ModelPrice(1.0, 4.0, 0.02, source = "deepseek", currency = CNY, asOf = "2026-11-01"),
            "kimi-k3.1-pro" to ModelPrice(0.6, 2.5, 0.15, source = "moonshot", currency = USD, asOf = "2026-10-30", region = "global"),
            "my-model" to ModelPrice(0.5, 1.5, manual = true, currency = CNY),
        ),
        usdToCny = 7.1,
    )
    private val usage = mapOf("2026-11-11" to mapOf("unpriced-model" to ModelUsage(1, 1_000, outputTokens = 100)))

    private fun show() {
        compose.setContent {
            ConsoleTheme {
                CompositionLocalProvider(LocalServer provides ServerAccess(ServerState(connected = true), FakeModelServer(emptyList()))) {
                    Column(
                        Modifier.testTag("prices").background(ConsoleColors.bg).verticalScroll(rememberScrollState()).padding(Dimens.spaceMd),
                        verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
                    ) { UsagePricesTab(usage, book, listOf(DEEPSEEK, MOONSHOT)) }
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h1400dp-land")
    fun onATabletEachPriceIsOneLine() {
        show()
        rowsSayWhereTheirPricesAreFrom()
        val field = width(hasText("Input"))
        assertTrue("an input field is $field wide", field >= 180.dp)
        assertTrue("Save sits beside the fields", saveIsBesideTheFields())
        save("prices-tablet.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h1400dp-port")
    fun onAPhoneTheFieldsKeepTheirWidthAndTheButtonsGoUnder() {
        show()
        rowsSayWhereTheirPricesAreFrom()
        val field = width(hasText("Cache hits"))
        assertTrue("a price field is $field wide", field >= 80.dp)
        assertFalse("Save goes under the fields", saveIsBesideTheFields())
        compose.onAllNodesWithText("Use synced price").onFirst().assertExists()
        save("prices-phone.png")
    }

    private fun rowsSayWhereTheirPricesAreFrom() {
        compose.onNodeWithText("DeepSeek's list of 2026-11-01 · CNY").assertExists()
        compose.onNodeWithText("Moonshot (Kimi)'s list of 2026-10-30 · USD (International)").assertExists()
        compose.onNodeWithText("Entered by hand · CNY").assertExists()
        compose.onAllNodesWithText("¥").onFirst().assertExists()
        compose.onAllNodesWithText("$").onFirst().assertExists()
    }

    private fun width(label: androidx.compose.ui.test.SemanticsMatcher): Dp {
        val bounds = compose.onAllNodes(hasSetTextAction() and label).onFirst().fetchSemanticsNode().boundsInRoot
        return with(compose.density) { bounds.width.toDp() }
    }

    /** Whether the first row's 保存 is on the line of its price fields. */
    private fun saveIsBesideTheFields(): Boolean {
        val field = compose.onAllNodes(hasSetTextAction() and hasText("Input")).onFirst().fetchSemanticsNode().boundsInRoot
        val save = compose.onAllNodesWithText("Save").onFirst().fetchSemanticsNode().boundsInRoot
        return save.center.y in field.top..field.bottom
    }

    private fun save(name: String) {
        val bitmap = compose.onNodeWithTag("prices").captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
