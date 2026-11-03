package cc.noharry.bothy.app.phone

import android.app.Activity
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import cc.noharry.bothy.app.a2a.A2aClientActions
import cc.noharry.bothy.app.a2a.A2aClientState
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ui.HtmlViewerDialog
import cc.noharry.bothy.app.console.ui.HtmlViewerRequest
import cc.noharry.bothy.app.session.UiState
import kotlinx.coroutines.launch

/** Where a phone is: the conversation is home, and the rest open over it and go back to it. */
internal enum class PhoneRoute { CHAT, PLANS, TRAJECTORY, A2A, SETTINGS }

/**
 * Bothy on a phone (docs/phone-portrait/design): screens and navigation of its own over the same state and actions as
 * the tablet's console ([cc.noharry.bothy.app.console.ConsoleScreen]), as one app server drives two front ends. The
 * drawer opens from the conversation; A2A is the client mode itself, on while its page is open.
 */
@Composable
internal fun PhoneApp(
    state: UiState,
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
    LaunchedEffect(route) { if (a2aActions != null && a2aClient.enabled != (route == PhoneRoute.A2A)) a2aActions.setEnabled(route == PhoneRoute.A2A) }
    BackHandler(enabled = route != PhoneRoute.CHAT && !drawer.isOpen) { route = PhoneRoute.CHAT }
    PhoneTypeScale {
        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = route == PhoneRoute.CHAT,
            drawerContent = {
                ModalDrawerSheet(drawerState = drawer, drawerContainerColor = ConsoleColors.bg) {
                    PhoneDrawer(state, drawer.isOpen, showA2a = a2aActions != null, actions = screenActions, onGo = go)
                }
            },
        ) {
            Box(Modifier.fillMaxSize().background(phoneBackdrop()).statusBarsPadding().navigationBarsPadding().imePadding()) {
                PhoneRouteContent(route, state, pendingInput, onPendingInputConsumed, screenActions, a2aClient, a2aActions, navigate = { route = it }) {
                    scope.launch { drawer.open() }
                }
            }
        }
    }
    htmlViewer?.let { request -> HtmlViewerDialog(request = request, onDismiss = { htmlViewer = null }) }
}

@Composable
private fun PhoneRouteContent(
    route: PhoneRoute,
    state: UiState,
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
    when (route) {
        PhoneRoute.CHAT -> PhoneChat(state, pendingInput, onPendingInputConsumed, actions, openDrawer) { turn ->
            trajectoryTurn = turn
            navigate(PhoneRoute.TRAJECTORY)
        }
        PhoneRoute.PLANS -> PhonePlans(back)
        PhoneRoute.TRAJECTORY -> PhoneTrajectory(state, actions, trajectoryTurn, back)
        PhoneRoute.A2A -> if (a2aActions != null) PhoneA2a(a2aClient, a2aActions, back) else back()
        PhoneRoute.SETTINGS -> PhoneSettings(state, actions, back)
    }
}

/** The phone's ground: the console's, washed with its accent at the top and lighter at the foot. */
@Composable
private fun phoneBackdrop(): Brush {
    val colors = ConsoleColors
    val top = lerp(colors.bg, colors.accentSoft, BACKDROP_WASH)
    // The status bar takes the backdrop's top, so the wash runs up under the clock, and the console's colour again when
    // the phone screens go (the layout switched to the console).
    val view = LocalView.current
    val ground = colors.bg
    DisposableEffect(view, top, ground) {
        val window = (view.context as? Activity)?.window?.takeUnless { view.isInEditMode }
        window?.statusBarColor = top.toArgb()
        onDispose { window?.statusBarColor = ground.toArgb() }
    }
    return Brush.verticalGradient(listOf(top, colors.bg, colors.panel))
}

/** The phone reads the console's type a fifth larger, as the phone design sets it; the user's own font scale still applies. */
@Composable
private fun PhoneTypeScale(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density, base.fontScale * PHONE_TYPE_SCALE), content = content)
}

private const val PHONE_TYPE_SCALE = 1.2f
private const val BACKDROP_WASH = 0.6f
