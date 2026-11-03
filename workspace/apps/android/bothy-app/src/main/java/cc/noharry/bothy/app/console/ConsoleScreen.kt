package cc.noharry.bothy.app.console
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.a2a.A2aClientActions
import cc.noharry.bothy.app.a2a.A2aClientLayout
import cc.noharry.bothy.app.a2a.A2aClientState
import cc.noharry.bothy.app.a2a.A2aProfilesPane
import cc.noharry.bothy.app.a2a.a2aClientStatusLine
import cc.noharry.bothy.app.console.ui.HtmlViewerDialog
import cc.noharry.bothy.app.console.ui.HtmlViewerRequest
import cc.noharry.bothy.app.mcp.McpProvidersPane
import cc.noharry.bothy.app.overlay.clientMode
import cc.noharry.bothy.app.plans.PlansLayout
import cc.noharry.bothy.app.session.SessionPane
import cc.noharry.bothy.app.session.UiState
import cc.noharry.bothy.app.trajectory.TrajectoryScreen

@Composable
internal fun ConsoleScreen(
    state: UiState,
    pendingInput: String,
    onPendingInputConsumed: () -> Unit,
    actions: ConsoleActions,
    a2aClient: A2aClientState = A2aClientState(),
    a2aActions: A2aClientActions? = null,
) {
    var page by rememberSaveable { mutableStateOf(ConsolePage.CONVERSATION) }
    var destination by rememberSaveable { mutableStateOf(NavigationDestination.CONVERSATION) }
    var mcpReturnDestination by rememberSaveable { mutableStateOf(NavigationDestination.BOARD) }
    var a2aReturnDestination by rememberSaveable { mutableStateOf(NavigationDestination.SETTINGS) }
    var consoleView by rememberSaveable { mutableStateOf(DEFAULT_DEBUG_CONSOLE_VIEW.name) }
    // The A2A tab is the persisted client mode itself; Debug/Trajectory remember their own last choice.
    val view = consoleViewFor(consoleView, a2aClient.enabled && a2aActions != null)
    val setView: (ConsoleView) -> Unit = { next ->
        when (next) {
            ConsoleView.A2A -> {
                consoleView = next.name
                a2aActions?.setEnabled?.invoke(true)
            }
            // 计划 is a look at the plans: it leaves the client mode as it is.
            ConsoleView.PLANS -> consoleView = next.name
            else -> {
                if (a2aClient.enabled) a2aActions?.setEnabled?.invoke(false)
                consoleView = next.name
            }
        }
    }
    val selectDestination = { selected: NavigationDestination ->
        destination = selected
        page = if (selected == NavigationDestination.SETTINGS) {
            ConsolePage.SETTINGS
        } else {
            ConsolePage.CONVERSATION
        }
    }
    val showConversation = { selectDestination(NavigationDestination.CONVERSATION) }
    val showSettings = { selectDestination(NavigationDestination.SETTINGS) }
    val showMcpProvidersFromBoard = {
        mcpReturnDestination = NavigationDestination.BOARD
        page = ConsolePage.MCP_PROVIDERS
    }
    val showMcpProvidersFromSettings = {
        mcpReturnDestination = NavigationDestination.SETTINGS
        page = ConsolePage.MCP_PROVIDERS
    }
    val leaveMcpProviders = {
        selectDestination(mcpReturnDestination)
    }
    val showA2aProfilesFromSettings = {
        a2aReturnDestination = NavigationDestination.SETTINGS
        page = ConsolePage.A2A_PROVIDERS
    }
    val leaveA2aProfiles = {
        selectDestination(a2aReturnDestination)
    }
    // Fullscreen HTML preview owned here; panes open it through the wrapped actions.
    var htmlViewer by remember { mutableStateOf<HtmlViewerRequest?>(null) }
    val screenActions = actions.copy(
        openHtmlViewer = { title, html ->
            htmlViewer = HtmlViewerRequest(title = title, html = html)
        },
    )
    val colors = ConsoleColors
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.bg)) {
        val policy = consoleLayoutPolicy(maxWidth.value, maxHeight.value)
        val context = LocalContext.current
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val drawerScope = rememberCoroutineScope()
        val openDrawer: () -> Unit = { drawerScope.launch { drawerState.open() } }
        val closeDrawer: () -> Unit = { drawerScope.launch { drawerState.close() } }
        val screenPadding = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout).imePadding().padding(Dimens.spaceMd)
        if (page != ConsolePage.CONVERSATION) {
            BackHandler(onBack = showConversation)
            MainPane(
                page, state, pendingInput, onPendingInputConsumed, screenActions,
                showConversation, showMcpProvidersFromSettings, leaveMcpProviders,
                showA2aProfilesFromSettings, leaveA2aProfiles, screenPadding,
            )
            return@BoxWithConstraints
        }
        val setViewState = rememberUpdatedState(setView)
        val openTrajectory = remember(policy.showPhoneDrawer) {
            if (policy.showPhoneDrawer) {
                { setViewState.value(ConsoleView.TRAJECTORY) }
            } else {
                null
            }
        }
        val openPlans = remember(policy.showPhoneDrawer) {
            if (policy.showPhoneDrawer) {
                { setViewState.value(ConsoleView.PLANS) }
            } else {
                null
            }
        }
        if (phoneBack(policy.showPhoneDrawer, drawerState.isOpen, view) == PhoneBack.SHOW_CONVERSATION) {
            BackHandler { setView(ConsoleView.DEBUG) }
        }
        val home: @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalOpenTrajectory provides openTrajectory,
            LocalOpenPlans provides openPlans,
            LocalPortraitPhone provides policy.showPhoneDrawer,
        ) {
        Column(screenPadding) {
            if (policy.showPhoneDrawer) {
                PhoneTopBar(state, view, screenActions, openDrawer)
            } else if (!policy.useRailChrome) {
                HostChrome(
                    state = state,
                    view = view,
                    onViewChange = setView,
                    statusLine = if (view == ConsoleView.A2A) a2aClientStatusLine(a2aClient) else appStatusLine(state),
                    showA2aTab = a2aActions != null,
                )
            }
            if (!policy.useRailChrome) {
                if (policy.showPhoneDrawer && view != ConsoleView.DEBUG && view != ConsoleView.TRAJECTORY && state.isRunning) {
                    TextButton(onClick = { setView(ConsoleView.TRAJECTORY) }) {
                        Text(stringResource(R.string.console_status_running), color = colors.activity)
                    }
                }
                Spacer(Modifier.height(Dimens.spaceSm))
            }
            val bodyModifier = Modifier.weight(1f).fillMaxWidth()
            when (view) {
                ConsoleView.A2A -> A2aClientLayout(
                    state = a2aClient,
                    actions = requireNotNull(a2aActions),
                    tabs = if (policy.useRailChrome) {
                        { ConsoleViewTabs(view, setView, showA2aTab = true) }
                    } else {
                        null
                    },
                    modifier = bodyModifier,
                )
                ConsoleView.PLANS -> PlansLayout(
                    tabs = if (policy.useRailChrome) {
                        { ConsoleViewTabs(view, setView, showA2aTab = a2aActions != null) }
                    } else {
                        null
                    },
                    modifier = bodyModifier,
                )
                ConsoleView.TRAJECTORY -> TrajectoryLayout(
                    state = state,
                    actions = screenActions,
                    policy = policy,
                    view = view,
                    onViewChange = setView,
                    onShowConversation = showConversation,
                    onShowSettings = showSettings,
                    modifier = bodyModifier,
                )
                ConsoleView.DEBUG -> when (policy.windowClass) {
                    WindowClass.COMPACT -> CompactLayout(
                        state = state,
                        pendingInput = pendingInput,
                        onPendingInputConsumed = onPendingInputConsumed,
                        actions = screenActions,
                        view = view,
                        onViewChange = setView,
                        useRailChrome = policy.useRailChrome,
                        useCondensedConversationChrome = policy.useUltraCompactHeightChrome,
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
                        showDestinationBar = !policy.showPhoneDrawer,
                        modifier = bodyModifier,
                    )
                    WindowClass.MEDIUM -> MediumLayout(
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
                    WindowClass.EXPANDED -> ExpandedLayout(
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
        }
        if (policy.showPhoneDrawer) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(drawerState = drawerState, drawerContainerColor = colors.bg) {
                        PhoneDrawer(
                            sessions = state.sessions,
                            activeSessionId = state.activeSessionId,
                            open = drawerState.isOpen,
                            showA2a = a2aActions != null,
                            onNewChat = {
                                screenActions.newSession()
                                setView(ConsoleView.DEBUG)
                                closeDrawer()
                            },
                            onSelectSession = {
                                screenActions.selectSession(it)
                                setView(ConsoleView.DEBUG)
                                closeDrawer()
                            },
                            onPlans = {
                                setView(ConsoleView.PLANS)
                                closeDrawer()
                            },
                            onA2a = {
                                setView(ConsoleView.A2A)
                                closeDrawer()
                            },
                            onPlayground = {
                                openPlayground(context)
                                closeDrawer()
                            },
                            onSettings = {
                                showSettings()
                                closeDrawer()
                            },
                        )
                    }
                },
            ) { home() }
        } else {
            home()
        }
    }
    htmlViewer?.let { request ->
        HtmlViewerDialog(request = request, onDismiss = { htmlViewer = null })
    }
}

