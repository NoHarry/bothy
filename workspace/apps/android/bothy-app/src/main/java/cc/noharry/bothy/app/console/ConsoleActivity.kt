package cc.noharry.bothy.app.console
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cc.noharry.bothy.app.Onboarding
import cc.noharry.bothy.app.OnboardingScreen
import cc.noharry.bothy.app.a2a.A2aClientActions
import cc.noharry.bothy.app.a2a.A2aClientConsole
import cc.noharry.bothy.app.a2a.A2aClientState
import cc.noharry.bothy.app.a2a.A2aInputSource
import cc.noharry.bothy.app.console.ui.DesignDensity
import cc.noharry.bothy.app.console.ui.UiScale
import cc.noharry.bothy.app.overlay.OverlayControl
import cc.noharry.bothy.app.overlay.OverlayService
import cc.noharry.bothy.app.runtime.RuntimeSession
import cc.noharry.bothy.app.runtime.safPickerLaunchFailedMessage
import cc.noharry.bothy.app.runtime.safPickerUnavailableMessage
import cc.noharry.bothy.app.session.DEBUG_ALREADY_SUBMITTED_EXTRA
import cc.noharry.bothy.app.session.DEBUG_TEXT_EXTRA
import cc.noharry.bothy.app.session.UiState
import cc.noharry.bothy.app.voice.AsrTextServerManager
import cc.noharry.bothy.app.voice.loadVoiceConfig

