package cc.noharry.bothy.app.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cc.noharry.agent.appserver.protocol.A2aClientState
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.bothy.app.a2a.A2aClientActions
import cc.noharry.bothy.app.a2ui.LocalPhoneA2ui
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.SettingsSection
import cc.noharry.bothy.app.console.ui.HtmlViewerDialog
import cc.noharry.bothy.app.page.LocalPageTouch
import cc.noharry.bothy.app.console.ui.HtmlViewerRequest
import kotlinx.coroutines.launch

/** Where a phone is: the conversation is home, and the rest open over it and go back to it. */
internal enum class PhoneRoute { CHAT, PLANS, TRAJECTORY, A2A, SETTINGS }

/**
 * Bothy on a phone (the Stitch design "Bothy 手机端", v2): screens, navigation and a look of its own ([PhoneTheme]) over
 * the same state and actions as the tablet's console ([cc.noharry.bothy.app.console.ConsoleScreen]), as one app server
 * drives two front ends. The drawer opens from the conversation; A2A is the client mode itself, on while its page is open.
 */
@Composable
internal fun PhoneApp(
    state: ServerState,
    pendingInput: String,
    onPendingInputConsumed: () -> Unit,
    actions: ConsoleActions,
    a2aClient: A2aClientState,
    a2aActions: A2aClientActions?,
) {
    var route by rememberSaveable { mutableStateOf(if (a2aClient.enabled && a2aActions != null) PhoneRoute.A2A else PhoneRoute.CHAT) }
    var htmlViewer by remember { mutableStateOf<HtmlViewerRequest?>(null) }
    val screenActions = actions.copy(openHtmlViewer = { title, html -> htmlViewer = HtmlViewerRequest(title = title, html = html) })
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val go: (PhoneRoute) -> Unit = { next ->
        route = next
        scope.launch { drawer.close() }
    }
    // Client mode is on only while A2A's page talks to an agent ([PhoneA2a]).
    LaunchedEffect(route) { if (a2aActions != null && a2aClient.enabled && route != PhoneRoute.A2A) a2aActions.setEnabled(false) }
    BackHandler(enabled = route != PhoneRoute.CHAT && !drawer.isOpen) { route = PhoneRoute.CHAT }
    // A finger on a drawn page is the page's: a sideways drag there moves its slider, not the drawer.
    val pageTouch = remember { mutableStateOf(false) }
    PhoneTheme {
        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = route == PhoneRoute.CHAT && !pageTouch.value,
            drawerContent = {
                ModalDrawerSheet(drawerState = drawer, drawerContainerColor = ConsoleColors.panel) {
                    PhoneDrawer(state, drawer.isOpen, showA2a = a2aActions != null, actions = screenActions, onGo = go)
                }
            },
        ) {
            Box(Modifier.fillMaxSize().background(ConsoleColors.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
                CompositionLocalProvider(LocalPageTouch provides pageTouch, LocalPhoneA2ui provides true) {
                    PhoneRouteContent(route, state, pendingInput, onPendingInputConsumed, screenActions, a2aClient, a2aActions, navigate = { route = it }) {
                        scope.launch { drawer.open() }
                    }
                }
            }
        }
    }
    htmlViewer?.let { request -> HtmlViewerDialog(request = request, onDismiss = { htmlViewer = null }) }
}

@Composable
private fun PhoneRouteContent(
    route: PhoneRoute,
    state: ServerState,
    pendingInput: String,
    onPendingInputConsumed: () -> Unit,
    actions: ConsoleActions,
    a2aClient: A2aClientState,
    a2aActions: A2aClientActions?,
    navigate: (PhoneRoute) -> Unit,
    openDrawer: () -> Unit,
) {
    val back = { navigate(PhoneRoute.CHAT) }
    // The turn whose footnote opened the trajectory; null for the latest.
    var trajectoryTurn by rememberSaveable { mutableStateOf<Long?>(null) }
    // The settings section A2A's page asked for; null for the settings' first page.
    var settingsStart by rememberSaveable { mutableStateOf<String?>(null) }
    // However the settings were left (their back, the system's), they open on their first page next time.
    LaunchedEffect(route) { if (route != PhoneRoute.SETTINGS) settingsStart = null }
    when (route) {
        PhoneRoute.CHAT -> {
            val links = PhoneLinks(
                onTrajectory = { turn ->
                    trajectoryTurn = turn
                    navigate(PhoneRoute.TRAJECTORY)
                },
                onPlans = { navigate(PhoneRoute.PLANS) },
            )
            PhoneChat(state, pendingInput, onPendingInputConsumed, actions, openDrawer, links)
        }
        PhoneRoute.PLANS -> PhonePlans(state, actions, back)
        PhoneRoute.TRAJECTORY -> PhoneTrajectory(state, actions, trajectoryTurn, back)
        PhoneRoute.A2A -> PhoneA2a(state, a2aClient, a2aActions, onManage = {
            settingsStart = SettingsSection.CONNECTIONS.name
            navigate(PhoneRoute.SETTINGS)
        }, onBack = back)
        PhoneRoute.SETTINGS -> PhoneSettings(state, actions, onBack = back, startAt = settingsStart?.let(SettingsSection::valueOf))
    }
}