private val BRAND_MARK_SIZE = 32.dp

@Composable
internal fun HostChrome(
    state: UiState,
    view: ConsoleView,
    onViewChange: (ConsoleView) -> Unit,
    statusLine: String = appStatusLine(state),
    showA2aTab: Boolean = true,
) {
    val colors = ConsoleColors
    // Tabs on the LEFT of chrome: some devices' right-edge floating chrome (avatar /
    // system gestures) intercepts taps on the far-right segmented control.
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
        ) {
            ConsoleViewTabs(view = view, onViewChange = onViewChange, showA2aTab = showA2aTab)
            Image(painterResource(R.drawable.bothy_mark), contentDescription = null, modifier = Modifier.size(BRAND_MARK_SIZE))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Bothy",
                    style = ConsoleType.panelTitle,
                    color = colors.text,
                    maxLines = 1,
                )
                Text(
                    text = statusLine,
                    style = ConsoleType.meta,
                    color = colors.muted,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(Dimens.spaceSm))
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
internal fun ConsoleViewTabs(
    view: ConsoleView,
    onViewChange: (ConsoleView) -> Unit,
    showA2aTab: Boolean = true,
) {
    val colors = ConsoleColors
    val shape = RoundedCornerShape(999.dp)
    val tabsLabel = stringResource(R.string.console_view_tabs)
    Row(
        modifier = Modifier
            .semantics { contentDescription = tabsLabel }
            .height(Dimens.toggleHeight)
            .border(1.dp, colors.border, shape)
            .background(colors.elevated, shape)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConsoleView.entries.filter { showA2aTab || it != ConsoleView.A2A }.forEach { entry ->
            ConsoleViewSegment(
                label = stringResource(entry.label),
                selected = view == entry,
                onClick = { onViewChange(entry) },
            )
        }
    }
}

