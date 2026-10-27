package cc.noharry.bothy.playground.headunit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reply's cards, picks and panel as Bothy sends them, and what the head unit says back. */
class A2uiTest {
    private val sent = """
        {"cards":[{"title":"深圳天气","icon":"svg:M12 7a5 5 0 1 0 0 10Z","value":"31°","layout":"strip","fields":[["湿度","72%"]],
          "progress":[],"items":[{"title":"周五","icon":"sunny","value":"31/24°","kind":"weather","fields":[]}]}],
         "choices":[{"ordinal":1,"label":"喜素客","recommended":true,"reply":"第1个"},{"ordinal":2,"label":"天屿水","recommended":false,"reply":"第2个"}],
         "controls":[{"label":"空调温度","type":"slider","min":16,"max":30,"step":0.5,"value":24,"unit":"℃","integer":false},
                     {"label":"座椅加热","type":"switch","value":false},
                     {"label":"模式","type":"choice","multiple":false,"options":["自动","制冷","制热"],"value":[0]}]}
    """.trimIndent()

    @Test
    fun cardsPicksAndPanelAreReadAsSent() {
        val a2ui = a2uiFrom(sent)
        assertEquals("深圳天气", a2ui.cards.single().title)
        assertEquals("strip", a2ui.cards.single().layout)
        assertEquals(listOf("湿度" to "72%"), a2ui.cards.single().fields)
        assertEquals("31/24°", a2ui.cards.single().items.single().value)
        assertEquals(listOf("第1个", "第2个"), a2ui.choices.map { it.reply })
        assertTrue(a2ui.choices.first().recommended)
        assertEquals(3, a2ui.controls.size)
        assertTrue(a2ui.asks)
        assertTrue("nothing sent, or something unreadable, is no A2UI", a2uiFrom("").isEmpty && a2uiFrom("{oops").isEmpty)
    }

    @Test
    fun aConfirmedPanelSaysWhatChangedAsTheUserWould() {
        val controls = a2uiFrom(sent).controls
        assertEquals("空调温度设为22.5℃，座椅加热打开", a2uiPanelReply(controls, listOf(22.4, true, listOf(0))))
        assertEquals("nothing changed, it says everything shown", "空调温度设为24℃，座椅加热关闭，模式设为自动", a2uiPanelReply(controls, listOf(24.0, false, listOf(0))))
        assertEquals("模式设为制冷", a2uiPanelReply(controls, listOf(24.0, false, listOf(1))))
    }
}
