package cc.noharry.bothy.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Codex / Claude Code–style assistant body: merged paragraphs, inline styles,
 * fenced code with streaming state, theme-aware colors.
 */
@Composable
internal fun DebugMarkdown(source: String, modifier: Modifier = Modifier) {
    val colors = DebugColors
    val blocks = remember(source) {
        parseDebugMarkdown(normalizeDebugMarkdown(source))
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceSm),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Code -> DebugCodeBlock(
                    code = block.content.trimEnd('\n'),
                    language = when {
                        !block.closed -> "code · streaming"
                        block.language.isNotBlank() -> block.language
                        else -> null
                    },
                )
                is MarkdownBlock.Heading -> when (block.level) {
                    1 -> DebugMarkdownAnnotated(text = block.text, style = DebugType.hero, color = colors.text)
                    2 -> DebugMarkdownAnnotated(text = block.text, style = DebugType.panelTitle, color = colors.text)
                    3 -> DebugMarkdownAnnotated(
                        text = block.text,
                        style = DebugType.body,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.text,
                    )
                    else -> DebugMarkdownAnnotated(
                        text = block.text,
                        style = DebugType.body,
                        fontWeight = FontWeight.Medium,
                        color = colors.text,
                    )
                }
                is MarkdownBlock.ListItem -> DebugMarkdownAnnotated(
                    text = "${block.marker}${block.text}",
                    startPadding = 12,
                    color = colors.text,
                )
                is MarkdownBlock.Paragraph -> DebugMarkdownAnnotated(
                    text = block.text,
                    color = colors.text,
                )
                is MarkdownBlock.Quote -> DebugMarkdownQuote(block.text)
            }
        }
    }
}

@Composable
private fun DebugMarkdownAnnotated(
    text: String,
    style: TextStyle = DebugType.body,
    fontWeight: FontWeight? = null,
    startPadding: Int = 0,
    color: Color = DebugColors.text,
) {
    val colors = DebugColors
    val annotated = remember(text, colors.codeBg, colors.text, colors.muted) {
        buildInlineAnnotatedString(
            text = text,
            baseColor = color,
            codeBg = colors.codeBg,
            codeFg = colors.text,
        )
    }
    Text(
        text = annotated,
        style = style,
        fontWeight = fontWeight,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startPadding.dp),
    )
}

@Composable
private fun DebugMarkdownQuote(text: String) {
    val colors = DebugColors
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 4.dp)
                .width(3.dp)
                .height(16.dp)
                .background(colors.border, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(10.dp))
        DebugMarkdownAnnotated(
            text = text,
            color = colors.muted,
        )
    }
}

internal fun buildInlineAnnotatedString(
    text: String,
    baseColor: Color,
    codeBg: Color,
    codeFg: Color,
) = buildAnnotatedString {
    parseInlineSegments(text).forEach { segment ->
        when (segment) {
            is InlineSegment.Text -> withStyle(SpanStyle(color = baseColor)) {
                append(segment.value)
            }
            is InlineSegment.Bold -> withStyle(
                SpanStyle(color = baseColor, fontWeight = FontWeight.SemiBold),
            ) {
                append(segment.value)
            }
            is InlineSegment.Italic -> withStyle(
                SpanStyle(color = baseColor, fontStyle = FontStyle.Italic),
            ) {
                append(segment.value)
            }
            // Inline spans cannot carry rounded corners/padding; keep the quiet bg highlight.
            is InlineSegment.Code -> withStyle(
                SpanStyle(
                    color = codeFg,
                    background = codeBg,
                    fontFamily = FontFamily.Monospace,
                    fontSize = DebugType.code.fontSize,
                ),
            ) {
                append(segment.value)
            }
        }
    }
}
