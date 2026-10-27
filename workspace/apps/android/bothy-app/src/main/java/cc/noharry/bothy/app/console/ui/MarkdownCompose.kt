package cc.noharry.bothy.app.console.ui
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
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.runtime.append

/**
 * Codex / Claude Code–style assistant body: merged paragraphs, inline styles,
 * fenced code with streaming state, theme-aware colors.
 */
@Composable
internal fun Markdown(source: String, modifier: Modifier = Modifier) {
    val colors = ConsoleColors
    val blocks = remember(source) {
        parseMarkdown(normalizeMarkdown(source))
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Code -> CodeBlock(
                    code = block.content.trimEnd('\n'),
                    language = when {
                        !block.closed -> "code · streaming"
                        block.language.isNotBlank() -> block.language
                        else -> null
                    },
                )
                is MarkdownBlock.Heading -> when (block.level) {
                    1 -> MarkdownAnnotated(text = block.text, style = ConsoleType.hero, color = colors.text)
                    2 -> MarkdownAnnotated(text = block.text, style = ConsoleType.panelTitle, color = colors.text)
                    3 -> MarkdownAnnotated(
                        text = block.text,
                        style = ConsoleType.body,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.text,
                    )
                    else -> MarkdownAnnotated(
                        text = block.text,
                        style = ConsoleType.body,
                        fontWeight = FontWeight.Medium,
                        color = colors.text,
                    )
                }
                is MarkdownBlock.ListItem -> MarkdownAnnotated(
                    text = "${block.marker}${block.text}",
                    startPadding = 12,
                    color = colors.text,
                )
                is MarkdownBlock.Paragraph -> MarkdownAnnotated(
                    text = block.text,
                    color = colors.text,
                )
                is MarkdownBlock.Quote -> MarkdownQuote(block.text)
            }
        }
    }
}

@Composable
private fun MarkdownAnnotated(
    text: String,
    style: TextStyle = ConsoleType.body,
    fontWeight: FontWeight? = null,
    startPadding: Int = 0,
    color: Color = ConsoleColors.text,
) {
    val colors = ConsoleColors
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
private fun MarkdownQuote(text: String) {
    val colors = ConsoleColors
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
        MarkdownAnnotated(
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
                    fontSize = ConsoleType.code.fontSize,
                ),
            ) {
                append(segment.value)
            }
        }
    }
}
