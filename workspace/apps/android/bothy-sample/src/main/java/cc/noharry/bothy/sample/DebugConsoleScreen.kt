package cc.noharry.bothy.sample

import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.activity.compose.BackHandler
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun DebugConsoleScreen(
    state: DebugUiState,
    pendingInput: String,
    onPendingInputConsumed: () -> Unit,
    actions: DebugConsoleActions,
    a2aClient: DebugA2aClientState = DebugA2aClientState(),
    a2aActions: DebugA2aClientActions? = null,
) {
    var page by rememberSaveable { mutableStateOf(DebugConsolePage.CONVERSATION) }
    var destination by rememberSaveable { mutableStateOf(DebugNavigationDestination.CONVERSATION) }
    var mcpReturnDestination by rememberSaveable { mutableStateOf(DebugNavigationDestination.BOARD) }
    var a2aReturnDestination by rememberSaveable { mutableStateOf(DebugNavigationDestination.SETTINGS) }
    var consoleView by rememberSaveable { mutableStateOf(DEFAULT_DEBUG_CONSOLE_VIEW.name) }
    // The A2A tab is the persisted client mode itself; Debug/Trajectory remember their own last choice.
    val view = debugConsoleViewFor(consoleView, a2aClient.enabled && a2aActions != null)
    val setView: (DebugConsoleView) -> Unit = { next ->
        when (next) {
            DebugConsoleView.A2A -> {
                consoleView = next.name
                a2aActions?.setEnabled?.invoke(true)
            }
            // 计划 is a look at the plans: it leaves the client mode as it is.
            DebugConsoleView.PLANS -> consoleView = next.name
            else -> {
                if (a2aClient.enabled) a2aActions?.setEnabled?.invoke(false)
                consoleView = next.name
            }
        }
    }
    val selectDestination = { selected: DebugNavigationDestination ->
        destination = selected
        page = if (selected == DebugNavigationDestination.SETTINGS) {
            DebugConsolePage.SETTINGS
        } else {
            DebugConsolePage.CONVERSATION
        }
    }
    val showConversation = { selectDestination(DebugNavigationDestination.CONVERSATION) }
    val showSettings = { selectDestination(DebugNavigationDestination.SETTINGS) }
    val showMcpProvidersFromBoard = {
        mcpReturnDestination = DebugNavigationDestination.BOARD
        page = DebugConsolePage.MCP_PROVIDERS
    }
    val showMcpProvidersFromSettings = {
        mcpReturnDestination = DebugNavigationDestination.SETTINGS
        page = DebugConsolePage.MCP_PROVIDERS
    }
    val leaveMcpProviders = {
        selectDestination(mcpReturnDestination)
    }
    val showA2aProfilesFromSettings = {
        a2aReturnDestination = DebugNavigationDestination.SETTINGS
        page = DebugConsolePage.A2A_PROVIDERS
    }
    val leaveA2aProfiles = {
        selectDestination(a2aReturnDestination)
    }
    // Fullscreen HTML preview owned here; panes open it through the wrapped actions.
    var htmlViewer by remember { mutableStateOf<DebugHtmlViewerRequest?>(null) }
    val screenActions = actions.copy(
        openHtmlViewer = { title, html ->
            htmlViewer = DebugHtmlViewerRequest(title = title, html = html)
        },
    )
    val colors = DebugColors
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .imePadding()
            .padding(DebugDimens.spaceMd),
    ) {
        val policy = debugConsoleLayoutPolicy(maxWidth.value, maxHeight.value)
        if (page != DebugConsolePage.CONVERSATION) {
            BackHandler(onBack = showConversation)
            DebugMainPane(
                page, state, pendingInput, onPendingInputConsumed, screenActions,
                showConversation, showMcpProvidersFromSettings, leaveMcpProviders,
                showA2aProfilesFromSettings, leaveA2aProfiles, Modifier.fillMaxSize(),
            )
            return@BoxWithConstraints
        }
        Column(Modifier.fillMaxSize()) {
            if (!policy.useUltraCompactHeightChrome) {
                SampleHostChrome(
                    state = state,
                    view = view,
                    onViewChange = setView,
                    statusLine = if (view == DebugConsoleView.A2A) a2aClientStatusLine(a2aClient) else sampleStatusLine(state),
                    showA2aTab = a2aActions != null,
                )
                Spacer(Modifier.height(DebugDimens.spaceSm))
            }
            val bodyModifier = Modifier.weight(1f).fillMaxWidth()
            when (view) {
                DebugConsoleView.A2A -> DebugA2aClientLayout(
                    state = a2aClient,
                    actions = requireNotNull(a2aActions),
                    tabs = if (policy.useUltraCompactHeightChrome) {
                        { DebugConsoleViewTabs(view, setView, showA2aTab = true) }
                    } else {
                        null
                    },
                    modifier = bodyModifier,
                )
                DebugConsoleView.PLANS -> DebugPlansLayout(
                    tabs = if (policy.useUltraCompactHeightChrome) {
                        { DebugConsoleViewTabs(view, setView, showA2aTab = a2aActions != null) }
                    } else {
                        null
                    },
                    modifier = bodyModifier,
                )
                DebugConsoleView.TRAJECTORY -> DebugTrajectoryLayout(
                    state = state,
                    actions = screenActions,
                    policy = policy,
                    view = view,
                    onViewChange = setView,
                    onShowConversation = showConversation,
                    onShowSettings = showSettings,
                    modifier = bodyModifier,
                )
                DebugConsoleView.DEBUG -> when (policy.windowClass) {
                    DebugWindowClass.COMPACT -> DebugCompactLayout(
                        state = state,
                        pendingInput = pendingInput,
                        onPendingInputConsumed = onPendingInputConsumed,
                        actions = screenActions,
                        view = view,
                        onViewChange = setView,
                        useUltraCompactHeightChrome = policy.useUltraCompactHeightChrome,
                        page = page,
                        destination = destination,
                        onDestinationSelected = selectDestination,
                        onShowConversation = showConversation,
                        onShowSettings = showSettings,
                        onShowMcpProvidersFromBoard = showMcpProvidersFromBoard,
                        onShowMcpProvidersFromSettings = showMcpProvidersFromSettings,
                        onLeaveMcpProviders = leaveMcpProviders,
                        onShowA2aProfiles = showA2aProfilesFromSettings,
                        onLeaveA2aProfiles = leaveA2aProfiles,
                        modifier = bodyModifier,
                    )
                    DebugWindowClass.MEDIUM -> DebugMediumLayout(
                        state = state,
                        pendingInput = pendingInput,
                        onPendingInputConsumed = onPendingInputConsumed,
                        actions = screenActions,
                        page = page,
                        destination = destination,
                        onDestinationSelected = selectDestination,
                        onShowConversation = showConversation,
                        onShowSettings = showSettings,
                        onShowMcpProvidersFromBoard = showMcpProvidersFromBoard,
                        onShowMcpProvidersFromSettings = showMcpProvidersFromSettings,
                        onLeaveMcpProviders = leaveMcpProviders,
                        onShowA2aProfiles = showA2aProfilesFromSettings,
                        onLeaveA2aProfiles = leaveA2aProfiles,
                        modifier = bodyModifier,
                    )
                    DebugWindowClass.EXPANDED -> DebugExpandedLayout(
                        state = state,
                        pendingInput = pendingInput,
                        onPendingInputConsumed = onPendingInputConsumed,
                        actions = screenActions,
                        page = page,
                        destination = destination,
                        onShowConversation = showConversation,
                        onShowSettings = showSettings,
                        onShowMcpProvidersFromBoard = showMcpProvidersFromBoard,
                        onShowMcpProvidersFromSettings = showMcpProvidersFromSettings,
                        onLeaveMcpProviders = leaveMcpProviders,
                        onShowA2aProfiles = showA2aProfilesFromSettings,
                        onLeaveA2aProfiles = leaveA2aProfiles,
                        modifier = bodyModifier,
                    )
                }
            }
        }
    }
    htmlViewer?.let { request ->
        DebugHtmlViewerDialog(request = request, onDismiss = { htmlViewer = null })
    }
}

