package cc.noharry.bothy.app.phoneassist

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import cc.noharry.agent.appserver.protocol.ApprovalDecision
import cc.noharry.agent.appserver.protocol.ApprovalRespondParams
import cc.noharry.agent.appserver.protocol.ClientRequests
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.OverlayCommand
import cc.noharry.agent.appserver.protocol.RunSettingsParams
import cc.noharry.agent.appserver.protocol.SettingsRequests
import cc.noharry.agent.appserver.protocol.TextParams
import cc.noharry.agent.appserver.protocol.TurnInterruptParams
import cc.noharry.agent.appserver.protocol.TurnStartParams
import cc.noharry.agent.appserver.protocol.WakeOrigin
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.view.OverlayPhase
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.AppLanguages
import cc.noharry.bothy.app.console.ConsoleActivity
import cc.noharry.bothy.app.console.ConsoleAppearance
import cc.noharry.bothy.app.console.DisplaySettings
import cc.noharry.bothy.app.overlay.AndroidUseOverlay
import cc.noharry.bothy.app.overlay.EXTRA_FOREGROUND
import cc.noharry.bothy.app.overlay.OverlayConnection
import cc.noharry.bothy.app.overlay.OverlayControl
import cc.noharry.bothy.app.overlay.OverlayVoice
import cc.noharry.bothy.app.overlay.followSubagents
import cc.noharry.bothy.app.overlay.overlayAnnounces
import cc.noharry.bothy.app.overlay.startOverlayForeground
import cc.noharry.bothy.protocol.VoiceRequests
import cc.noharry.bothy.protocol.view.overlayHud
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The phone's assistant over other apps ([PhoneAssistant]), apart from the head unit's overlay in all but the
 * connection, the voice and the hooks they both answer through. Three windows: the capsule at the top while 小博 works
 * with the sheet down ([PhoneCapsule]), the sheet from the bottom ([PhoneSheet]), and the edge light while 小博
 * operates the screen ([PhoneUseLight]).
 *
 * The sheet comes up when woken, when asked (an A2A caller, a tap on the capsule while a task runs), when a request
 * starts or its answer arrives, and when the run waits for an approval; an answer to a task the person closed while
 * it ran does not bring it back. The service goes once the sheet is down with nothing under way. Main thread.
 */
