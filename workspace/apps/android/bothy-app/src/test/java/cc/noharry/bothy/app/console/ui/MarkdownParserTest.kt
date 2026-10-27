package cc.noharry.bothy.app.console.ui
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    @Test
    fun parsesFencedCodeBlockAsSingleBlock() {
        val source = """
            Some intro text.
            ```
            val x = 1
            val y = 2
            ```
            After block.
        """.trimIndent()

        val blocks = parseMarkdown(source)

        val code = blocks.filterIsInstance<MarkdownBlock.Code>()
        assertEquals(1, code.size)
        assertTrue(code[0].closed)
        assertTrue(code[0].content.contains("val x = 1"))
        assertTrue(code[0].content.contains("val y = 2"))
    }

    @Test
    fun parsesHeadingLevels() {
        val blocks = parseMarkdown("# Title\n## Sub\n### Deep")

        val headings = blocks.filterIsInstance<MarkdownBlock.Heading>()
        assertEquals(3, headings.size)
        assertEquals("Title", headings[0].text)
        assertEquals(1, headings[0].level)
        assertEquals(2, headings[1].level)
        assertEquals(3, headings[2].level)
    }

    @Test
    fun parsesBulletAndOrderedListItems() {
        val blocks = parseMarkdown("- first\n- second\n1. ordered\n2. another")

        val items = blocks.filterIsInstance<MarkdownBlock.ListItem>()
        assertEquals(4, items.size)
        assertEquals("• ", items[0].marker)
        assertEquals("first", items[0].text)
        assertEquals("1. ", items[2].marker)
        assertEquals("ordered", items[2].text)
    }

    @Test
    fun parsesBlockquote() {
        val blocks = parseMarkdown("> a quoted line")

        val quote = blocks.filterIsInstance<MarkdownBlock.Quote>()
        assertEquals(1, quote.size)
        assertEquals("a quoted line", quote[0].text)
    }

    @Test
    fun consecutiveProseLinesMergeIntoParagraph() {
        val blocks = parseMarkdown("line one\nline two\n\nnext paragraph")

        val paragraphs = blocks.filterIsInstance<MarkdownBlock.Paragraph>()
        assertEquals(2, paragraphs.size)
        assertEquals("line one\nline two", paragraphs[0].text)
        assertEquals("next paragraph", paragraphs[1].text)
    }

    @Test
    fun unclosedCodeBlockIsMarkedStreaming() {
        val blocks = parseMarkdown("```kotlin\nunfinished code")

        val code = blocks.filterIsInstance<MarkdownBlock.Code>().single()
        assertFalse(code.closed)
        assertEquals("kotlin", code.language)
        assertTrue(code.content.contains("unfinished code"))
    }

    @Test
    fun parseInlineSegmentsBoldCodeItalic() {
        val segments = parseInlineSegments("see **bold** and `code` plus *italic*")
        assertEquals(6, segments.size)
        assertEquals(InlineSegment.Text("see "), segments[0])
        assertEquals(InlineSegment.Bold("bold"), segments[1])
        assertEquals(InlineSegment.Text(" and "), segments[2])
        assertEquals(InlineSegment.Code("code"), segments[3])
        assertEquals(InlineSegment.Text(" plus "), segments[4])
        assertEquals(InlineSegment.Italic("italic"), segments[5])
    }

    @Test
    fun languageTagOnClosedFence() {
        val blocks = parseMarkdown("```json\n{\"a\":1}\n```")
        val code = blocks.filterIsInstance<MarkdownBlock.Code>().single()
        assertTrue(code.closed)
        assertEquals("json", code.language)
        assertTrue(code.content.contains("\"a\""))
    }

    @Test
    fun fullwidthEmphasisMarkersNormalizeToAsciiMarkdown() {
        val normalized = normalizeMarkdown("见＊＊粗体＊＊与｀code｀")
        assertEquals("见**粗体**与`code`", normalized)
        val segments = parseInlineSegments(normalized)
        assertTrue(segments.any { it is InlineSegment.Bold && it.value == "粗体" })
        assertTrue(segments.any { it is InlineSegment.Code && it.value == "code" })
    }
}
