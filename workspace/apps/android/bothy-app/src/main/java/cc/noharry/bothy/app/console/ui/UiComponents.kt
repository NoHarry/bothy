package cc.noharry.bothy.app.console.ui
import android.webkit.WebView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cc.noharry.bothy.app.a2ui.initialValue
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color

/**
 * Shared primitives for the Phase 2 conversation UI redesign.
 * Everything here rides on Debug tokens — no ad-hoc colors or font sizes.
 */

/** Rotating chevron that replaces the old ▾/▸ and "+ "/"− " text disclosures. */
@Composable
internal fun Disclosure(expanded: Boolean, modifier: Modifier = Modifier) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        label = "debug-disclosure",
    )
    Icon(
        imageVector = Icons.Default.KeyboardArrowDown,
        contentDescription = null,
        tint = ConsoleColors.muted,
        modifier = modifier
            .size(DISCLOSURE_ICON_SIZE)
            .graphicsLayer { rotationZ = rotation },
    )
}

/** Unified code block: quiet codeBg surface, optional language tag, tall dumps scroll inside. */
@Composable
internal fun CodeBlock(
    code: String,
    language: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = ConsoleColors
    Surface(
        color = colors.codeBg,
        shape = RoundedCornerShape(Dimens.radiusSm),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(CODE_BLOCK_PADDING)) {
            if (!language.isNullOrBlank()) {
                Text(
                    text = language,
                    style = ConsoleType.label,
                    color = colors.tertiary,
                    maxLines = 1,
                )
                Spacer(Modifier.height(Dimens.spaceXsMd))
            }
            // softWrap so long lines wrap; oversize dumps scroll instead of flooding the timeline.
            Text(
                text = code,
                style = ConsoleType.code,
                color = colors.text,
                softWrap = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = CODE_BLOCK_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

/** Confirmation actions: primary Approve + quiet outlined Deny, end-aligned. */
@Composable
internal fun ApproveDenyRow(
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ConsoleColors
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm, Alignment.End),
    ) {
        OutlinedButton(
            onClick = onDeny,
            shape = RoundedCornerShape(Dimens.radiusMd),
            border = BorderStroke(1.dp, colors.border),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
            contentPadding = PaddingValues(horizontal = Dimens.spaceLg, vertical = Dimens.spaceXs),
            modifier = Modifier.height(ACTION_BUTTON_HEIGHT),
        ) {
            Text("Deny")
        }
        Button(
            onClick = onApprove,
            shape = RoundedCornerShape(Dimens.radiusMd),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = if (colors.isDark) colors.bg else Color.White,
            ),
            contentPadding = PaddingValues(horizontal = Dimens.spaceLg, vertical = Dimens.spaceXs),
            modifier = Modifier.height(ACTION_BUTTON_HEIGHT),
        ) {
            Text("Approve")
        }
    }
}

/** 8dp status dot; [pulsing] runs an infinite alpha pulse for live/running signals. */
@Composable
internal fun StatusDot(
    color: Color,
    pulsing: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (pulsing) {
        PulsingStatusDot(color = color, modifier = modifier)
    } else {
        Box(modifier.statusDotSize().background(color, CircleShape))
    }
}

@Composable
private fun PulsingStatusDot(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "status-dot")
    val alpha by transition.animateFloat(
        initialValue = STATUS_DOT_PULSE_MIN_ALPHA,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(STATUS_DOT_PULSE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "status-dot-alpha",
    )
    Box(
        modifier
            .graphicsLayer { this.alpha = alpha }
            .statusDotSize()
            .background(color, CircleShape),
    )
}

private fun Modifier.statusDotSize(): Modifier = size(STATUS_DOT_SIZE)

/**
 * Unified activity dot — same pulse as [StatusDot] so all live dots share one look.
 * Kept under the old name for panes that have not migrated yet.
 */
