package cc.noharry.bothy.sample

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Window
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class DebugConsoleActivity : ComponentActivity() {
    private var uiState by mutableStateOf<DebugUiState?>(null)
    private var a2aClientState by mutableStateOf(DebugA2aClientState())
    private var pendingInput by mutableStateOf("")
    private var detachSession: (() -> Unit)? = null
    private var detachA2aClient: (() -> Unit)? = null
    private val safWorkspacePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { DebugRuntimeSession.selectSafWorkspace(this, it) }
    }

    /** The overlay steps aside while this full-screen console is visible, and comes back when it leaves. */
    override fun onStart() {
        super.onStart()
        DebugConsoleVisibility.set(this, visible = true)
        // With 语音唤醒 on, 小博 comes up with the app, not only when opened by hand (the wake word listens either way).
        if (loadDebugVoiceConfig(this).overlayWake) DebugOverlayControl.ensureRunning(this)
    }

    override fun onStop() {
        DebugConsoleVisibility.set(this, visible = false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        DebugAsrTextServerManager.start(applicationContext)
        detachSession = DebugRuntimeSession.attach(this) { uiState = it }
        detachA2aClient = DebugA2aClientConsole.attach(this) { a2aClientState = it }
        setContent {
            DebugDesignDensity {
                DebugConsoleTheme {
                    uiState?.let { state ->
                        DebugConsoleScreen(
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

    private fun a2aClientActions(): DebugA2aClientActions =
        DebugA2aClientActions(
            setEnabled = { enabled -> DebugA2aClientConsole.setEnabled(this, enabled) },
            send = { text -> DebugA2aClientConsole.send(this, text, DebugA2aInputSource.CONSOLE) },
            steer = { text -> DebugA2aClientConsole.steer(this, text, DebugA2aInputSource.CONSOLE) },
            answer = { questionId, text -> DebugA2aClientConsole.answer(this, questionId, text, DebugA2aInputSource.CONSOLE) },
            cancel = { DebugA2aClientConsole.cancel(this) },
            newConversation = { DebugA2aClientConsole.newConversation(this) },
            openOverlay = ::openOverlay,
            setSpeakExternal = { speak -> DebugA2aClientConsole.setSpeakExternal(this, speak) },
        )

    private fun actions(): DebugConsoleActions =
        DebugConsoleActions(
            submit = { text -> DebugRuntimeSession.submitText(this, text) },
            steer = { text -> DebugRuntimeSession.postSteeringText(text) },
            answerQuestion = { question, text -> DebugRuntimeSession.answerQuestion(this, question, text) },
            approveConfirmation = { pendingId -> DebugRuntimeSession.resolveConfirmation(pendingId, approve = true) },
            denyConfirmation = { pendingId -> DebugRuntimeSession.resolveConfirmation(pendingId, approve = false) },
            selectConfirmationMode = { mode -> DebugRuntimeSession.selectConfirmationMode(mode) },
            stop = { DebugRuntimeSession.stopRun() },
            newSession = { DebugRuntimeSession.newSession(this) },
            selectSession = { id -> DebugRuntimeSession.selectSession(this, id) },
            deleteSession = { id -> DebugRuntimeSession.deleteSession(this, id) },
            clearRuntimeData = { DebugRuntimeSession.clearRuntimeData(this) },
            clearMemoriesAndRuntimeData = { done -> DebugRuntimeSession.clearRuntimeData(this, alsoMemories = true, onDone = done) },
            selectProvider = { provider -> DebugRuntimeSession.selectProvider(this, provider) },
            selectProfile = { profileId -> DebugRuntimeSession.selectProfile(this, profileId) },
            setManualProfileEnabled = { enabled -> DebugRuntimeSession.setManualProfileEnabled(this, enabled) },
            selectModel = { model -> DebugRuntimeSession.selectModel(this, model) },
            selectVariant = { variant -> DebugRuntimeSession.selectVariant(this, variant) },
            toggleStream = { DebugRuntimeSession.toggleStream() },
            selectBackend = { simulated -> DebugRuntimeSession.selectBackend(simulated) },
            reloadConfig = { DebugRuntimeSession.reloadConfig(this) },
            saveConfig = { provider, apiKey, model, baseUrl, endpoint ->
                DebugRuntimeSession.saveProviderOverride(this, provider, apiKey, model, baseUrl, endpoint)
            },
            saveNewProfile = { label, provider, endpoint, apiKey, model, baseUrl ->
                DebugRuntimeSession.saveNewProviderProfile(this, label, provider, endpoint, apiKey, model, baseUrl)
            },
            saveProviderModels = { editor, models -> DebugRuntimeSession.saveProviderModels(this, editor, models) },
            deleteProfile = { profileId -> DebugRuntimeSession.deleteProviderProfile(this, profileId) },
            fetchModels = { profileId, provider, endpoint, apiKey, model, baseUrl, onModels ->
                DebugRuntimeSession.fetchProviderModels(this, profileId, provider, endpoint, apiKey, model, baseUrl, onModels)
            },
            toggleItem = { item -> DebugRuntimeSession.toggleTranscriptItem(item) },
            setMcpProviderEnabled = { id, enabled -> DebugRuntimeSession.setMcpProviderEnabled(this, id, enabled) },
            saveMcpServer = { draft -> DebugRuntimeSession.saveMcpServer(this, draft) },
            deleteMcpServer = { id -> DebugRuntimeSession.deleteMcpServer(this, id) },
            setA2aProfileEnabled = { profileId, enabled ->
                DebugRuntimeSession.setA2aProfileEnabled(this, profileId, enabled)
            },
            saveA2aProfile = { draft ->
                with(draft) { DebugRuntimeSession.saveA2aProfile(this@DebugConsoleActivity, label, moduleId, endpoint, allowLoopback, description, token) }
            },
            deleteA2aProfile = { profileId -> DebugRuntimeSession.deleteA2aProfile(this, profileId) },
            pickSafWorkspace = ::pickSafWorkspace,
            clearSafWorkspace = { DebugRuntimeSession.clearSafWorkspace(this) },
            openOverlay = ::openOverlay,
            // Overridden by the Compose screen, which owns the viewer dialog state.
            openHtmlViewer = { _, _ -> },
            trajectory = TrajectoryActions(
                refresh = { DebugRuntimeSession.refreshTrajectory(this) },
                loadOlderRuns = { DebugRuntimeSession.loadOlderTrajectoryRuns(this) },
                loadRun = { runId -> DebugRuntimeSession.loadTrajectoryRun(this, runId) },
            ),
        )

    private fun pickSafWorkspace() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        if (intent.resolveActivity(packageManager) == null) {
            DebugRuntimeSession.reportSafWorkspaceUnavailable(debugSafPickerUnavailableMessage())
            return
        }
        try {
            safWorkspacePicker.launch(null)
        } catch (error: ActivityNotFoundException) {
            DebugRuntimeSession.reportSafWorkspaceUnavailable(debugSafPickerUnavailableMessage())
        } catch (error: RuntimeException) {
            DebugRuntimeSession.reportSafWorkspaceUnavailable(debugSafPickerLaunchFailedMessage(error))
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val text = intent.getStringExtra(DEBUG_TEXT_EXTRA) ?: return
        if (!intent.getBooleanExtra(DEBUG_ALREADY_SUBMITTED_EXTRA, false)) pendingInput = text
    }

    private fun openOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startService(Intent(this, DebugOverlayService::class.java))
        moveTaskToBack(true)
    }

}
