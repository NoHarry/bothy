package cc.noharry.bothy.sample

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal enum class DebugWindowClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

internal enum class DebugNavigationDestination(val label: String) {
    SESSIONS("Sessions"),
    CONVERSATION("Chat"),
    BOARD("Board"),
    SETTINGS("Settings"),
}

internal data class DebugConsoleLayoutPolicy(
    val windowClass: DebugWindowClass,
    val showPersistentSessions: Boolean,
    val showPersistentBoard: Boolean,
    val useUltraCompactHeightChrome: Boolean,
) {
    val showBottomNavigation: Boolean =
        windowClass != DebugWindowClass.EXPANDED && !useUltraCompactHeightChrome

    val showTrailingNavigationRail: Boolean =
        windowClass == DebugWindowClass.COMPACT && useUltraCompactHeightChrome
}

internal fun debugConsoleLayoutPolicy(widthDp: Float, heightDp: Float): DebugConsoleLayoutPolicy {
    val windowClass = when {
        widthDp < COMPACT_WIDTH_UPPER_BOUND_DP || heightDp < COMPACT_HEIGHT_UPPER_BOUND_DP -> {
            DebugWindowClass.COMPACT
        }
        widthDp < EXPANDED_WIDTH_LOWER_BOUND_DP -> DebugWindowClass.MEDIUM
        else -> DebugWindowClass.EXPANDED
    }
    return DebugConsoleLayoutPolicy(
        windowClass = windowClass,
        showPersistentSessions = windowClass != DebugWindowClass.COMPACT,
        showPersistentBoard = windowClass == DebugWindowClass.EXPANDED,
        useUltraCompactHeightChrome = heightDp < ULTRA_COMPACT_HEIGHT_UPPER_BOUND_DP,
    )
}

@Composable
internal fun DebugDestinationBar(
    selected: DebugNavigationDestination,
    includeSessions: Boolean,
    onSelected: (DebugNavigationDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DebugColors
    val destinations = DebugNavigationDestination.entries.filter { includeSessions || it != DebugNavigationDestination.SESSIONS }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DebugDimens.radiusMd),
        color = colors.panel,
        border = BorderStroke(1.dp, colors.borderSubtle),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(Modifier.fillMaxWidth().padding(4.dp)) {
            destinations.forEach { destination ->
                val active = destination == selected
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelected(destination) }
                        .semantics {
                            contentDescription = destination.label
                            role = Role.Tab
                            this.selected = active
                        },
                    shape = RoundedCornerShape(DebugDimens.radiusSm),
                    color = if (active) colors.accentSoft else colors.panel,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = destination.icon(),
                            contentDescription = null,
                            tint = if (active) colors.accent else colors.muted,
                            modifier = Modifier.size(DESTINATION_ICON_SIZE),
                        )
                        Spacer(Modifier.height(DebugDimens.spaceXs))
                        Text(
                            text = destination.label,
                            style = DebugType.label,
                            color = if (active) colors.accent else colors.muted,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun DebugDestinationRail(
    selected: DebugNavigationDestination,
    includeSessions: Boolean,
    onSelected: (DebugNavigationDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DebugColors
    val destinations = DebugNavigationDestination.entries.filter { includeSessions || it != DebugNavigationDestination.SESSIONS }
    Surface(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(DebugDimens.radiusMd),
        color = colors.panel,
        border = BorderStroke(1.dp, colors.borderSubtle),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxSize().padding(4.dp)) {
            destinations.forEach { destination ->
                val active = destination == selected
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clickable { onSelected(destination) }
                        .semantics {
                            contentDescription = destination.label
                            role = Role.Tab
                            this.selected = active
                        },
                    shape = RoundedCornerShape(DebugDimens.radiusSm),
                    color = if (active) colors.accentSoft else colors.panel,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = destination.icon(),
                                contentDescription = null,
                                tint = if (active) colors.accent else colors.muted,
                                modifier = Modifier.size(DESTINATION_ICON_SIZE),
                            )
                            Spacer(Modifier.height(DebugDimens.spaceXs))
                            Text(
                                text = destination.label,
                                style = DebugType.label,
                                color = if (active) colors.accent else colors.muted,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Persistent 64dp left rail for MEDIUM/EXPANDED: icon-only entries with a left
 * accent indicator on the active item. Sessions/Board act as side-pane toggles,
 * so [isActive] is caller-defined per destination.
 */
@Composable
internal fun DebugNavRail(
    isActive: (DebugNavigationDestination) -> Boolean,
    onSelected: (DebugNavigationDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DebugColors
    Surface(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(DebugDimens.radiusMd),
        color = colors.panel,
        border = BorderStroke(1.dp, colors.borderSubtle),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(4.dp)) {
            NAV_RAIL_DESTINATIONS.forEach { destination ->
                DebugNavRailItem(
                    destination = destination,
                    active = isActive(destination),
                    onClick = { onSelected(destination) },
                )
                Spacer(Modifier.height(DebugDimens.spaceXxs))
            }
        }
    }
}

@Composable
private fun DebugNavRailItem(
    destination: DebugNavigationDestination,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = DebugColors
    Box(Modifier.fillMaxWidth().height(NAV_RAIL_ITEM_HEIGHT)) {
        if (active) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(NAV_RAIL_INDICATOR_WIDTH)
                    .height(NAV_RAIL_INDICATOR_HEIGHT)
                    .background(colors.accent, RoundedCornerShape(DebugDimens.radiusFull)),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(
                    start = NAV_RAIL_INDICATOR_WIDTH + DebugDimens.spaceXxs,
                    top = DebugDimens.spaceXxs,
                    end = DebugDimens.spaceXxs,
                    bottom = DebugDimens.spaceXxs,
                )
                .clip(RoundedCornerShape(DebugDimens.radiusSm))
                .background(if (active) colors.accentSoft else Color.Transparent)
                .clickable(onClick = onClick)
                .semantics {
                    contentDescription = destination.label
                    role = Role.Tab
                    this.selected = active
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = destination.icon(),
                contentDescription = null,
                tint = if (active) colors.accent else colors.muted,
                modifier = Modifier.size(NAV_RAIL_ICON_SIZE),
            )
        }
    }
}

internal val NAV_RAIL_WIDTH = 64.dp

private val NAV_RAIL_DESTINATIONS = listOf(
    DebugNavigationDestination.CONVERSATION,
    DebugNavigationDestination.SESSIONS,
    DebugNavigationDestination.BOARD,
    DebugNavigationDestination.SETTINGS,
)
private val NAV_RAIL_ITEM_HEIGHT = 48.dp
private val NAV_RAIL_ICON_SIZE = 22.dp
private val NAV_RAIL_INDICATOR_WIDTH = 3.dp
private val NAV_RAIL_INDICATOR_HEIGHT = 24.dp
private val DESTINATION_ICON_SIZE = 20.dp

private const val COMPACT_WIDTH_UPPER_BOUND_DP = 600f
private const val EXPANDED_WIDTH_LOWER_BOUND_DP = 840f
private const val COMPACT_HEIGHT_UPPER_BOUND_DP = 480f
private const val ULTRA_COMPACT_HEIGHT_UPPER_BOUND_DP = 240f