class ConsoleActivity : ComponentActivity() {
    private var uiState by mutableStateOf<UiState?>(null)
    private var a2aClientState by mutableStateOf(A2aClientState())
    private var pendingInput by mutableStateOf("")
    /** The first launch without a model key shows the setup ([Onboarding]) instead of the console. */
    private var onboarding by mutableStateOf(false)
    private var detachSession: (() -> Unit)? = null
    private var detachA2aClient: (() -> Unit)? = null
    private val safWorkspacePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { RuntimeSession.selectSafWorkspace(this, it) }
    }

    /** The overlay steps aside while this full-screen console is visible, and comes back when it leaves. */
    override fun onStart() {
        super.onStart()
        ConsoleVisibility.set(this, visible = true)
        // With 语音唤醒 on, 小博 comes up with the app, not only when opened by hand (the wake word listens either way).
        if (loadVoiceConfig(this).overlayWake) OverlayControl.ensureRunning(this)
    }

    override fun onStop() {
        ConsoleVisibility.set(this, visible = false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        if (UiScale.phone(this)) sideways()
        AsrTextServerManager.start(applicationContext)
        detachSession = RuntimeSession.attach(this) { uiState = it }
        detachA2aClient = A2aClientConsole.attach(this) { a2aClientState = it }
        onboarding = Onboarding.needed(this)
        setContent {
            DesignDensity {
                ConsoleTheme {
                    if (onboarding) {
                        OnboardingScreen {
                            Onboarding.finish(this)
                            onboarding = false
                        }
                        return@ConsoleTheme
                    }
                    uiState?.let { state ->
                        ConsoleScreen(
                            state = state,
                            pendingInput = pendingInput,
                            onPendingInputConsumed = { pendingInput = "" },
                            actions = actions(),
                            a2aClient = a2aClientState,
                            a2aActions = a2aClientActions(),
                        )
                    }
                }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        detachSession?.invoke()
        detachSession = null
        detachA2aClient?.invoke()
        detachA2aClient = null
        super.onDestroy()
    }

    private fun a2aClientActions(): A2aClientActions =
        A2aClientActions(
            setEnabled = { enabled -> A2aClientConsole.setEnabled(this, enabled) },
            send = { text -> A2aClientConsole.send(this, text, A2aInputSource.CONSOLE) },
            steer = { text -> A2aClientConsole.steer(this, text, A2aInputSource.CONSOLE) },
            answer = { questionId, text -> A2aClientConsole.answer(this, questionId, text, A2aInputSource.CONSOLE) },
            cancel = { A2aClientConsole.cancel(this) },
            newConversation = { A2aClientConsole.newConversation(this) },
            openOverlay = ::openOverlay,
            setSpeakExternal = { speak -> A2aClientConsole.setSpeakExternal(this, speak) },
        )

    private fun actions(): ConsoleActions =
        ConsoleActions(
            submit = { text -> RuntimeSession.submitText(this, text) },
            steer = { text -> RuntimeSession.postSteeringText(text) },
            answerQuestion = { question, text -> RuntimeSession.answerQuestion(this, question, text) },
            approveConfirmation = { pendingId -> RuntimeSession.resolveConfirmation(pendingId, approve = true) },
            denyConfirmation = { pendingId -> RuntimeSession.resolveConfirmation(pendingId, approve = false) },
            selectConfirmationMode = { mode -> RuntimeSession.selectConfirmationMode(mode) },
            stop = { RuntimeSession.stopRun() },
            newSession = { RuntimeSession.newSession(this) },
            selectSession = { id -> RuntimeSession.selectSession(this, id) },
            deleteSession = { id -> RuntimeSession.deleteSession(this, id) },
            clearRuntimeData = { RuntimeSession.clearRuntimeData(this) },
            clearMemoriesAndRuntimeData = { done -> RuntimeSession.clearRuntimeData(this, alsoMemories = true, onDone = done) },
            selectProvider = { provider -> RuntimeSession.selectProvider(this, provider) },
            selectProfile = { profileId -> RuntimeSession.selectProfile(this, profileId) },
            setManualProfileEnabled = { enabled -> RuntimeSession.setManualProfileEnabled(this, enabled) },
            selectModel = { model -> RuntimeSession.selectModel(this, model) },
            selectVariant = { variant -> RuntimeSession.selectVariant(this, variant) },
            toggleStream = { RuntimeSession.toggleStream() },
            selectBackend = { simulated -> RuntimeSession.selectBackend(simulated) },
            reloadConfig = { RuntimeSession.reloadConfig(this) },
            saveConfig = { provider, apiKey, model, baseUrl, endpoint ->
                RuntimeSession.saveProviderOverride(this, provider, apiKey, model, baseUrl, endpoint)
            },
            saveNewProfile = { label, provider, endpoint, apiKey, model, baseUrl ->
                RuntimeSession.saveNewProviderProfile(this, label, provider, endpoint, apiKey, model, baseUrl)
            },
            saveProviderModels = { editor, models -> RuntimeSession.saveProviderModels(this, editor, models) },
            deleteProfile = { profileId -> RuntimeSession.deleteProviderProfile(this, profileId) },
            fetchModels = { profileId, provider, endpoint, apiKey, model, baseUrl, onModels ->
                RuntimeSession.fetchProviderModels(this, profileId, provider, endpoint, apiKey, model, baseUrl, onModels)
            },
            toggleItem = { item -> RuntimeSession.toggleTranscriptItem(item) },
            setMcpProviderEnabled = { id, enabled -> RuntimeSession.setMcpProviderEnabled(this, id, enabled) },
            saveMcpServer = { draft -> RuntimeSession.saveMcpServer(this, draft) },
            deleteMcpServer = { id -> RuntimeSession.deleteMcpServer(this, id) },
            setA2aProfileEnabled = { profileId, enabled ->
                RuntimeSession.setA2aProfileEnabled(this, profileId, enabled)
            },
            saveA2aProfile = { draft ->
                with(draft) { RuntimeSession.saveA2aProfile(this@ConsoleActivity, label, moduleId, endpoint, allowLoopback, description, token) }
            },
            deleteA2aProfile = { profileId -> RuntimeSession.deleteA2aProfile(this, profileId) },
            pickSafWorkspace = ::pickSafWorkspace,
            clearSafWorkspace = { RuntimeSession.clearSafWorkspace(this) },
            openOverlay = ::openOverlay,
            // Overridden by the Compose screen, which owns the viewer dialog state.
            openHtmlViewer = { _, _ -> },
            trajectory = TrajectoryActions(
                refresh = { RuntimeSession.refreshTrajectory(this) },
                loadOlderRuns = { RuntimeSession.loadOlderTrajectoryRuns(this) },
                loadRun = { runId -> RuntimeSession.loadTrajectoryRun(this, runId) },
            ),
        )

    private fun pickSafWorkspace() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        if (intent.resolveActivity(packageManager) == null) {
            RuntimeSession.reportSafWorkspaceUnavailable(safPickerUnavailableMessage())
            return
        }
        try {
            safWorkspacePicker.launch(null)
        } catch (error: ActivityNotFoundException) {
            RuntimeSession.reportSafWorkspaceUnavailable(safPickerUnavailableMessage())
        } catch (error: RuntimeException) {
            RuntimeSession.reportSafWorkspaceUnavailable(safPickerLaunchFailedMessage(error))
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val text = intent.getStringExtra(DEBUG_TEXT_EXTRA) ?: return
        if (!intent.getBooleanExtra(DEBUG_ALREADY_SUBMITTED_EXTRA, false)) pendingInput = text
    }

    /**
     * A phone holds the console sideways, as the playground's head unit (owner 2026-10-23: 手机都固定横屏), without the
     * system bars, which would take a fifth of its height; a swipe brings them back for a moment. The camera's side would
     * stay a black band: the console draws under it and keeps its content clear ([ConsoleScreen]).
     */
    private fun sideways() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
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
