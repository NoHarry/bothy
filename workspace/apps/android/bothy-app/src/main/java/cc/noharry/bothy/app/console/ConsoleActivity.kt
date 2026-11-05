package cc.noharry.bothy.app.console
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import cc.noharry.agent.appserver.protocol.ClientCapabilities
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.SettingsRequests
import cc.noharry.agent.appserver.protocol.TextParams
import cc.noharry.agent.appserver.protocol.UriParams
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.foldedByDefault
import cc.noharry.bothy.app.OnboardingDraft
import cc.noharry.bothy.app.OnboardingScreen
import cc.noharry.bothy.app.a2a.A2aClientActions
import cc.noharry.bothy.app.console.ui.DesignDensity
import cc.noharry.bothy.app.console.ui.UiScale
import cc.noharry.bothy.app.frontend.FrontEndClient
import cc.noharry.bothy.app.frontend.LocalServer
import cc.noharry.bothy.app.frontend.ServerAccess
import cc.noharry.bothy.app.overlay.OverlayService
import cc.noharry.bothy.app.phone.PhoneApp
import cc.noharry.bothy.protocol.OnboardingRequests
import cc.noharry.bothy.protocol.withSpeechLevel

class ConsoleActivity : ComponentActivity() {
    /** Whether this screen is a phone's, worked out once: a fold or a rotation recreates the activity. */
    private val phoneScreen by lazy { UiScale.phone(this) }

    private var uiState by mutableStateOf<ServerState?>(null)
    private var pendingInput by mutableStateOf("")
    /** The first launch without a model key shows the setup ([Onboarding]) instead of the console. */
    private var onboarding by mutableStateOf<Boolean?>(null)
    /** This screen's connection to the server: its state, and the conversation's requests. */
    private var server: FrontEndClient? = null
    private val safWorkspacePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { server?.send(SettingsRequests.WORKSPACE_SELECT, UriParams(it.toString())) }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguages.wrap(newBase))
    }

    /**
     * The overlay steps aside while this full-screen console is visible, and comes back when it leaves. Being in front
     * also brings 小博 up with 语音唤醒 on: the server does that when it hears the app is in front.
     */
    override fun onStart() {
        super.onStart()
        ConsoleVisibility.set(this, visible = true)
    }

    override fun onStop() {
        ConsoleVisibility.set(this, visible = false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        ConsoleAppearance.load(this)
        FrontEnd.load(this)
        // Phones stay in the orientation the person is holding, portrait included. A portrait phone keeps the status bar.
        // The reply's loudness, 50 a second while 小博 speaks, is the overlay's light: left out, the console redraws only
        // when something it shows changes. What the person opened or folded outlives this activity ([ItemFolds]).
        server = FrontEndClient(this, CLIENT_NAME, ClientCapabilities(trajectory = true)).also {
            it.start { state -> uiState = ItemFolds.over(state).withSpeechLevel(null) }
        }
        server?.send(OnboardingRequests.READ, Empty) { onboarding = it.needed }
        setContent {
            // Left to the screen, a tablet held upright shows the phone's screens, at the platform's dp as a phone would.
            val configuration = LocalConfiguration.current
            val phone = FrontEnd.phone(phoneScreen, upright = FrontEnd.upright(this, configuration))
            DesignDensity(design = !phone) {
                ConsoleTheme {
                    // The setup and the panes read the state and save through the server where they sit
                    // ([LocalServer]); the setup may show before the first state arrives.
                    val known = uiState
                    val access = remember(known) { ServerAccess(known ?: ServerState(connected = false), server) }
                    CompositionLocalProvider(LocalServer provides access) {
                        when (onboarding) {
                            // Until the server says whether the setup shows, neither shows.
                            null -> Unit
                            true -> OnboardingScreen {
                                server?.send(OnboardingRequests.FINISH, Empty)
                                OnboardingDraft.clear()
                                onboarding = false
                            }
                            false -> known?.let { FrontEndScreen(it, phone) }
                        }
                    }
                }
            }
        }
        // A recreated activity is handed the same intent again. A debug utterance is filled in once.
        if (savedInstanceState == null) handleIntent(intent)
    }

    /**
     * One runtime, two front ends: a phone, or a tablet held upright, has screens of its own ([phone]), a tablet on its
     * side and a head unit the console ([FrontEnd]); switching in the settings swaps them at once.
     */
    @Composable
    private fun FrontEndScreen(state: ServerState, phone: Boolean) {
        if (phone) {
            PhoneApp(state, pendingInput, { pendingInput = "" }, actions(), state.a2aClient, a2aClientActions())
        } else {
            ConsoleScreen(
                state = state,
                pendingInput = pendingInput,
                onPendingInputConsumed = { pendingInput = "" },
                actions = actions(),
                a2aClient = state.a2aClient,
                a2aActions = a2aClientActions(),
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        server?.close()
        server = null
        super.onDestroy()
    }

    private fun a2aClientActions(): A2aClientActions = consoleA2aActions(requireNotNull(server), ::openOverlay)

    private fun actions(): ConsoleActions =
        consoleActions(requireNotNull(server), resources, ::isFolded, ::pickSafWorkspace, ::openOverlay)

    private fun isFolded(item: ConversationItem): Boolean = uiState?.isFolded(item) ?: item.foldedByDefault

    private fun pickSafWorkspace() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        // The server words it: no detail means there is no picker at all.
        if (intent.resolveActivity(packageManager) == null) {
            server?.send(SettingsRequests.WORKSPACE_UNAVAILABLE, TextParams(""))
            return
        }
        try {
            safWorkspacePicker.launch(null)
        } catch (error: ActivityNotFoundException) {
            server?.send(SettingsRequests.WORKSPACE_UNAVAILABLE, TextParams(""))
        } catch (error: RuntimeException) {
            val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            server?.send(SettingsRequests.WORKSPACE_UNAVAILABLE, TextParams(detail))
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val text = intent.getStringExtra(TEXT_EXTRA) ?: return
        if (!intent.getBooleanExtra(ALREADY_SUBMITTED_EXTRA, false)) pendingInput = text
    }

    private companion object {
        const val CLIENT_NAME = "console"

        /** A debug launch's words for the composer (`am start ... --es text ...`), unless already sent. */
        const val TEXT_EXTRA = "text"
        const val ALREADY_SUBMITTED_EXTRA = "already_submitted"
    }

    private fun openOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startService(Intent(this, OverlayService::class.java))
        moveTaskToBack(true)
    }

}