class PhoneAssistantService : Service() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguages.wrap(newBase))

    override fun onBind(intent: Intent?): IBinder? = null

    private lateinit var capsule: PhoneCapsule
    private lateinit var sheet: PhoneSheet
    private lateinit var light: PhoneUseLight
    private lateinit var live: PhoneLiveUpdate
    private lateinit var voice: OverlayVoice
    private val model = PhoneSheetModel()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var using = false
    private var capturing = false
    /** The sheet was down when an answer or request wanted it (while 小博 operated the screen): it comes up after. */
    private var wantSheet = false
    private var lastPhase: OverlayPhase? = null
    private var shownApproval: String? = null
    /** Closed while its task ran: that task's answer stays in the capsule's place, the next request opens again. */
    private var closedRun = false
    private var destroyed = false
    private var lastStartId = 0
    private val rest = Runnable { restOrLeave() }
    private val useIdle = object : Runnable {
        override fun run() {
            if (model.state.activeSessionCount == 0) endUse() else main.postDelayed(this, USE_IDLE_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        ConsoleAppearance.load(this)
        val windows = getSystemService(WindowManager::class.java)
        light = PhoneUseLight(this, windows)
        live = PhoneLiveUpdate(this, inForeground = { ::voice.isInitialized && voice.inForeground })
        sheet = PhoneSheet(this, windows, model, sheetActions())
        capsule = PhoneCapsule(this, windows, ::capsuleTapped)
        OverlayControl.owner = this
        OverlayConnection.open(this, ::stateChanged)
        voice = OverlayVoice(this, onWoken = ::woken, onChanged = ::refresh, onDismissed = ::fold)
        OverlayControl.presenter = { command -> if (command == OverlayCommand.SHOW) openSheet() else fold() }
        OverlayControl.closer = { stopSelf() }
        OverlayControl.androidUse = useHooks
        scope.followSubagents { refresh() }
        armRest()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Started from the background as a foreground service: it must promote itself within seconds.
        if (intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true) {
            if (::voice.isInitialized) voice.promoteForA2a() else startOverlayForeground()
        }
        if (intent?.action == ACTION_OPEN_SHEET && ::sheet.isInitialized) openSheet()
        if (intent?.action == ACTION_STOP_TASK) OverlayConnection.send(ClientRequests.TURN_INTERRUPT, TurnInterruptParams())
        if (intent?.action == ACTION_WAKE && ::voice.isInitialized) voice.wake(WakeOrigin.LONG_PRESS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        if (::sheet.isInitialized) {
            main.removeCallbacksAndMessages(null)
            OverlayControl.release(this)
            voice.destroy()
            scope.cancel()
            capsule.destroy()
            sheet.destroy()
            light.destroy()
            live.clear()
            OverlayConnection.close(this)
        }
        super.onDestroy()
    }

    private fun stateChanged(state: ServerState) {
        val hud = state.overlayHud(System.currentTimeMillis(), DisplaySettings.showThinking.value)
        model.state = state
        model.hud = hud
        val previous = lastPhase
        lastPhase = hud.phase
        if (hud.phase == OverlayPhase.LIVE) model.fresh = false
        if (using && state.activeSessionCount == 0) endUse()
        if (previous != null && overlayAnnounces(previous, hud.phase)) {
            if (!(closedRun && previous == OverlayPhase.LIVE)) openSheet()
            closedRun = false
        }
        val approval = state.waitingApproval()?.item?.pendingId
        if (approval != null && approval != shownApproval) {
            // The run waits for the person: the screen is theirs to answer it.
            endUse()
            openSheet()
        }
        shownApproval = approval
        refresh()
    }

    /**
     * The capsule (or on Android 16 the status bar's live update, with no window over the app) and the edge light as
     * things stand; the sheet reads [model] itself. While 小博 operates the screen the light's window says what the
     * capsule would. A screenshot leaves out the windows, not the live update, which the system draws.
     */
    private fun refresh() {
        if (destroyed) return
        val signal = if (sheet.shown) null else phoneSignal(resources, model.state, using)
        if (!capturing && !(signal != null && live.takes(signal) && live.show(signal))) live.clear()
        val windowed = signal.takeIf { !live.shown && !capturing }
        capsule.show(windowed.takeIf { !using })
        light.status = windowed.takeIf { using }
        light.shown = using && !capturing
        armRest()
    }

    private fun woken() {
        model.fresh = true
        model.typing = false
        openSheet()
    }

    private fun openSheet() {
        if (destroyed) return
        Log.i(TAG, "sheet up (shown=${sheet.shown}, using=$using)")
        if (using) {
            wantSheet = true
            return
        }
        wantSheet = false
        if (!sheet.shown) sheet.show()
        refresh()
    }

    private fun fold() {
        if (destroyed) return
        Log.i(TAG, "sheet down")
        model.typing = false
        sheet.hide()
        refresh()
    }

    /** The person closed the sheet: a woken session ends with it, and the task it showed keeps its answer quiet. */
    private fun close() {
        voice.cardClosed()
        closedRun = model.hud?.phase == OverlayPhase.LIVE
        model.fresh = false
        fold()
    }

    /** As a tap on 小博 elsewhere: while a task runs the sheet shows it (a wake would stop it); otherwise 小博 listens. */
    private fun capsuleTapped() {
        Log.i(TAG, "capsule tapped (running=${model.state.isRunning})")
        if (model.state.isRunning || !voice.tap { openSheet() }) openSheet()
    }

    /** Goes once the sheet is down and nothing shows or listens; looks again in a while otherwise. */
    private fun restOrLeave() {
        val busy = sheet.shown || capsule.shown || live.shown || using || OverlayConnection.voice.listening
        // A start delivered meanwhile (a wake, an open) keeps it.
        if (busy || !stopSelfResult(lastStartId)) armRest() else Log.i(TAG, "nothing to show: leaving")
    }

    private fun armRest() {
        main.removeCallbacks(rest)
        main.postDelayed(rest, REST_CHECK_MS)
    }

    private fun sheetActions() = PhoneSheetActions(
        fold = ::fold,
        close = ::close,
        send = { text -> start(text) },
        steer = { text -> OverlayConnection.send(ClientRequests.TURN_STEER, TextParams(text)); model.typing = false },
        interruptWith = { text ->
            model.typing = false
            OverlayConnection.send(ClientRequests.TURN_INTERRUPT, TurnInterruptParams()) { start(text) }
        },
        stop = { OverlayConnection.send(ClientRequests.TURN_INTERRUPT, TurnInterruptParams()) },
        stopListening = { OverlayConnection.send(VoiceRequests.STOP, Empty) },
        talk = { if (!voice.tap { typing(true) }) typing(true) },
        type = ::typing,
        reply = ::start,
        approve = { id -> OverlayConnection.send(ClientRequests.APPROVAL_RESPOND, ApprovalRespondParams(id, ApprovalDecision.APPROVE)) },
        deny = { id -> OverlayConnection.send(ClientRequests.APPROVAL_RESPOND, ApprovalRespondParams(id, ApprovalDecision.DENY)) },
        allowSession = { id ->
            OverlayConnection.send(SettingsRequests.RUN_SET, RunSettingsParams(confirmationMode = ConfirmationMode.ALLOW_ALL_SESSION))
            OverlayConnection.send(ClientRequests.APPROVAL_RESPOND, ApprovalRespondParams(id, ApprovalDecision.APPROVE))
        },
        openApp = ::openApp,
        copy = ::copy,
    )

    private fun start(text: String) {
        model.typing = false
        model.fresh = false
        sheet.focusable(false)
        OverlayConnection.send(ClientRequests.TURN_START, TurnStartParams(text))
    }

    private fun typing(on: Boolean) {
        model.typing = on
        sheet.focusable(on)
    }

    private fun openApp() {
        fold()
        startActivity(Intent(this, ConsoleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(getString(R.string.phone_assist_name), text))
        Toast.makeText(this, R.string.phone_assist_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * Android Use as a phone shows it: the sheet goes at once, the edge lights up and says what 小博 does, in a window
     * that takes no taps, so 小博's own land on the app; it leaves the screenshots. Any step means 小博 operates the
     * screen, also one with no [AndroidUseOverlay.begin] before it (the assistant came up mid-run): a step that has
     * just put the windows away waits a frame for the screen to show it.
     */
    private val useHooks = object : AndroidUseOverlay {
        override fun begin(onReady: () -> Unit) = afterUse(onReady)

        override fun point(x: Int, y: Int, press: Boolean, onArrived: () -> Unit) {
            if (press) light.tapAt(x, y)
            afterUse(onArrived)
        }

        override fun drag(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long, onStarted: () -> Unit) = afterUse(onStarted)

        override fun hide(onHidden: () -> Unit) {
            noteUse()
            capturing = true
            refresh()
            capsule.afterFrame(onHidden)
        }

        override fun reveal() {
            capturing = false
            refresh()
        }
    }

    private fun afterUse(then: () -> Unit) = if (noteUse()) capsule.afterFrame(then) else then()

    /** 小博 operates the screen; true when it starts now (the sheet and the capsule have just been put away). */
    private fun noteUse(): Boolean {
        main.removeCallbacks(useIdle)
        main.postDelayed(useIdle, USE_IDLE_MS)
        if (using) return false
        using = true
        if (sheet.shown) {
            model.typing = false
            sheet.hideNow()
        }
        refresh()
        return true
    }

    /** Android Use is over: the light goes, and an answer that came meanwhile brings the sheet up. */
    private fun endUse() {
        if (!using) return
        using = false
        capturing = false
        main.removeCallbacks(useIdle)
        refresh()
        if (wantSheet) openSheet()
    }
}

private const val TAG = "PhoneAssist"
private const val REST_CHECK_MS = 4_000L
/** Without any Android Use call for this long, and no run going on, the screen is the person's again. */
private const val USE_IDLE_MS = 20_000L
