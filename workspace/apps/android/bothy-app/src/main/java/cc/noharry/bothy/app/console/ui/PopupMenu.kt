package cc.noharry.bothy.app.console.ui
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleThemeColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color

/**
 * Shared hybrid popup chrome for sample-host menus (model, variant, approval, settings, more).
 * Selected row = soft ink fill + accent check — one selection language across menus.
 */
@Composable
internal fun PopupMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp = DEBUG_POPUP_MIN_WIDTH,
    maxWidth: Dp = DEBUG_POPUP_MAX_WIDTH,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ConsoleColors
    // BOM 2024.02 DropdownMenu has no containerColor/shape API — paint via surface tokens in content.
    MaterialThemeSurface(colors) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier
                .widthIn(min = minWidth, max = maxWidth)
                .background(colors.elevated),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.elevated)
                    .padding(vertical = 4.dp),
                content = content,
            )
        }
    }
}

/** Local surface tint so M3 menu default surface does not fight hybrid elevated. */
@Composable
private fun MaterialThemeSurface(
    colors: ConsoleThemeColors,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.MaterialTheme(
        colorScheme = androidx.compose.material3.MaterialTheme.colorScheme.copy(
            surface = colors.elevated,
            surfaceContainer = colors.elevated,
            surfaceContainerHigh = colors.elevated,
            surfaceContainerHighest = colors.elevated,
            onSurface = colors.text,
            outline = colors.border,
        ),
        content = content,
    )
}

@Composable
internal fun PopupMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    accent: Color? = null,
    compact: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = ConsoleColors
    val mark = accent ?: colors.accent
    val bg = when {
        selected -> colors.inkSoft.copy(alpha = if (colors.isDark) 0.22f else 0.08f)
        else -> Color.Transparent
    }
    val minH = if (compact) DEBUG_POPUP_ITEM_COMPACT_HEIGHT else DEBUG_POPUP_ITEM_HEIGHT
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(Dimens.radiusSm))
            .background(bg)
            .clickable(onClick = onClick)
            .heightIn(min = minH)
            .padding(horizontal = 10.dp, vertical = if (compact) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = ConsoleType.bodySmall,
                color = colors.text,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!hint.isNullOrBlank() && !compact) {
                Text(
                    text = hint,
                    style = ConsoleType.meta,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = mark,
                modifier = Modifier.size(DEBUG_POPUP_CHECK_SIZE),
            )
        }
    }
}

@Composable
internal fun PopupSectionLabel(text: String) {
    val colors = ConsoleColors
    Text(
        text = text,
        style = ConsoleType.label,
        color = colors.muted,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
internal fun PopupDivider() {
    val colors = ConsoleColors
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp),
        color = colors.borderSubtle,
        thickness = 1.dp,
    )
}

/**
 * Selected option card used in settings list-style pickers (model list, etc.).
 * One selection language: accentSoft fill + 3dp accent bar + accent check.
 */
@Composable
internal fun SelectableOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val colors = ConsoleColors
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(Dimens.radiusSm),
        color = if (selected) colors.accentSoft else colors.elevated,
        border = if (selected) null else BorderStroke(1.dp, colors.border),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(Modifier.fillMaxWidth()) {
            if (selected) {
                // Same pill indicator as the NavRail (3dp x 24dp, centered on the leading edge).
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .width(OPTION_SELECTED_BAR_WIDTH)
                        .height(OPTION_SELECTED_BAR_HEIGHT)
                        .background(colors.accent, RoundedCornerShape(Dimens.radiusFull)),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = OPTION_SELECTED_BAR_WIDTH + Dimens.spaceMd,
                        top = Dimens.spaceMd,
                        end = Dimens.spaceMd,
                        bottom = Dimens.spaceMd,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXxs)) {
                    Text(
                        text = label,
                        color = colors.text,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = ConsoleType.meta,
                            color = colors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(OPTION_CHECK_SIZE),
                    )
                }
            }
        }
    }
}

@Composable
internal fun PopupDot(active: Boolean, color: Color) {
    val colors = ConsoleColors
    Box(
        Modifier
            .size(7.dp)
            .background(if (active) color else colors.border, CircleShape),
    )
}

internal val DEBUG_POPUP_MIN_WIDTH = 200.dp
internal val DEBUG_POPUP_MAX_WIDTH = 320.dp
internal val DEBUG_POPUP_ITEM_HEIGHT = 40.dp
internal val DEBUG_POPUP_ITEM_COMPACT_HEIGHT = 36.dp
private val DEBUG_POPUP_CHECK_SIZE = 16.dp
private val OPTION_SELECTED_BAR_WIDTH = 3.dp
private val OPTION_SELECTED_BAR_HEIGHT = 24.dp
private val OPTION_CHECK_SIZE = 16.dp
