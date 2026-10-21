package cc.noharry.bothy.sample

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A head unit draws the reply's cards and picks itself: they go out as plain JSON, icons as it can draw them. */
class DebugAssistantSurfaceA2uiTest {
    private fun hud(cards: List<DebugCard> = emptyList(), choices: List<DebugDisplayChoice> = emptyList()) = DebugOverlayHud(
        phase = DebugOverlayPhase.DONE, phaseLabel = "DONE", elapsedLabel = "", providerLabel = "", requestPreview = null, progressDone = 0,
        progressTotal = 0, progressLabel = "", currentStep = null, planRows = emptyList(), resultText = "", timingLine = "",
        toolLines = emptyList(), isRunning = false, headline = "", cards = cards, choices = choices,
    )

    @Test
    fun aCardAndPicksGoOutWithWhatATapSays() {
        val card = DebugCard(
            title = "深圳天气", icon = "sunny", value = "31°", subtitle = null, fields = listOf("湿度" to "72%"),
            items = listOf(DebugCardItem("sunny", "周五", null, "31/24°")), layout = DebugCardLayout.STRIP,
        )
        val sent = Json.parseToJsonElement(hud(listOf(card), listOf(DebugDisplayChoice(1, "喜素客", true))).surfaceA2ui()).jsonObject
        val first = sent.getValue("cards").jsonArray.single().jsonObject
        assertEquals("strip", first.getValue("layout").jsonPrimitive.content)
        assertTrue("its own glyphs go as paths", first.getValue("icon").jsonPrimitive.content.startsWith("svg:M"))
        assertEquals("第1个", sent.getValue("choices").jsonArray.single().jsonObject.getValue("reply").jsonPrimitive.content)
        assertEquals("a reply with nothing to show sends nothing", "", hud().surfaceA2ui())
    }
}