private val BRAND_MARK_SIZE = 32.dp

@Composable
internal fun SampleHostChrome(
    state: DebugUiState,
    view: DebugConsoleView,
    onViewChange: (DebugConsoleView) -> Unit,
    statusLine: String = sampleStatusLine(state),
    showA2aTab: Boolean = true,
) {
    val colors = DebugColors
    // Tabs on the LEFT of chrome: some devices' right-edge floating chrome (avatar /
    // system gestures) intercepts taps on the far-right segmented control.
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DebugDimens.spaceMd),
        ) {
            DebugConsoleViewTabs(view = view, onViewChange = onViewChange, showA2aTab = showA2aTab)
            Image(painterResource(R.drawable.bothy_mark), contentDescription = null, modifier = Modifier.size(BRAND_MARK_SIZE))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Bothy Sample",
                    style = DebugType.panelTitle,
                    color = colors.text,
                    maxLines = 1,
                )
                Text(
                    text = statusLine,
                    style = DebugType.meta,
                    color = colors.muted,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(DebugDimens.spaceSm))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.borderSubtle),
        )
    }
}

/**
 * Segmented Debug/Trajectory(/A2A) control. A2A appears only where the client mode can be driven.
 * Uses Material3 [Surface] onClick (same path as Approval / session rows) so
 * full-segment bounds are pressable for finger and adb taps.
 */
