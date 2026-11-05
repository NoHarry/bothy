package cc.noharry.bothy.app.overlay
import cc.noharry.agent.appserver.protocol.view.CardItem
import cc.noharry.agent.appserver.protocol.view.CardLayout
import cc.noharry.agent.appserver.protocol.view.DisplayChoice
import cc.noharry.agent.appserver.protocol.view.OverlayPhase
import cc.noharry.agent.appserver.protocol.view.WidgetCard
import cc.noharry.bothy.app.console.ui.icon
import cc.noharry.bothy.protocol.view.OverlayHud
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A head unit draws the reply's cards and picks itself: they go out as plain JSON, icons as it can draw them. */
class AssistantSurfaceA2uiTest {
    private fun hud(cards: List<WidgetCard> = emptyList(), choices: List<DisplayChoice> = emptyList()) = OverlayHud(
        phase = OverlayPhase.DONE, phaseLabel = "DONE", elapsedLabel = "", providerLabel = "", requestPreview = null, progressDone = 0,
        progressTotal = 0, progressLabel = "", currentStep = null, planRows = emptyList(), resultText = "", timingLine = "",
        toolLines = emptyList(), isRunning = false, headline = "", cards = cards, choices = choices,
    )

    @Test
    fun aCardAndPicksGoOutWithWhatATapSays() {
        val card = WidgetCard(
            title = "深圳天气", icon = "sunny", value = "31°", subtitle = null, fields = listOf("湿度" to "72%"),
            items = listOf(CardItem("sunny", "周五", null, "31/24°")), layout = CardLayout.STRIP,
        )
        val sent = Json.parseToJsonElement(hud(listOf(card), listOf(DisplayChoice(1, "喜素客", true))).surfaceA2ui()).jsonObject
        val first = sent.getValue("cards").jsonArray.single().jsonObject
        assertEquals("strip", first.getValue("layout").jsonPrimitive.content)
        assertTrue("its own glyphs go as paths", first.getValue("icon").jsonPrimitive.content.startsWith("svg:M"))
        assertEquals("第1个", sent.getValue("choices").jsonArray.single().jsonObject.getValue("reply").jsonPrimitive.content)
        assertEquals("a reply with nothing to show sends nothing", "", hud().surfaceA2ui())
    }
}
