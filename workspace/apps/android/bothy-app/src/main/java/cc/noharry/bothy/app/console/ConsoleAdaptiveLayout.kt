package cc.noharry.bothy.app.console
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
import cc.noharry.bothy.app.console.ui.icon

internal enum class WindowClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

internal enum class NavigationDestination(val label: String) {
    SESSIONS("Sessions"),
    CONVERSATION("Chat"),
    BOARD("Board"),
    SETTINGS("Settings"),
}

internal data class ConsoleLayoutPolicy(
    val windowClass: WindowClass,
    val showPersistentSessions: Boolean,
    val showPersistentBoard: Boolean,
    val useUltraCompactHeightChrome: Boolean,
    /** No header: the view tabs sit over the pane and the destinations in a rail on the right. */
    val useRailChrome: Boolean = useUltraCompactHeightChrome,
) {
    val showBottomNavigation: Boolean =
        windowClass != WindowClass.EXPANDED && !useRailChrome

    val showTrailingNavigationRail: Boolean =
        windowClass == WindowClass.COMPACT && useRailChrome
}

internal fun consoleLayoutPolicy(widthDp: Float, heightDp: Float): ConsoleLayoutPolicy {
    val windowClass = when {
        widthDp < COMPACT_WIDTH_UPPER_BOUND_DP || heightDp < COMPACT_HEIGHT_UPPER_BOUND_DP -> {
            WindowClass.COMPACT
        }
        widthDp < EXPANDED_WIDTH_LOWER_BOUND_DP -> WindowClass.MEDIUM
        else -> WindowClass.EXPANDED
    }
    return ConsoleLayoutPolicy(
        windowClass = windowClass,
        showPersistentSessions = windowClass != WindowClass.COMPACT,
        showPersistentBoard = windowClass == WindowClass.EXPANDED,
        useUltraCompactHeightChrome = heightDp < ULTRA_COMPACT_HEIGHT_UPPER_BOUND_DP,
        useRailChrome = heightDp < ULTRA_COMPACT_HEIGHT_UPPER_BOUND_DP || shortLandscape(widthDp, heightDp),
    )
}

/**
 * A phone held sideways (owner 2026-10-23: 手机都固定横屏) takes the rail too: under 480 dp the header and the bottom bar
 * would leave the conversation a strip. It keeps the whole conversation, which only the bench's strip condenses.
 */
private fun shortLandscape(widthDp: Float, heightDp: Float): Boolean =
    heightDp < COMPACT_HEIGHT_UPPER_BOUND_DP && widthDp > heightDp

@Composable
internal fun DestinationBar(
    selected: NavigationDestination,
    includeSessions: Boolean,
    onSelected: (NavigationDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ConsoleColors
    val destinations = NavigationDestination.entries.filter { includeSessions || it != NavigationDestination.SESSIONS }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.radiusMd),
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
                    shape = RoundedCornerShape(Dimens.radiusSm),
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
                        Spacer(Modifier.height(Dimens.spaceXs))
                        Text(
                            text = destination.label,
                            style = ConsoleType.label,
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
internal fun DestinationRail(
    selected: NavigationDestination,
    includeSessions: Boolean,
    onSelected: (NavigationDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ConsoleColors
    val destinations = NavigationDestination.entries.filter { includeSessions || it != NavigationDestination.SESSIONS }
    Surface(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(Dimens.radiusMd),
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
                    shape = RoundedCornerShape(Dimens.radiusSm),
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
                            Spacer(Modifier.height(Dimens.spaceXs))
                            Text(
                                text = destination.label,
                                style = ConsoleType.label,
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
internal fun ConsoleNavRail(
    isActive: (NavigationDestination) -> Boolean,
    onSelected: (NavigationDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ConsoleColors
    Surface(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(Dimens.radiusMd),
        color = colors.panel,
        border = BorderStroke(1.dp, colors.borderSubtle),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(4.dp)) {
            NAV_RAIL_DESTINATIONS.forEach { destination ->
                NavRailItem(
                    destination = destination,
                    active = isActive(destination),
                    onClick = { onSelected(destination) },
                )
                Spacer(Modifier.height(Dimens.spaceXxs))
            }
        }
    }
}

@Composable
private fun NavRailItem(
    destination: NavigationDestination,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = ConsoleColors
    Box(Modifier.fillMaxWidth().height(NAV_RAIL_ITEM_HEIGHT)) {
        if (active) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(NAV_RAIL_INDICATOR_WIDTH)
                    .height(NAV_RAIL_INDICATOR_HEIGHT)
                    .background(colors.accent, RoundedCornerShape(Dimens.radiusFull)),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(
                    start = NAV_RAIL_INDICATOR_WIDTH + Dimens.spaceXxs,
                    top = Dimens.spaceXxs,
                    end = Dimens.spaceXxs,
                    bottom = Dimens.spaceXxs,
                )
                .clip(RoundedCornerShape(Dimens.radiusSm))
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
    NavigationDestination.CONVERSATION,
    NavigationDestination.SESSIONS,
    NavigationDestination.BOARD,
    NavigationDestination.SETTINGS,
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