@Composable
internal fun DebugConsoleViewTabs(
    view: DebugConsoleView,
    onViewChange: (DebugConsoleView) -> Unit,
    showA2aTab: Boolean = true,
) {
    val colors = DebugColors
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .semantics { contentDescription = "Console view" }
            .height(DebugDimens.toggleHeight)
            .border(1.dp, colors.border, shape)
            .background(colors.elevated, shape)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DebugConsoleView.entries.filter { showA2aTab || it != DebugConsoleView.A2A }.forEach { entry ->
            DebugConsoleViewSegment(
                label = entry.label,
                selected = view == entry,
                onClick = { onViewChange(entry) },
            )
        }
    }
}

@Composable
private fun DebugConsoleViewSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = DebugColors
    val shape = RoundedCornerShape(999.dp)
    Surface(
        onClick = onClick,
        modifier = Modifier
            .widthIn(min = DebugDimens.toggleMinWidth)
            .fillMaxHeight()
            .semantics {
                contentDescription = label
                role = Role.Tab
                this.selected = selected
            },
        shape = shape,
        color = if (selected) colors.ink else Color.Transparent,
        contentColor = if (selected) {
            if (colors.isDark) colors.bg else Color.White
        } else {
            colors.muted
        },
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = DebugType.bodySmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

internal fun sampleStatusLine(state: DebugUiState): String {
    val run = when {
        state.isRunning -> "Running"
        !state.lastRunSummary.isNullOrBlank() -> state.lastRunSummary
        else -> "Ready"
    }
    return "$run · ${state.providerLabel} · ${state.streamLabel}"
}

/**
 * Top-level console view. Replaces the removed Demo/Debug presentation mode:
 * Debug keeps the full console, Trajectory is a read-only run projection, and
 * A2A is the opt-in client mode (see docs/design/a2a-client-console/).
 */
internal enum class DebugConsoleView(val label: String) {
    DEBUG("Debug"),
    TRAJECTORY("Trajectory"),
    A2A("A2A"),
    PLANS("计划"),
}

internal val DEFAULT_DEBUG_CONSOLE_VIEW: DebugConsoleView = DebugConsoleView.DEBUG

internal fun String.toDebugConsoleView(): DebugConsoleView =
    runCatching { DebugConsoleView.valueOf(this) }.getOrDefault(DEFAULT_DEBUG_CONSOLE_VIEW)

/**
 * A2A is shown exactly while client mode is on (a saved A2A choice alone never selects it), unless 计划 was picked:
 * that tab is a look at the plans and leaves the mode on.
 */
internal fun debugConsoleViewFor(saved: String, clientMode: Boolean): DebugConsoleView = when {
    saved == DebugConsoleView.PLANS.name -> DebugConsoleView.PLANS
    clientMode -> DebugConsoleView.A2A
    else -> saved.toDebugConsoleView().takeUnless { it == DebugConsoleView.A2A } ?: DEFAULT_DEBUG_CONSOLE_VIEW
}

/**
 * Trajectory reuses the selected session: compact opens the existing Sessions
 * pane from the header, medium/expanded keep [DebugSessionPane] on the left.
 */
@Composable
private fun DebugTrajectoryLayout(
    state: DebugUiState,
    actions: DebugConsoleActions,
    policy: DebugConsoleLayoutPolicy,
    view: DebugConsoleView,
    onViewChange: (DebugConsoleView) -> Unit,
    onShowConversation: () -> Unit,
    onShowSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSessions by rememberSaveable { mutableStateOf(false) }
    val trajectory: @Composable (Modifier) -> Unit = { paneModifier ->
        TrajectoryScreen(
            state = state,
            actions = actions,
            policy = policy,
            onOpenSessions = { showSessions = true }.takeIf {
                policy.windowClass == DebugWindowClass.COMPACT
            },
            modifier = paneModifier,
        )
    }
    if (policy.windowClass == DebugWindowClass.COMPACT) {
        Column(modifier.fillMaxSize()) {
            if (policy.useUltraCompactHeightChrome) {
                DebugConsoleViewTabs(view, onViewChange)
                Spacer(Modifier.height(4.dp))
            }
            if (showSessions) {
                DebugSessionPane(
                    state, actions, false, { showSessions = false }, onShowSettings,
                    Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                trajectory(Modifier.weight(1f).fillMaxWidth())
            }
        }
        return
    }
    Row(modifier.fillMaxSize()) {
        DebugSessionPane(
            state, actions, false, onShowConversation, onShowSettings,
            Modifier.width(SESSION_PANE_WIDTH).fillMaxHeight(),
        )
        Spacer(Modifier.width(10.dp))
        trajectory(Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun DebugCompactLayout(
    state: DebugUiState,
    pendingInput: String,
    onPendingInputConsumed: () -> Unit,
    actions: DebugConsoleActions,
    view: DebugConsoleView,
    onViewChange: (DebugConsoleView) -> Unit,
    useUltraCompactHeightChrome: Boolean,
    page: DebugConsolePage,
    destination: DebugNavigationDestination,
    onDestinationSelected: (DebugNavigationDestination) -> Unit,
    onShowConversation: () -> Unit,
    onShowSettings: () -> Unit,
    onShowMcpProvidersFromBoard: () -> Unit,
    onShowMcpProvidersFromSettings: () -> Unit,
    onLeaveMcpProviders: () -> Unit,
    onShowA2aProfiles: () -> Unit,
    onLeaveA2aProfiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedNavigation = compactNavigationDestination(page, destination)
    val paneContent: @Composable (Modifier) -> Unit = { paneModifier ->
        DebugCompactPane(
            state, pendingInput, onPendingInputConsumed, actions, page, destination,
            onShowConversation, onShowSettings, onShowMcpProvidersFromBoard,
            onShowMcpProvidersFromSettings, onLeaveMcpProviders, onShowA2aProfiles, onLeaveA2aProfiles,
            useUltraCompactHeightChrome, paneModifier,
        )
    }
    if (useUltraCompactHeightChrome) {
        Row(modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                DebugConsoleViewTabs(view, onViewChange)
                Spacer(Modifier.height(4.dp))
                paneContent(Modifier.weight(1f).fillMaxWidth())
            }
            Spacer(Modifier.width(8.dp))
            DebugDestinationRail(
                selected = selectedNavigation,
                includeSessions = true,
                onSelected = onDestinationSelected,
                modifier = Modifier.width(ULTRA_COMPACT_RAIL_WIDTH).fillMaxHeight(),
            )
        }
    } else {
        Column(modifier.fillMaxSize()) {
            paneContent(Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            DebugDestinationBar(selectedNavigation, true, onDestinationSelected)
        }
    }
}

@Composable
private fun DebugCompactPane(
    state: DebugUiState,
    pendingInput: String,
    onPendingInputConsumed: () -> Unit,
    actions: DebugConsoleActions,
    page: DebugConsolePage,
    destination: DebugNavigationDestination,
    onShowConversation: () -> Unit,
    onShowSettings: () -> Unit,
    onShowMcpProvidersFromBoard: () -> Unit,
    onShowMcpProvidersFromSettings: () -> Unit,
    onLeaveMcpProviders: () -> Unit,
    onShowA2aProfiles: () -> Unit,
    onLeaveA2aProfiles: () -> Unit,
    useCondensedConversationChrome: Boolean,
    modifier: Modifier,
) {
    when {
        page == DebugConsolePage.MCP_PROVIDERS -> DebugMainPane(
            page, state, pendingInput, onPendingInputConsumed, actions,
            onShowConversation, onShowMcpProvidersFromSettings, onLeaveMcpProviders,
            onShowA2aProfiles, onLeaveA2aProfiles,
            modifier, useCondensedConversationChrome,
        )
        destination == DebugNavigationDestination.SESSIONS -> DebugSessionPane(
            state, actions, page == DebugConsolePage.SETTINGS, onShowConversation, onShowSettings, modifier,
            onShowA2aProfiles = onShowA2aProfiles,
        )
        destination == DebugNavigationDestination.BOARD -> DebugBoardPane(
            state, actions, onShowMcpProvidersFromBoard, modifier,
        )
        else -> DebugMainPane(
            page, state, pendingInput, onPendingInputConsumed, actions,
            onShowConversation, onShowMcpProvidersFromSettings, onLeaveMcpProviders,
            onShowA2aProfiles, onLeaveA2aProfiles,
            modifier, useCondensedConversationChrome,
        )
    }
}

private fun compactNavigationDestination(
    page: DebugConsolePage,
    destination: DebugNavigationDestination,
): DebugNavigationDestination =
    if (page == DebugConsolePage.MCP_PROVIDERS && destination != DebugNavigationDestination.SETTINGS) {
        DebugNavigationDestination.BOARD
    } else {
        destination
    }

@Composable
private fun DebugMediumLayout(
    state: DebugUiState,
    pendingInput: String,
    onPendingInputConsumed: () -> Unit,
    actions: DebugConsoleActions,
    page: DebugConsolePage,
    destination: DebugNavigationDestination,
    onDestinationSelected: (DebugNavigationDestination) -> Unit,
    onShowConversation: () -> Unit,
    onShowSettings: () -> Unit,
    onShowMcpProvidersFromBoard: () -> Unit,
    onShowMcpProvidersFromSettings: () -> Unit,
    onLeaveMcpProviders: () -> Unit,
    onShowA2aProfiles: () -> Unit,
    onLeaveA2aProfiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sessionsCollapsed by rememberSaveable { mutableStateOf(false) }
    val sessionPaneWidth by animateDpAsState(
        targetValue = if (sessionsCollapsed) 0.dp else SESSION_PANE_WIDTH,
        label = "medium-session-pane",
    )
    Column(modifier.fillMaxSize()) {
        Row {
            TextButton(onClick = { sessionsCollapsed = !sessionsCollapsed }) { Text("Sessions") }
            TextButton(onClick = onShowConversation) { Text("Chat") }
            TextButton(onClick = { onDestinationSelected(DebugNavigationDestination.BOARD) }) { Text("Board") }
            TextButton(onClick = onShowSettings) { Text("Settings") }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (sessionPaneWidth > 0.dp) {
                Box(
                    Modifier
                        .width(sessionPaneWidth)
                        .fillMaxHeight()
                        .clipToBounds(),
                ) {
                    DebugSessionPane(
                        state, actions, page == DebugConsolePage.SETTINGS, onShowConversation, onShowSettings,
                        Modifier.width(SESSION_PANE_WIDTH).fillMaxHeight(),
                        onShowA2aProfiles = onShowA2aProfiles,
                    )
                }
                Spacer(Modifier.width(10.dp))
            }
            val paneModifier = Modifier.weight(1f).fillMaxHeight()
            if (page != DebugConsolePage.MCP_PROVIDERS && destination == DebugNavigationDestination.BOARD) {
                DebugBoardPane(state, actions, onShowMcpProvidersFromBoard, paneModifier)
            } else {
                DebugMainPane(
                    page, state, pendingInput, onPendingInputConsumed, actions,
                    onShowConversation, onShowMcpProvidersFromSettings, onLeaveMcpProviders,
                    onShowA2aProfiles, onLeaveA2aProfiles, paneModifier,
                )
            }
        }
    }
}

/** Same selection mapping the old MEDIUM bottom bar used; sessions live on the rail now. */
private fun mediumNavigationDestination(
    page: DebugConsolePage,
    destination: DebugNavigationDestination,
): DebugNavigationDestination =
    if (page == DebugConsolePage.MCP_PROVIDERS && destination != DebugNavigationDestination.SETTINGS) {
        DebugNavigationDestination.BOARD
    } else {
        destination.takeUnless { it == DebugNavigationDestination.SESSIONS } ?: DebugNavigationDestination.CONVERSATION
    }

@Composable
private fun DebugExpandedLayout(
    state: DebugUiState,
    pendingInput: String,
    onPendingInputConsumed: () -> Unit,
    actions: DebugConsoleActions,
    page: DebugConsolePage,
    destination: DebugNavigationDestination,
    onShowConversation: () -> Unit,
    onShowSettings: () -> Unit,
    onShowMcpProvidersFromBoard: () -> Unit,
    onShowMcpProvidersFromSettings: () -> Unit,
    onLeaveMcpProviders: () -> Unit,
    onShowA2aProfiles: () -> Unit,
    onLeaveA2aProfiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sessionsCollapsed by rememberSaveable { mutableStateOf(false) }
    var boardCollapsed by rememberSaveable { mutableStateOf(false) }
    val sessionPaneWidth by animateDpAsState(
        targetValue = if (sessionsCollapsed) 0.dp else SESSION_PANE_WIDTH,
        label = "expanded-session-pane",
    )
    val boardPaneWidth by animateDpAsState(
        targetValue = if (boardCollapsed) BOARD_COLLAPSED_STRIP_WIDTH else EXPANDED_BOARD_PANE_WIDTH,
        label = "expanded-board-pane",
    )
    Column(modifier.fillMaxSize()) {
        Row {
            TextButton(onClick = { sessionsCollapsed = !sessionsCollapsed }) { Text("Sessions") }
            TextButton(onClick = { boardCollapsed = !boardCollapsed }) { Text("Board") }
            TextButton(onClick = onShowSettings) { Text("Settings") }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (sessionPaneWidth > 0.dp) {
                Box(
                    Modifier
                        .width(sessionPaneWidth)
                        .fillMaxHeight()
                        .clipToBounds(),
                ) {
                    DebugSessionPane(
                        state, actions, page == DebugConsolePage.SETTINGS, onShowConversation, onShowSettings,
                        Modifier.width(SESSION_PANE_WIDTH).fillMaxHeight(),
                        onShowA2aProfiles = onShowA2aProfiles,
                    )
                }
                Spacer(Modifier.width(10.dp))
            }
            DebugMainPane(
                page, state, pendingInput, onPendingInputConsumed, actions,
                onShowConversation, onShowMcpProvidersFromSettings, onLeaveMcpProviders,
                onShowA2aProfiles, onLeaveA2aProfiles,
                Modifier.weight(1f).fillMaxHeight(),
            )
            if (page == DebugConsolePage.CONVERSATION) {
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .width(boardPaneWidth)
                        .fillMaxHeight()
                        .clipToBounds(),
                ) {
                    if (boardCollapsed) {
                        DebugBoardCollapsedStrip(
                            onExpand = { boardCollapsed = false },
                            modifier = Modifier.width(BOARD_COLLAPSED_STRIP_WIDTH).fillMaxHeight(),
                        )
                    } else {
                        DebugBoardPane(
                            state, actions, onShowMcpProvidersFromBoard,
                            Modifier.width(EXPANDED_BOARD_PANE_WIDTH).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

/** Slim 40dp strip kept on the trailing edge when the EXPANDED board pane is collapsed. */
@Composable
private fun DebugBoardCollapsedStrip(
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DebugColors
    Column(
        modifier
            .background(colors.panel, RoundedCornerShape(DebugDimens.radiusMd))
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(DebugDimens.radiusMd))
            .padding(top = DebugDimens.spaceXs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(BOARD_STRIP_BUTTON_SIZE)
                .clip(RoundedCornerShape(DebugDimens.radiusSm))
                .clickable(onClick = onExpand)
                .semantics {
                    contentDescription = "Expand board"
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = null,
                tint = colors.muted,
                modifier = Modifier.size(DESTINATION_ICON_SIZE),
            )
        }
    }
}

@Composable
private fun DebugMainPane(
    page: DebugConsolePage,
    state: DebugUiState,
    pendingInput: String,
    onPendingInputConsumed: () -> Unit,
    actions: DebugConsoleActions,
    onSettingsBack: () -> Unit,
    onOpenMcpProviders: () -> Unit,
    onMcpBack: () -> Unit,
    onOpenA2aProviders: () -> Unit,
    onA2aBack: () -> Unit,
    modifier: Modifier,
    useCondensedConversationChrome: Boolean = false,
) {
    when (page) {
        DebugConsolePage.CONVERSATION -> DebugConversationPane(
            state, pendingInput, onPendingInputConsumed, actions,
            modifier, useCondensedConversationChrome,
        )
        DebugConsolePage.SETTINGS -> DebugSettingsPane(
            state, actions, onSettingsBack, modifier,
        )
        DebugConsolePage.MCP_PROVIDERS -> DebugMcpProvidersPane(state, actions, onMcpBack, modifier)
        DebugConsolePage.A2A_PROVIDERS -> DebugA2aProfilesPane(state, actions, onA2aBack, modifier)
    }
}

private enum class DebugConsolePage { CONVERSATION, SETTINGS, MCP_PROVIDERS, A2A_PROVIDERS }

@Composable
internal fun DebugPanelBox(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DebugColors
    Column(
        modifier
            .background(colors.panel, RoundedCornerShape(DebugDimens.radiusMd))
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(DebugDimens.radiusMd))
            .padding(DebugDimens.panelPadding),
    ) {
        Text(
            text = title,
            style = DebugType.panelTitle,
            color = colors.text,
        )
        Spacer(Modifier.height(DebugDimens.spaceSm))
        content()
    }
}

private val SESSION_PANE_WIDTH = 240.dp
private val EXPANDED_BOARD_PANE_WIDTH = 300.dp
private val BOARD_COLLAPSED_STRIP_WIDTH = 40.dp
private val BOARD_STRIP_BUTTON_SIZE = 32.dp
private val DESTINATION_ICON_SIZE = 20.dp
private val ULTRA_COMPACT_RAIL_WIDTH = 104.dp