@Composable
private fun ConsoleViewSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = ConsoleColors
    val shape = RoundedCornerShape(999.dp)
    Surface(
        onClick = onClick,
        modifier = Modifier
            .widthIn(min = Dimens.toggleMinWidth)
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
                style = ConsoleType.bodySmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun appStatusLine(state: UiState): String {
    val run = when {
        state.isRunning -> stringResource(R.string.console_status_running)
        !state.lastRunSummary.isNullOrBlank() -> runSummaryText(state.lastRunSummary)
        else -> stringResource(R.string.console_ready)
    }
    return "$run · ${state.providerLabel} · ${streamLabelText(state.streamLabel)}"
}

/**
 * Top-level console view. Replaces the removed Demo/Debug presentation mode:
 * Debug keeps the full console, Trajectory is a read-only run projection, and
 * A2A is the opt-in client mode (see docs/design/a2a-client-console/).
 */
internal enum class ConsoleView(@StringRes val label: Int) {
    DEBUG(R.string.console_view_debug),
    TRAJECTORY(R.string.console_view_trajectory),
    A2A(R.string.console_view_a2a),
    PLANS(R.string.console_view_plans),
}

/**
 * What an extra back handler does on a portrait phone. An open drawer is [SYSTEM]: the sheet's own predictive back
 * closes it, and a second handler would take that gesture.
 */
internal enum class PhoneBack { SHOW_CONVERSATION, SYSTEM }

internal fun phoneBack(showPhoneDrawer: Boolean, drawerOpen: Boolean, view: ConsoleView): PhoneBack = when {
    !showPhoneDrawer || drawerOpen -> PhoneBack.SYSTEM
    view != ConsoleView.DEBUG -> PhoneBack.SHOW_CONVERSATION
    else -> PhoneBack.SYSTEM
}

internal val DEFAULT_DEBUG_CONSOLE_VIEW: ConsoleView = ConsoleView.DEBUG

internal fun String.toConsoleView(): ConsoleView =
    runCatching { ConsoleView.valueOf(this) }.getOrDefault(DEFAULT_DEBUG_CONSOLE_VIEW)

/**
 * A2A is shown exactly while client mode is on (a saved A2A choice alone never selects it), unless 计划 was picked:
 * that tab is a look at the plans and leaves the mode on.
 */
internal fun consoleViewFor(saved: String, clientMode: Boolean): ConsoleView = when {
    saved == ConsoleView.PLANS.name -> ConsoleView.PLANS
    clientMode -> ConsoleView.A2A
    else -> saved.toConsoleView().takeUnless { it == ConsoleView.A2A } ?: DEFAULT_DEBUG_CONSOLE_VIEW
}

/**
 * Trajectory reuses the selected session: compact opens the existing Sessions
 * pane from the header, medium/expanded keep [SessionPane] on the left.
 */
@Composable
private fun TrajectoryLayout(
    state: UiState,
    actions: ConsoleActions,
    policy: ConsoleLayoutPolicy,
    view: ConsoleView,
    onViewChange: (ConsoleView) -> Unit,
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
                policy.windowClass == WindowClass.COMPACT
            },
            modifier = paneModifier,
        )
    }
    if (policy.windowClass == WindowClass.COMPACT) {
        Column(modifier.fillMaxSize()) {
            if (policy.useRailChrome) {
                ConsoleViewTabs(view, onViewChange)
                Spacer(Modifier.height(4.dp))
            }
            if (showSessions) {
                SessionPane(
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
        SessionPane(
            state, actions, false, onShowConversation, onShowSettings,
            Modifier.width(SESSION_PANE_WIDTH).fillMaxHeight(),
        )
        Spacer(Modifier.width(10.dp))
        trajectory(Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun CompactLayout(
    state: UiState,
    pendingInput: String,
    onPendingInputConsumed: () -> Unit,
    actions: ConsoleActions,
    view: ConsoleView,
    onViewChange: (ConsoleView) -> Unit,
    useRailChrome: Boolean,
    useCondensedConversationChrome: Boolean,
    page: ConsolePage,
    destination: NavigationDestination,
    onDestinationSelected: (NavigationDestination) -> Unit,
    onShowConversation: () -> Unit,
    onShowSettings: () -> Unit,
    onShowMcpProvidersFromBoard: () -> Unit,
    onShowMcpProvidersFromSettings: () -> Unit,
    onLeaveMcpProviders: () -> Unit,
    onShowA2aProfiles: () -> Unit,
    onLeaveA2aProfiles: () -> Unit,
    showDestinationBar: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val selectedNavigation = compactNavigationDestination(page, destination)
    val paneContent: @Composable (Modifier) -> Unit = { paneModifier ->
        CompactPane(
            state, pendingInput, onPendingInputConsumed, actions, page, destination,
            onShowConversation, onShowSettings, onShowMcpProvidersFromBoard,
            onShowMcpProvidersFromSettings, onLeaveMcpProviders, onShowA2aProfiles, onLeaveA2aProfiles,
            useCondensedConversationChrome, paneModifier,
        )
    }
    if (useRailChrome) {
        Row(modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                ConsoleViewTabs(view, onViewChange)
                Spacer(Modifier.height(4.dp))
                paneContent(Modifier.weight(1f).fillMaxWidth())
            }
            Spacer(Modifier.width(8.dp))
            DestinationRail(
                selected = selectedNavigation,
                includeSessions = true,
                onSelected = onDestinationSelected,
                modifier = Modifier.width(ULTRA_COMPACT_RAIL_WIDTH).fillMaxHeight(),
            )
        }
    } else {
        Column(modifier.fillMaxSize()) {
            paneContent(Modifier.weight(1f).fillMaxWidth())
            if (showDestinationBar) {
                Spacer(Modifier.height(8.dp))
                DestinationBar(selectedNavigation, true, onDestinationSelected)
            }
        }
    }
}

@Composable
private fun CompactPane(
    state: UiState,
    pendingInput: String,
    onPendingInputConsumed: () -> Unit,
    actions: ConsoleActions,
    page: ConsolePage,
    destination: NavigationDestination,
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
        page == ConsolePage.MCP_PROVIDERS -> MainPane(
            page, state, pendingInput, onPendingInputConsumed, actions,
            onShowConversation, onShowMcpProvidersFromSettings, onLeaveMcpProviders,
            onShowA2aProfiles, onLeaveA2aProfiles,
            modifier, useCondensedConversationChrome,
        )
        destination == NavigationDestination.SESSIONS -> SessionPane(
            state, actions, page == ConsolePage.SETTINGS, onShowConversation, onShowSettings, modifier,
            onShowA2aProfiles = onShowA2aProfiles,
        )
        destination == NavigationDestination.BOARD -> BoardPane(
            state, actions, onShowMcpProvidersFromBoard, modifier,
        )
        else -> MainPane(
            page, state, pendingInput, onPendingInputConsumed, actions,
            onShowConversation, onShowMcpProvidersFromSettings, onLeaveMcpProviders,
            onShowA2aProfiles, onLeaveA2aProfiles,
            modifier, useCondensedConversationChrome,
        )
    }
}

private fun compactNavigationDestination(
    page: ConsolePage,
    destination: NavigationDestination,
): NavigationDestination =
    if (page == ConsolePage.MCP_PROVIDERS && destination != NavigationDestination.SETTINGS) {
        NavigationDestination.BOARD
    } else {
        destination
    }

@Composable
private fun MediumLayout(
    state: UiState,
    pendingInput: String,
    onPendingInputConsumed: () -> Unit,
    actions: ConsoleActions,
    page: ConsolePage,
    destination: NavigationDestination,
    onDestinationSelected: (NavigationDestination) -> Unit,
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
            TextButton(onClick = { sessionsCollapsed = !sessionsCollapsed }) {
                Text(stringResource(R.string.console_nav_sessions))
            }
            TextButton(onClick = onShowConversation) { Text(stringResource(R.string.console_nav_chat)) }
            TextButton(onClick = { onDestinationSelected(NavigationDestination.BOARD) }) {
                Text(stringResource(R.string.console_board))
            }
            TextButton(onClick = onShowSettings) { Text(stringResource(R.string.common_settings)) }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (sessionPaneWidth > 0.dp) {
                Box(
                    Modifier
                        .width(sessionPaneWidth)
                        .fillMaxHeight()
                        .clipToBounds(),
                ) {
                    SessionPane(
                        state, actions, page == ConsolePage.SETTINGS, onShowConversation, onShowSettings,
                        Modifier.width(SESSION_PANE_WIDTH).fillMaxHeight(),
                        onShowA2aProfiles = onShowA2aProfiles,
                    )
                }
                Spacer(Modifier.width(10.dp))
            }
            val paneModifier = Modifier.weight(1f).fillMaxHeight()
            if (page != ConsolePage.MCP_PROVIDERS && destination == NavigationDestination.BOARD) {
                BoardPane(state, actions, onShowMcpProvidersFromBoard, paneModifier)
            } else {
                MainPane(
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
    page: ConsolePage,
    destination: NavigationDestination,
): NavigationDestination =
    if (page == ConsolePage.MCP_PROVIDERS && destination != NavigationDestination.SETTINGS) {
        NavigationDestination.BOARD
    } else {
        destination.takeUnless { it == NavigationDestination.SESSIONS } ?: NavigationDestination.CONVERSATION
    }

@Composable
private fun ExpandedLayout(
    state: UiState,
    pendingInput: String,
    onPendingInputConsumed: () -> Unit,
    actions: ConsoleActions,
    page: ConsolePage,
    destination: NavigationDestination,
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
            TextButton(onClick = { sessionsCollapsed = !sessionsCollapsed }) {
                Text(stringResource(R.string.console_nav_sessions))
            }
            TextButton(onClick = { boardCollapsed = !boardCollapsed }) { Text(stringResource(R.string.console_board)) }
            TextButton(onClick = onShowSettings) { Text(stringResource(R.string.common_settings)) }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (sessionPaneWidth > 0.dp) {
                Box(
                    Modifier
                        .width(sessionPaneWidth)
                        .fillMaxHeight()
                        .clipToBounds(),
                ) {
                    SessionPane(
                        state, actions, page == ConsolePage.SETTINGS, onShowConversation, onShowSettings,
                        Modifier.width(SESSION_PANE_WIDTH).fillMaxHeight(),
                        onShowA2aProfiles = onShowA2aProfiles,
                    )
                }
                Spacer(Modifier.width(10.dp))
            }
            MainPane(
                page, state, pendingInput, onPendingInputConsumed, actions,
                onShowConversation, onShowMcpProvidersFromSettings, onLeaveMcpProviders,
                onShowA2aProfiles, onLeaveA2aProfiles,
                Modifier.weight(1f).fillMaxHeight(),
            )
            if (page == ConsolePage.CONVERSATION) {
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .width(boardPaneWidth)
                        .fillMaxHeight()
                        .clipToBounds(),
                ) {
                    if (boardCollapsed) {
                        BoardCollapsedStrip(
                            onExpand = { boardCollapsed = false },
                            modifier = Modifier.width(BOARD_COLLAPSED_STRIP_WIDTH).fillMaxHeight(),
                        )
                    } else {
                        BoardPane(
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
private fun BoardCollapsedStrip(
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ConsoleColors
    val expandLabel = stringResource(R.string.console_expand_board)
    Column(
        modifier
            .background(colors.panel, RoundedCornerShape(Dimens.radiusMd))
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(Dimens.radiusMd))
            .padding(top = Dimens.spaceXs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(BOARD_STRIP_BUTTON_SIZE)
                .clip(RoundedCornerShape(Dimens.radiusSm))
                .clickable(onClick = onExpand)
                .semantics {
                    contentDescription = expandLabel
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
private fun MainPane(
    page: ConsolePage,
    state: UiState,
    pendingInput: String,
    onPendingInputConsumed: () -> Unit,
    actions: ConsoleActions,
    onSettingsBack: () -> Unit,
    onOpenMcpProviders: () -> Unit,
    onMcpBack: () -> Unit,
    onOpenA2aProviders: () -> Unit,
    onA2aBack: () -> Unit,
    modifier: Modifier,
    useCondensedConversationChrome: Boolean = false,
) {
    when (page) {
        ConsolePage.CONVERSATION -> ConversationPane(
            state, pendingInput, onPendingInputConsumed, actions,
            modifier, useCondensedConversationChrome,
        )
        ConsolePage.SETTINGS -> SettingsPane(
            state, actions, onSettingsBack, modifier,
        )
        ConsolePage.MCP_PROVIDERS -> McpProvidersPane(state, actions, onMcpBack, modifier)
        ConsolePage.A2A_PROVIDERS -> A2aProfilesPane(state, actions, onA2aBack, modifier)
    }
}

private enum class ConsolePage { CONVERSATION, SETTINGS, MCP_PROVIDERS, A2A_PROVIDERS }

@Composable
internal fun PanelBox(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ConsoleColors
    Column(
        modifier
            .background(colors.panel, RoundedCornerShape(Dimens.radiusMd))
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(Dimens.radiusMd))
            .padding(Dimens.panelPadding),
    ) {
        Text(
            text = title,
            style = ConsoleType.panelTitle,
            color = colors.text,
        )
        Spacer(Modifier.height(Dimens.spaceSm))
        content()
    }
}

private val SESSION_PANE_WIDTH = 240.dp
private val EXPANDED_BOARD_PANE_WIDTH = 300.dp
private val BOARD_COLLAPSED_STRIP_WIDTH = 40.dp
private val BOARD_STRIP_BUTTON_SIZE = 32.dp
private val DESTINATION_ICON_SIZE = 20.dp
private val ULTRA_COMPACT_RAIL_WIDTH = 104.dp
