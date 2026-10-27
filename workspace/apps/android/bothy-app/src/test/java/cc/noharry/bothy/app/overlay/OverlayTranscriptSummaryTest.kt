package cc.noharry.bothy.app.overlay
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.session.ConversationItem
import cc.noharry.bothy.app.session.ToolCallStatus
import cc.noharry.bothy.app.tools.prettyToolPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayTranscriptSummaryTest {
    @Test
    fun overlayToolProjectionKeepsCollapsedBodyEmptyButRetainsPayloadForExpand() {
        val item = ConversationItem.ToolCallItem(
            id = 1,
            toolName = "command_navi_SearchParking",
            argumentsJson = """{"destination":"广州塔"}""",
            resultJson = """{"parkingLots":[{"name":"P1"}]}""",
            status = ToolCallStatus.DONE,
            startedAt = 10L,
            finishedAt = 2010L,
        )

        val entry = item.toOverlayEntry()
        val compact = item.toOverlayCompact()

        assertTrue(entry.title.contains("command_navi_SearchParking"))
        assertTrue(entry.isTool)
        assertEquals("", entry.body)
        assertEquals("""{"destination":"广州塔"}""", entry.argumentsJson)
        assertEquals("""{"parkingLots":[{"name":"P1"}]}""", entry.resultJson)
        assertEquals("2s", entry.durationLabel)
        // Compact flat line still omits payload (collapsed strip).
        assertEquals("", compact.body)
        assertFalse(compact.title.contains("广州塔"))
        assertFalse(compact.title.contains("parkingLots"))
    }

    @Test
    fun overlayRuntimeProjectionShowsOnlyFirstCollapsedLine() {
        val item = ConversationItem.RuntimePhaseItem(
            id = 2,
            title = "Runtime result",
            body = "COMPLETED\nlarge details that should stay collapsed",
        )

        val compact = item.toOverlayCompact()

        assertEquals("Runtime result", compact.title)
        assertEquals("COMPLETED", compact.body)
    }

    @Test
    fun prettyToolPayloadIndentsJsonObjects() {
        val pretty = prettyToolPayload("""{"a":1,"b":[2]}""")
        assertEquals(
            """
            {
              "a": 1,
              "b": [
                2
              ]
            }
            """.trimIndent(),
            pretty,
        )
        assertNotNull(pretty)
    }
}