@Composable
internal fun ActivityDot(
    modifier: Modifier = Modifier,
    color: Color = ConsoleColors.activity,
) {
    StatusDot(color = color, pulsing = true, modifier = modifier)
}

/** Converged chip anchor for composer selectors (model / variant / approval / overflow). */
@Composable
internal fun SelectorChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingDot: Color? = null,
    placeholder: Boolean = false,
    warning: Boolean = false,
) {
    val colors = ConsoleColors
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(Dimens.radiusFull),
        color = if (warning) colors.warning.copy(alpha = CHIP_WARNING_ALPHA) else colors.panel,
        border = BorderStroke(
            1.dp,
            if (warning) colors.warning.copy(alpha = CHIP_WARNING_BORDER_ALPHA) else colors.borderSubtle,
        ),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            Modifier.padding(horizontal = Dimens.spaceMd, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingDot != null) {
                StatusDot(color = leadingDot, modifier = Modifier.size(7.dp))
                Spacer(Modifier.width(Dimens.spaceSm))
            }
            Text(
                text = label,
                style = ConsoleType.meta,
                color = when {
                    warning -> colors.warning
                    placeholder -> colors.muted
                    else -> colors.text
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(Dimens.spaceXsMd))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.muted,
                modifier = Modifier.size(CHIP_CHEVRON_SIZE),
            )
        }
    }
}

/** "1 turn" / "N turns" — one pluralization for session lists and board cards. */
internal fun formatTurnCount(turnCount: Int): String =
    if (turnCount == 1) "1 turn" else "$turnCount turns"

/** Uppercase label-style section header, shared by board rail sections and settings groups. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = ConsoleType.label,
        color = ConsoleColors.tertiary,
        modifier = modifier,
    )
}

/** Quiet turn separator: hairline — label — hairline. */
@Composable
internal fun TurnDividerLine(label: String, modifier: Modifier = Modifier) {
    val colors = ConsoleColors
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = Dimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(colors.borderSubtle),
        )
        Text(
            text = label,
            style = ConsoleType.label,
            color = colors.tertiary,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = Dimens.spaceSm),
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(colors.borderSubtle),
        )
    }
}

private val DISCLOSURE_ICON_SIZE = 18.dp
private val CODE_BLOCK_PADDING = 10.dp
private val CODE_BLOCK_MAX_HEIGHT = 240.dp
private val ACTION_BUTTON_HEIGHT = 36.dp
private val STATUS_DOT_SIZE = 8.dp
private val CHIP_CHEVRON_SIZE = 16.dp
private const val STATUS_DOT_PULSE_MS = 700
private const val STATUS_DOT_PULSE_MIN_ALPHA = 0.5f
private const val CHIP_WARNING_ALPHA = 0.12f
private const val CHIP_WARNING_BORDER_ALPHA = 0.55f

/**
 * Non-interactive HTML thumbnail (JS off, in-memory content). The whole surface is the
 * tap target that opens the fullscreen viewer; reloads only when [refreshToken] changes.
 */
@Composable
internal fun HtmlPreviewBox(
    html: String,
    refreshToken: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ConsoleColors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Dimens.radiusSm),
        border = BorderStroke(1.dp, colors.borderSubtle),
        color = colors.codeBg,
    ) {
        Box {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                },
                update = { webView ->
                    if (webView.tag != refreshToken) {
                        webView.tag = refreshToken
                        webView.loadDataWithBaseURL(
                            HTML_PREVIEW_BASE_URL,
                            html,
                            HTML_PREVIEW_MIME_TYPE,
                            HTML_PREVIEW_ENCODING,
                            null,
                        )
                    }
                },
            )
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(onClickLabel = "Open fullscreen preview") { onOpen() },
            )
        }
    }
}

private const val HTML_PREVIEW_BASE_URL = "https://artifact.preview/"
private const val HTML_PREVIEW_MIME_TYPE = "text/html"
private const val HTML_PREVIEW_ENCODING = "UTF-8"
