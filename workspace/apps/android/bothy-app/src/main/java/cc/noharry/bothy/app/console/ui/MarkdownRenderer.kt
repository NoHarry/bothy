package cc.noharry.bothy.app.console.ui

import cc.noharry.bothy.app.runtime.append
import cc.noharry.bothy.app.session.normalizeTextEscapes
/** Normalizes model-emitted markdown before parsing (escapes + fullwidth markers). */
internal fun normalizeMarkdown(source: String): String =
    source.normalizeTextEscapes()
        // Chinese models often emit fullwidth emphasis markers; map to ASCII MD.
        .replace('＊', '*')
        .replace('｀', '`')

/**
 * Pure-data parse result. [Paragraph] holds one or more soft-wrapped lines
 * (blank lines in the source separate paragraphs).
 */
internal sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Code(
        val content: String,
        val closed: Boolean = true,
        val language: String = "",
    ) : MarkdownBlock
    data class Heading(val text: String, val level: Int) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class ListItem(val marker: String, val text: String) : MarkdownBlock
}

/**
 * Pure Kotlin markdown parser → [MarkdownBlock]s.
 *
 * - Merges consecutive prose lines into paragraphs (blank line splits).
 * - Unclosed ``` fences become [MarkdownBlock.Code] with [MarkdownBlock.Code.closed] = false
 *   so the UI can show a streaming code state without treating half-fences as prose.
 */
internal fun parseMarkdown(source: String): List<MarkdownBlock> {
    val raw = mutableListOf<MarkdownBlock>()
    val lines = source.split("\n")
    var inCodeBlock = false
    var codeLanguage = ""
    val codeBlock = StringBuilder()

    for (line in lines) {
        val trimmed = line.trimEnd()
        when {
            trimmed.startsWith(CODE_FENCE) -> {
                if (inCodeBlock) {
                    raw += MarkdownBlock.Code(
                        content = codeBlock.toString(),
                        closed = true,
                        language = codeLanguage,
                    )
                    codeBlock.clear()
                    codeLanguage = ""
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                    codeLanguage = trimmed.removePrefix(CODE_FENCE).trim()
                }
            }
            inCodeBlock -> {
                if (codeBlock.isNotEmpty()) codeBlock.append("\n")
                codeBlock.append(line)
            }
            else -> raw += parseLine(trimmed)
        }
    }
    if (inCodeBlock) {
        raw += MarkdownBlock.Code(
            content = codeBlock.toString(),
            closed = false,
            language = codeLanguage,
        )
    }
    return coalesceProse(raw)
}

/** Join adjacent plain/prose lines; drop pure empty separators between paragraphs. */
private fun coalesceProse(raw: List<MarkdownBlock>): List<MarkdownBlock> {
    if (raw.isEmpty()) return raw
    val out = mutableListOf<MarkdownBlock>()
    val prose = StringBuilder()

    fun flushProse() {
        if (prose.isEmpty()) return
        out += MarkdownBlock.Paragraph(prose.toString())
        prose.clear()
    }

    raw.forEach { block ->
        when (block) {
            is MarkdownBlock.Paragraph -> {
                if (block.text.isEmpty()) {
                    flushProse()
                } else if (prose.isEmpty()) {
                    prose.append(block.text)
                } else {
                    prose.append('\n').append(block.text)
                }
            }
            else -> {
                flushProse()
                out += block
            }
        }
    }
    flushProse()
    return out
}

