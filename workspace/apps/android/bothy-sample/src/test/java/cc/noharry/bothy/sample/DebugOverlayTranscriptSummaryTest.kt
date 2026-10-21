package cc.noharry.bothy.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugOverlayTranscriptSummaryTest {
    @Test
    fun overlayToolProjectionKeepsCollapsedBodyEmptyButRetainsPayloadForExpand() {
        val item = DebugConversationItem.ToolCallItem(
            id = 1,
            toolName = "command_navi_SearchParking",
            argumentsJson = """{"destination":"广州塔"}""",
            resultJson = """{"parkingLots":[{"name":"P1"}]}""",
            status = DebugToolCallStatus.DONE,
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
        val item = DebugConversationItem.RuntimePhaseItem(
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