private fun parseLine(line: String): MarkdownBlock {
    if (line.isEmpty()) return MarkdownBlock.Paragraph("")
    orderedListMatch(line)?.let { return MarkdownBlock.ListItem("${it.groupValues[1]}. ", it.groupValues[2]) }
    return when {
        line.startsWith(H6) -> MarkdownBlock.Heading(line.removePrefix(H6).trim(), 6)
        line.startsWith(H5) -> MarkdownBlock.Heading(line.removePrefix(H5).trim(), 5)
        line.startsWith(H4) -> MarkdownBlock.Heading(line.removePrefix(H4).trim(), 4)
        line.startsWith(H3) -> MarkdownBlock.Heading(line.removePrefix(H3).trim(), 3)
        line.startsWith(H2) -> MarkdownBlock.Heading(line.removePrefix(H2).trim(), 2)
        line.startsWith(H1) -> MarkdownBlock.Heading(line.removePrefix(H1).trim(), 1)
        line.startsWith(BLOCKQUOTE) -> MarkdownBlock.Quote(line.removePrefix(BLOCKQUOTE).trim())
        line.startsWith(BULLET_DASH) -> MarkdownBlock.ListItem("• ", line.removePrefix(BULLET_DASH).trim())
        line.startsWith(BULLET_STAR) -> MarkdownBlock.ListItem("• ", line.removePrefix(BULLET_STAR).trim())
        else -> MarkdownBlock.Paragraph(line)
    }
}

private fun orderedListMatch(line: String): MatchResult? = ORDERED_LIST.find(line)

/**
 * Inline segments for Compose [androidx.compose.ui.text.AnnotatedString] building.
 * Order of match: **bold** → `code` → *italic*.
 */
internal sealed interface InlineSegment {
    data class Text(val value: String) : InlineSegment
    data class Bold(val value: String) : InlineSegment
    data class Italic(val value: String) : InlineSegment
    data class Code(val value: String) : InlineSegment
}

internal fun parseInlineSegments(text: String): List<InlineSegment> {
    if (text.isEmpty()) return emptyList()
    val out = mutableListOf<InlineSegment>()
    val plain = StringBuilder()
    var i = 0
    while (i < text.length) {
        i = appendNextInlineSegment(text, i, out, plain)
    }
    flushInlinePlain(out, plain)
    return out
}

private fun flushInlinePlain(out: MutableList<InlineSegment>, plain: StringBuilder) {
    if (plain.isEmpty()) return
    out += InlineSegment.Text(plain.toString())
    plain.clear()
}

/** Returns the next index after consuming one plain char or one markup span. */
private fun appendNextInlineSegment(
    text: String,
    index: Int,
    out: MutableList<InlineSegment>,
    plain: StringBuilder,
): Int {
    val boldEnd = delimitedSpanEnd(text, index, BOLD_DELIM)
    if (boldEnd != null) {
        flushInlinePlain(out, plain)
        out += InlineSegment.Bold(text.substring(index + BOLD_DELIM.length, boldEnd))
        return boldEnd + BOLD_DELIM.length
    }
    val codeEnd = delimitedSpanEnd(text, index, BACKTICK)
    if (codeEnd != null) {
        flushInlinePlain(out, plain)
        out += InlineSegment.Code(text.substring(index + 1, codeEnd))
        return codeEnd + 1
    }
    val italicEnd = delimitedSpanEnd(text, index, ITALIC_DELIM)
    if (italicEnd != null) {
        flushInlinePlain(out, plain)
        out += InlineSegment.Italic(text.substring(index + ITALIC_DELIM.length, italicEnd))
        return italicEnd + ITALIC_DELIM.length
    }
    plain.append(text[index])
    return index + 1
}

/** Closed delimiter span end index, or null when open delimiter is missing/unmatched. */
private fun delimitedSpanEnd(text: String, index: Int, delim: String): Int? {
    if (!text.startsWith(delim, index)) return null
    val end = text.indexOf(delim, index + delim.length)
    return end.takeIf { it >= 0 }
}

private const val CODE_FENCE = "```"
private const val BACKTICK = "`"
private const val BOLD_DELIM = "**"
private const val ITALIC_DELIM = "*"
private const val BLOCKQUOTE = ">"
private const val H1 = "# "
private const val H2 = "## "
private const val H3 = "### "
private const val H4 = "#### "
private const val H5 = "##### "
private const val H6 = "###### "
private const val BULLET_DASH = "- "
private const val BULLET_STAR = "* "
private val ORDERED_LIST = Regex("""^(\d+)\.\s+(.*)""")
