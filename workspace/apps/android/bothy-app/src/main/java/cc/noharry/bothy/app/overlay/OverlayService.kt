package cc.noharry.bothy.app.overlay
import android.app.Service
import android.content.Intent
import android.graphics.Rect
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import cc.noharry.bothy.app.a2a.*
import cc.noharry.bothy.app.a2ui.*
import cc.noharry.bothy.app.androiduse.*
import cc.noharry.bothy.app.console.*
import cc.noharry.bothy.app.console.ui.UiScale
import cc.noharry.bothy.app.runtime.RuntimeSession
import cc.noharry.bothy.app.session.UiState
import kotlin.math.roundToInt
import kotlinx.coroutines.*

/**
 * Floating assistant for car landscape HUIs (also fits portrait): 小博 as a small bubble the user can park
 * anywhere, opening into a card when something arrives or when asked, and folding back when done.
 *
 * One long-lived Compose stage reads two state objects: the HUD projection of the session (or the A2A client in
 * client mode) and the window-side UI state. Renders only replace state, so the A2UI pick surface and scroll
 * positions survive updates while the card is open, and a dragged card stays where the user put it.
 *
 * Opening: 小博 notices, flies from its spot to where its seat in the card will be, and the card unfolds around it.
 * Closing reverses that. The bubble, the flight ([OverlayFlight]) and the card are separate windows that draw
 * one shared [MascotPose]; each hand-over shows 小博 in the next window before the last one lets go, and only one
 * window drives the pose at a time (the old one stops, the new one starts a frame later).
 */
class OverlayService : Service(), OverlayBubble.Listener {
    private val composeOwner = OverlayComposeOwner()
    private lateinit var windowManager: WindowManager
    private lateinit var root: ComposeView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var bubble: OverlayBubble
    /** The folded 小博's ring and capsule for the delegation under way. */
    private lateinit var glance: OverlayTaskGlance
    private lateinit var flight: OverlayFlight
    private lateinit var useStage: AndroidUseStage
    private lateinit var voice: OverlayVoice
    private val mascot = MascotPose()
    private var detachSession: (() -> Unit)? = null
    private var detachClient: (() -> Unit)? = null
    private var latestState: UiState? = null
    private var renderScheduled: Boolean = false
    private var placedLandscape: Boolean? = null

    private var hud by mutableStateOf<OverlayHud?>(null)
    private var ui by mutableStateOf(OverlayUi())
    private var showInput: Boolean = false
    private var showDetails: Boolean = false
    /** Choices already answered by a tap; cleared once the stage shows none, so a repeat offer is tappable again. */
    private var submittedChoices: List<DisplayChoice>? = null
    private var submittedControls: List<Control>? = null
    /** The tapped pick, shown as confirmed until the follow-up run ends or a new offer appears. */
    private var chosenChoice: DisplayChoice? = null
    /** The picture shown large; a new picture (or none) returns the card to normal. */
    private var expandedPicture: String? = null
    private val expandedToolIds = linkedSetOf<Long>()

    private var presence = Presence.BUBBLE
    /** Where the user wants the overlay; a transition under way finishes first, then this is honoured. */
    private var wantCard = false
    private var cardComposed by mutableStateOf(false)
    private var cardShown = false
    private var mascotDriving = false
    private var cardDrawn = true
    /** The header's 小博 centre in the card window's pixels; null until the card has laid out. */
    private var mascotInWindow: Offset? = null
    private var openRequestedAt = 0L
    private var flightStarted = false
    /** A voice wake: no notice beat and a quicker flight, so 小博 is visibly listening at once. */
    private var quickOpen = false
    private var sleeping = false
    private var previewAct: MascotAction? = null
    private val endPreview = Runnable { previewAct = null; scheduleRender(force = true) }
    /** Where a closing card's 小博 lands, fixed when the closing starts. */
    private var landing: Pair<Int, Int> = 0 to 0
    private var startRequest: StartRequest? = null
    private var lastPhase: OverlayPhase? = null
    /** When the open card last became quiet (nothing running, nothing waiting on the user); 0 while it is not. */
    private var quietSince = 0L
    /** Touched while it counted down: stays open until closed or the next request ([OverlayUi.held]). */
    private var held = false
    /** Fresh cards for a wake or tap, the replaced result's chip, and reports held while a result is read. */
    private val results = OverlayResultMemory()
    /** Delegated tasks the device follows ([A2aDelegations]), for the card's tray. */
    private var delegations: List<DelegationView> = emptyList()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Android Use: the card stays folded and 小博 is the pointer on [useStage] from ON until it has flown home. */
    private var useMode = UseMode.OFF
    private var pendingUse: (() -> Unit)? = null
    private val useIdle = Runnable { exitAndroidUse() }

    private val renderRunnable = Runnable { renderLatestState() }
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!::root.isInitialized) return
            val client = A2aClientConsole.state
            val following = delegations.any { !it.stage.settled }
            if (latestState?.isRunning == true || following || clientView() && (client.running || client.mirrors.isNotEmpty())) {
                scheduleRender(force = true)
            }
            root.postDelayed(this, TICK_MS)
        }
    }
    private val autoCollapse = Runnable { collapseIfQuiet() }

    private val actions = OverlayActions(
        onDrag = ::dragBy,
        // Only the user's close ends a woken voice session; folding for Android Use keeps it.
        onClose = {
            voice.cardClosed()
            closedRun = hud?.phase == OverlayPhase.LIVE
            collapse()
        },
        onStop = { if (clientView()) A2aClientConsole.cancel(this) else RuntimeSession.stopRun() },
        onToggleInput = {
            showInput = !showInput
            syncCardFocus()
            interacted()
        },
        onToggleDetails = { showDetails = !showDetails; interacted() },
        onToggleTool = { id -> if (!expandedToolIds.add(id)) expandedToolIds.remove(id); interacted() },
        onSubmit = ::submitPrompt,
        onPick = ::submitChoice,
        onPanel = ::submitPanel,
        // Reported from layout; posting keeps window relayouts out of the measure pass.
        onCardTarget = { card -> root.post { growWindowFor(card) } },
        onCardSettled = { card -> root.post { if (presence == Presence.CARD) scheduleFit(card) } },
        onTogglePicture = {
            val shown = hud?.picture?.reference
            expandedPicture = if (expandedPicture == null) shown else null
            interacted()
        },
        onMascotPlaced = { centre ->
            mascotInWindow = centre
            if (presence == Presence.OPENING) root.post(::flyToCard)
        },
        onRevealSettled = { shown -> root.post { if (shown) cardOpened() else cardFolded() } },
        onLastResult = { results.showLast(); showInput = false; syncCardFocus(); interacted() },
        onPreviousResult = { results.togglePrevious(); interacted() },
        onReleaseReport = { OverlayControl.releaseReports(); interacted() },
        onTouched = { if (quietSince != 0L && !held) { held = true; scheduleRender(force = true) } },
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!canDrawOverlay()) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        root = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { hud?.takeIf { cardComposed }?.let { OverlayStage(it, ui, actions, mascot) } }
        }
        composeOwner.attachTo(root)
        params = overlayParams()
        windowManager.addView(root, params)
        // Added after the card, so the bubble draws on top of it during hand-overs.
        bubble = OverlayBubble(this, windowManager, mascot, overlayWindowType(), stageScale(), this)
        val capsule = OverlayTaskCapsule(this, windowManager, overlayWindowType(), stageScale(), ::openFromCapsule)
        glance = OverlayTaskGlance(bubble, capsule, root) {
            (if (useMode == UseMode.OFF && !sleeping && !AssistantSurface.active) overlayFoldedSignal(delegations) else null) to
                (presence == Presence.BUBBLE)
        }
        flight = OverlayFlight(this, windowManager, mascot, overlayWindowType(), stageScale())
        useStage = AndroidUseStage(this, windowManager, mascot, overlayWindowType(), stageScale())
        // "退下吧" folds the card, unless it shows another A2A client's task, which only that caller may put away.
        voice = OverlayVoice(this, onWoken = ::voiceWoken, onChanged = { scheduleRender(force = true) }, onDismissed = {
            if (!OverlayFocus.external && A2aClientConsole.state.mirrors.isEmpty()) collapse()
        })
        OverlayControl.androidUse = androidUseOverlay
        val (x, y) = parkedSpot()
        bubble.showAt(x, y, touchable = true)
        bubble.driving = true
        OverlayControl.presenter = { command ->
            if (command == OverlayCommand.SHOW) expand() else collapse()
        }
        OverlayControl.mascotPreview = ::previewMascot
        OverlayControl.closer = { stopSelf() }
        detachSession = RuntimeSession.attach(this) { state ->
            latestState = state
            scheduleRender(force = false)
        }
        detachClient = A2aClientConsole.attach(this) { scheduleRender(force = false) }
        serviceScope.launch { A2aDelegations.views.collect { delegations = it; scheduleRender(force = true) } }
        serviceScope.followSubagents { scheduleRender(force = true) }
        // A head unit that shows the assistant itself takes over: the card folds and 小博 hides until it is given back.
        AssistantSurface.follow(this, serviceScope, ::surfaceChanged)
        root.post(tickRunnable)
    }

    /** An A2A caller's `show` opens the card; any other start (leaving the console, a wake) brings 小博 back folded. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started from the background as a foreground service: it must promote itself within seconds.
        if (intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true) {
            if (::voice.isInitialized) voice.promoteForA2a() else startOverlayForeground()
        }
        if (::root.isInitialized) {
            startRequest = if (intent?.action == ACTION_OPEN_OVERLAY_CARD) StartRequest.OPEN else StartRequest.FOLDED
            scheduleRender(force = true)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        OverlayControl.presenter = null
        OverlayControl.closer = null
        OverlayControl.mascotPreview = null
        OverlayControl.androidUse = null
        OverlayControl.readingUntil = 0L
        serviceScope.cancel()
        detachSession?.invoke()
        detachClient?.invoke()
        if (::root.isInitialized) {
            root.removeCallbacks(renderRunnable)
            root.removeCallbacks(tickRunnable)
            root.removeCallbacks(autoCollapse)
            root.removeCallbacks(endPreview)
            root.removeCallbacks(openWatchdog)
            root.removeCallbacks(flyToCardRunnable)
            root.removeCallbacks(fitPending)
            root.removeCallbacks(useIdle)
            voice.destroy()
            useStage.destroy()
            flight.destroy()
            bubble.destroy()
            glance.destroy()
            windowManager.removeView(root)
            composeOwner.destroy()
        }
        super.onDestroy()
    }

    /**
     * Tapping 小博 wakes it ([OverlayVoice.tap]). While a task runs, or where voice cannot listen, the card opens
     * instead; with nothing new on it that means "let me ask": the input box opens, a read result waits behind 上次.
     */
    override fun onBubbleTap() {
        if (latestState?.isRunning != true && voice.tap()) return
        results.openToTalk(latestState?.overlayHud()?.phase)
        if (hud?.phase == null || hud?.phase == OverlayPhase.IDLE || results.fresh) showInput = true
        expand()
    }

    /** A long press wakes voice when 语音唤醒 is on; otherwise 小博 goes to sleep and the overlay closes. */
    override fun onBubbleLongPress() {
        if (presence != Presence.BUBBLE || voice.longPress()) return
        sleeping = true
        bubble.action = MascotAction.SLEEPING
        bubble.setTouchable(false)
        root.postDelayed({ stopSelf() }, SLEEP_MS)
    }

    /** Woken by 你好，小博 or a long press: 小博 takes notice and flies to the card, whose input slot is voice's. */
    private fun voiceWoken() {
        results.woken(lastPhase)
        showInput = false
        quickOpen = true
        mascot.react(MascotReaction.NOTICE)
        expand()
        scheduleRender(force = true)
    }

    override fun onBubbleDragging(dragging: Boolean) = glance.dragging(dragging)

    /** The capsule tapped: the card opens; a waiting result is spoken now instead of waiting for the device. */
    private fun openFromCapsule() {
        if (overlayTaskSignal(delegations, System.currentTimeMillis())?.tone == OverlayTaskTone.RESULT) OverlayControl.releaseReports()
        expand()
    }

    override fun onBubbleDropped(x: Int, y: Int) {
        getSharedPreferences(OVERLAY_PREFS, MODE_PRIVATE).edit().putInt(KEY_BUBBLE_X, x).putInt(KEY_BUBBLE_Y, y).apply()
    }

    /**
     * Anywhere inside the overlay frame and above the dock widgets, which draw over overlays. Staying inside the
     * frame matters: the system pushes a window back into it, and then the bubble is not where its offsets say.
     */
    override fun bubbleBounds(sizePx: Int): Rect {
        val frame = overlayFrame()
        val half = sizePx / 2
        val centre = frame.center
        val bottom = minOf(frame.bottom, dockTopPx().toFloat()) - half
        val left = (frame.left + half - centre.x).roundToInt()
        val right = (frame.right - half - centre.x).roundToInt()
        val top = (frame.top + half - centre.y).roundToInt()
        return Rect(left, top, maxOf(left, right), maxOf(top, (bottom - centre.y).roundToInt()))
    }

    /**
     * The area overlay windows are laid out in, in screen pixels: the screen without the status and system bars
     * (on the bench [0,104]-[2560,1320]). Gravity offsets count from it: the bubble and flight windows from its
     * centre, the card window from its top.
     */
    private fun overlayFrame(): androidx.compose.ui.geometry.Rect {
        val frame = Rect()
        root.getWindowVisibleDisplayFrame(frame)
        if (frame.isEmpty) {
            val metrics = resources.displayMetrics
            frame.set(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        return androidx.compose.ui.geometry.Rect(frame.left.toFloat(), frame.top.toFloat(), frame.right.toFloat(), frame.bottom.toFloat())
    }

    /** Where the dock widgets start, measured from the physical bottom edge they are laid out against. */
    private fun dockTopPx(): Int {
        val real = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(real)
        return real.heightPixels - dockReservePx()
    }

    private fun scheduleRender(force: Boolean) {
        if (force) {
            root.removeCallbacks(renderRunnable)
            renderScheduled = true
            root.post(renderRunnable)
            return
        }
        if (!renderScheduled) {
            renderScheduled = true
            root.postDelayed(renderRunnable, RENDER_DEBOUNCE_MS)
        }
    }

    private fun renderLatestState() {
        renderScheduled = false
        val state = latestState ?: return
        // Client mode, or an inbound A2A task, shows what crossed A2A; otherwise the local session stage.
        updateExternalFocus(state)
        val clientMode = clientView()
        val now = System.currentTimeMillis()
        val next = if (clientMode) A2aClientConsole.state.overlayHud(now) else state.overlayHud(now, DisplaySettings.showThinking.value)
        AssistantSurface.runChanged(this, next, state.takeUnless { clientMode })
        val cardOpen = presence == Presence.CARD
        results.phaseChanged(lastPhase, next.phase)
        val shownState = results.show(next, hud, cardOpen)
        val shown = shownState.hud
        forgetAnswers(shown)
        val landscape = isLandscape()
        val size = cardSizeFor(shown)
        if (placedLandscape != landscape) placeWindow(landscape)
        hud = shown
        ui = cardUi(next, shownState, size, landscape, clientMode, now)
        OverlayControl.readingUntil = results.reading.holdUntil(shownState, cardOpen)
        glance.sync()
        if (!sleeping) {
            val act = previewAct ?: next.mascotAction(ui)
            bubble.action = act
            flight.action = act
            useStage.action = act
        }
        // The run that operated the screen is over: 小博 flies home first, then the reply may open the card. Counted
        // over every session: an A2A task runs in its own, not the selected one, and must keep the glow while it acts.
        if (useMode == UseMode.ON && state.activeSessionCount == 0) exitAndroidUse()
        followPhase(next.phase)
        // A listening card stays open (the session's idle end folds it), and so does one a report waits for (folding as the report starts flew 小博 home and back).
        val reportWaits = delegations.any { it.stage == DelegationStage.REPORTING }
        noteQuiet(if (voice.shown || reportWaits) null else shown.quietCollapseMillis(ui))
    }

    /** The card's state for [shown] (the session's [next], or a remembered result) at [now]. */
    private fun cardUi(next: OverlayHud, shown: OverlayShownResult, size: OverlaySize, landscape: Boolean, clientMode: Boolean, now: Long) =
        OverlayUi(
            scale = stageScale(),
            maxHeight = availableStageHeight(),
            size = size,
            cardWidth = cardWidth(landscape, size),
            clientMode = clientMode,
            landscape = landscape,
            showInput = showInput,
            showDetails = showDetails,
            held = held,
            choicesAnswered = shown.hud.choices.isNotEmpty() && shown.hud.choices == submittedChoices,
            panelAnswered = shown.hud.controls.isNotEmpty() && shown.hud.controls == submittedControls,
            chosen = chosenChoice,
            pictureExpanded = expandedPicture != null && expandedPicture == shown.hud.picture?.reference,
            // Mirrored PC tasks are read-only here: Stop only targets this car's own client task.
            stopVisible = if (clientMode) A2aClientConsole.state.running else next.isRunning,
            pendingQuestion = clientMode && activeClientQuestion() != null,
            expandedToolIds = expandedToolIds.toSet(),
            cardShown = cardShown,
            mascotDriving = mascotDriving,
            cardDrawn = cardDrawn,
            mascotPreview = previewAct,
            voiceShown = voice.shown.also { useStage.voice = it },
            voiceFailure = voice.failure,
            lastResult = shown.lastResult,
            previousResult = shown.previousResult,
            showingPrevious = shown.showingPrevious,
            delegations = delegations.filter { !it.stage.settled || it.stage == DelegationStage.REPORTING },
            nowMillis = now,
        )

    /** Debug review: plays an act for [PREVIEW_MS], or a reaction once, on whichever view shows 小博. */
    private fun previewMascot(name: String): Boolean {
        MascotReaction.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { reaction ->
            mascot.react(reaction)
            return true
        }
        val act = MascotAction.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return false
        mascot.lastAct = null
        previewAct = act
        root.removeCallbacks(endPreview)
        root.postDelayed(endPreview, PREVIEW_MS)
        scheduleRender(force = true)
        return true
    }

    /**
     * The card's X closed it while a run worked: that run's reply does not open it again (2026-10-20: closed,
     * then reopened by the answer six seconds later); the next request does.
     */
    private var closedRun = false

    /** A new request or a reply arriving opens the card; so does a pending start request. */
    private fun followPhase(phase: OverlayPhase) {
        val previous = lastPhase
        lastPhase = phase
        if (phase == OverlayPhase.LIVE) held = false
        when (startRequest.also { startRequest = null }) {
            StartRequest.OPEN -> expand()
            StartRequest.FOLDED -> mascot.react(MascotReaction.NOTICE)
            null -> if (previous != null && overlayAnnounces(previous, phase)) {
                val closedReply = closedRun && previous == OverlayPhase.LIVE
                closedRun = false
                if (!closedReply) expand()
            }
        }
        // An open asked for before there was anything to lay out.
        if (wantCard && presence == Presence.BUBBLE) expand()
    }

    private fun surfaceChanged(taken: Boolean) {
        bubble.suppressed = taken
        if (taken && presence != Presence.BUBBLE) collapse()
        glance.sync()
    }

    private fun expand() {
        // Another app shows the assistant now; nothing opens here until it gives it back.
        if (AssistantSurface.active) return
        wantCard = true
        // While Android Use runs the card stays folded; the wish is honoured once 小博 is home.
        if (useMode != UseMode.OFF || pendingUse != null) return
        if (presence != Presence.BUBBLE || hud == null || sleeping) return
        presence = Presence.OPENING
        glance.sync()
        openRequestedAt = System.currentTimeMillis()
        flightStarted = false
        mascotInWindow = null
        cardDrawn = true
        bubble.setTouchable(false)
        mascot.react(MascotReaction.NOTICE)
        cardShown = false
        mascotDriving = false
        // The card lays out invisibly; once 小博's seat in it is known, the flight starts (see onMascotPlaced).
        cardComposed = true
        scheduleRender(force = true)
        root.postDelayed(openWatchdog, OPEN_WATCHDOG_MS)
    }

    /** A card that never laid out (nothing reported 小博's seat) leaves 小博 where it was instead of stuck. */
    private val openWatchdog = Runnable {
        if (presence == Presence.OPENING && !flightStarted) {
            presence = Presence.BUBBLE
            wantCard = false
            cardComposed = false
            setCardTouchable(false)
            // Folded away as after a close: an empty card-sized window would still count against taps beneath it.
            resizeWindow(1, 1)
            bubble.setTouchable(true)
            enterAndroidUse()
        }
    }

    /**
     * Flies to 小博's seat in the card after a beat, so the notice reads before it moves. Meanwhile the card has
     * drawn once, invisibly, so its first visible frame does not pay for text layout and shaders mid-landing.
     */
    private fun flyToCard() {
        val seat = seatOnScreen() ?: return
        if (presence != Presence.OPENING || flightStarted) return
        val wait = (if (quickOpen) 0L else NOTICE_MS) - (System.currentTimeMillis() - openRequestedAt)
        if (wait > 0) {
            root.removeCallbacks(flyToCardRunnable)
            root.postDelayed(flyToCardRunnable, wait)
            return
        }
        flightStarted = true
        val pace = if (quickOpen) WAKE_FLIGHT_PACE else 1f
        quickOpen = false
        flight.open(bubble.centreOnScreen(), seat, overlayFrame(), pace) {
            // The flight window shows 小博 over the bubble now; the bubble lets go and the flight takes over.
            bubble.hide()
            cardDrawn = false
            ui = ui.copy(cardDrawn = false)
            root.postOnAnimation {
                flight.driving = true
                flight.fly {
                    // The card unfolds around 小博 once it has landed in its seat.
                    cardDrawn = true
                    cardShown = true
                    ui = ui.copy(cardDrawn = true, cardShown = true)
                }
            }
        }
    }

    /** 小博's seat in the card, in screen pixels: where the card window is now plus where 小博 sits in it. */
    private fun seatOnScreen(): Offset? {
        val inWindow = mascotInWindow ?: return null
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        return Offset(location[0] + inWindow.x, location[1] + inWindow.y)
    }

    private val flyToCardRunnable = Runnable { flyToCard() }

    /** The card has unfolded around 小博: the card's own 小博, drawn underneath all along, takes over. */
    private fun cardOpened() {
        if (presence != Presence.OPENING) return
        presence = Presence.CARD
        // Touchable only once it can be seen: taps meant for the map never reach a card still unfolding.
        setCardTouchable(true)
        syncCardFocus()
        flight.close()
        root.postOnAnimation {
            mascotDriving = true
            ui = ui.copy(mascotDriving = true)
        }
        quietSince = 0L
        scheduleRender(force = true)
        if (!wantCard) collapse()
    }

    /** Folds the card into 小博, who then flies back to its spot. */
    private fun collapse() {
        wantCard = false
        held = false
        if (presence != Presence.CARD) return
        results.folded(hud?.phase)
        OverlayControl.readingUntil = 0L
        val seat = seatOnScreen() ?: return
        presence = Presence.CLOSING
        setCardTouchable(false)
        root.removeCallbacks(autoCollapse)
        hideSoftKeyboard()
        showInput = false
        syncCardFocus()
        landing = parkedSpot()
        val frame = overlayFrame()
        flight.open(seat, bubble.centreAt(landing.first, landing.second, frame.center), frame) {
            // 小博 is drawn over its seat now: the card lets go of it, then folds away beneath it.
            mascotDriving = false
            ui = ui.copy(mascotDriving = false, showInput = false)
            root.postOnAnimation {
                flight.driving = true
                cardShown = false
                ui = ui.copy(cardShown = false)
            }
        }
    }

    /** The card has folded into 小博, who flies back to its spot and becomes the bubble again. */
    private fun cardFolded() {
        if (presence != Presence.CLOSING) return
        cardComposed = false
        setCardTouchable(false)
        resizeWindow(1, 1)
        flight.fly {
            flight.driving = false
            bubble.showAt(landing.first, landing.second, touchable = true) {
                flight.close()
                bubble.driving = true
                presence = Presence.BUBBLE
                glance.sync()
                if (pendingUse != null) enterAndroidUse() else if (wantCard) expand()
            }
        }
    }

    /** Starts or stops the quiet countdown, shown on the close button; [delay] is null while the card should stay open. */
    private fun noteQuiet(delay: Long?) {
        if (delay == null || presence != Presence.CARD) {
            quietSince = 0L
            root.removeCallbacks(autoCollapse)
        } else if (quietSince == 0L) {
            quietSince = System.currentTimeMillis()
            root.removeCallbacks(autoCollapse)
            root.postDelayed(autoCollapse, delay)
        }
        ui = ui.copy(collapseAt = if (quietSince == 0L) 0L else quietSince + (delay ?: 0L), collapseMillis = delay ?: 0L)
    }

    private fun collapseIfQuiet() {
        val delay = hud?.quietCollapseMillis(ui) ?: return
        if (presence != Presence.CARD || quietSince == 0L) return
        val left = quietSince + delay - System.currentTimeMillis()
        if (left > 0) root.postDelayed(autoCollapse, left) else collapse()
    }

    /** Anything the user does on the card restarts the quiet countdown. */
    private fun interacted() {
        results.reading.touched()
        if (quietSince != 0L) quietSince = System.currentTimeMillis()
        scheduleRender(force = true)
    }

    /** Where the bubble rests: where the user last put it, or the upper right. */
    private fun parkedSpot(): Pair<Int, Int> {
        val prefs = getSharedPreferences(OVERLAY_PREFS, MODE_PRIVATE)
        val bounds = bubbleBounds(bubble.sizePx)
        val x = prefs.getInt(KEY_BUBBLE_X, bounds.right - designPx(BUBBLE_EDGE_DP))
        val y = prefs.getInt(KEY_BUBBLE_Y, bounds.top + designPx(BUBBLE_TOP_DP))
        return x.coerceIn(bounds.left, bounds.right) to y.coerceIn(bounds.top, bounds.bottom)
    }

    /** Focusable only while its input box is open, so the keyboard goes to the card then and to other apps otherwise. */
    private fun syncCardFocus() {
        val focusable = showInput && presence == Presence.CARD
        val flags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (flags == params.flags) return
        params.flags = flags
        runCatching { windowManager.updateViewLayout(root, params) }
    }

    private fun setCardTouchable(touchable: Boolean) {
        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { windowManager.updateViewLayout(root, params) }
    }

    /** A tap is the spoken ordinal reply, routed exactly like typed overlay input; one tap per offer. */
    private fun submitChoice(choice: DisplayChoice) {
        val current = hud ?: return
        if (current.choices.isEmpty() || current.choices == submittedChoices) return
        submittedChoices = current.choices
        chosenChoice = choice
        // Confirm on the frame after the tap, not when the follow-up run first reports.
        scheduleRender(force = true)
        route(choice.replyText)
    }

    /** What was tapped or confirmed belongs to the offer on screen; a new offer or an ended follow-up clears it. */
    private fun forgetAnswers(next: OverlayHud) {
        if (next.choices.isEmpty()) submittedChoices = null
        if (next.controls.isEmpty()) submittedControls = null
        val followUpOver = next.choices.isEmpty() && next.phase != OverlayPhase.LIVE
        val newOffer = next.choices.isNotEmpty() && next.choices != submittedChoices
        if (followUpOver || newOffer) chosenChoice = null
        if (expandedPicture != next.picture?.reference) expandedPicture = null
    }

    /** While a turn runs the card only widens: streamed text crossing a width threshold and back would reflow it. */
    private fun cardSizeFor(next: OverlayHud): OverlaySize {
        val wanted = next.overlaySize(showDetails)
        val stillRunning = next.phase == OverlayPhase.LIVE && hud?.phase == OverlayPhase.LIVE
        return if (stillRunning && wanted.ordinal < ui.size.ordinal) ui.size else wanted
    }

    /** A confirmed panel goes out like a tapped pick; one confirmation per offer. */
    private fun submitPanel(text: String) {
        val controls = hud?.controls?.takeIf { it.isNotEmpty() } ?: return
        if (controls == submittedControls) return
        submittedControls = controls
        scheduleRender(force = true)
        route(text)
    }

    private fun submitPrompt(text: String) {
        hideSoftKeyboard()
        showInput = false
        syncCardFocus()
        route(text)
    }

    private fun route(text: String) {
        if (clientView()) submitClientPrompt(text) else RuntimeSession.submitText(this, text)
        scheduleRender(force = true)
    }

    /**
     * An inbound A2A task takes the stage, so the device responds to any caller even outside client mode; it keeps it
     * (and a pick goes back into that conversation) until someone starts a run on the device itself.
     */
    private fun updateExternalFocus(state: UiState) {
        val startedLocally = state.isRunning && localWasRunning == false
        localWasRunning = state.isRunning
        if (startedLocally) OverlayFocus.localRun()
    }

    /** Null until the first state arrives, so a run already going when the overlay opens is not taken as new. */
    private var localWasRunning: Boolean? = null

    private fun hideSoftKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(root.windowToken, 0)
    }

    /** The origin follows orientation only; a dragged card is not pulled back on the next update. */
    private fun placeWindow(landscape: Boolean) {
        placedLandscape = landscape
        params.x = 0
        params.y = homeTopY(landscape)
        runCatching { windowManager.updateViewLayout(root, params) }
        if (presence == Presence.BUBBLE) bubble.clamp()
    }

    /**
     * The window hangs from its top edge ([Gravity.TOP], centred across), so y is the card's top on screen and x an
     * offset from the centre. Home leaves a little air under the top margin and the rest for the card to grow into,
     * down to the dock widgets, which draw over overlays.
     */
    private fun homeTopY(landscape: Boolean): Int {
        val usable = roomBelowFrameTop() - topMarginPx(landscape)
        return topMarginPx(landscape) + (usable * HOME_TOP_SHARE).roundToInt()
    }

    /** From the overlay frame's top (where y counts from) down to the dock widgets. */
    private fun roomBelowFrameTop(): Int = dockTopPx() - overlayFrame().top.roundToInt()

    private fun topMarginPx(landscape: Boolean): Int = designPx(if (landscape) TOP_MARGIN_LANDSCAPE_DP else TOP_MARGIN_PORTRAIT_DP)

    /**
     * A card about to animate to a larger size gets its whole window at once, so the animation runs inside a
     * still window; the transparent spare is trimmed in [fitWindowTo] when the motion ends.
     */
    private fun growWindowFor(card: IntSize) {
        if (!cardComposed) return
        // A newer size cancels a pending trim, so the window never shrinks just before it must grow again.
        root.removeCallbacks(fitPending)
        val need = windowSizeFor(card).let { IntSize(maxOf(it.width, wideWindowWidth()), it.height) }
        if (need.width <= params.width && need.height <= params.height) return
        // Grow a step past the need: streamed lines and late widgets then fit without another window resize, each of
        // which the system applies a frame apart from the content and so shows as a jump.
        val step = (GROW_STEP_DP * stageUnitPx()).roundToInt()
        resizeWindow(maxOf(params.width, need.width), maxOf(params.height, (need.height + step).even()))
    }

    /** Trims the window to the settled card once its content has been still for a moment and nothing streams. */
    private fun scheduleFit(card: IntSize) {
        pendingFit = card
        root.removeCallbacks(fitPending)
        root.postDelayed(fitPending, FIT_QUIET_MS)
    }

    private var pendingFit: IntSize? = null

    private val fitPending: Runnable = object : Runnable {
        override fun run() {
            val card = pendingFit ?: return
            if (presence != Presence.CARD) return
            // Streaming text keeps growing the card; trim once the run has ended.
            if (hud?.phase == OverlayPhase.LIVE) {
                root.postDelayed(this, FIT_QUIET_MS)
                return
            }
            fitWindowTo(card)
        }
    }

    /**
     * The settled card's height, so empty glass below it never swallows taps meant for the map. The width stays the
     * widest card's while the card is open: a narrower window would move the card sideways when it widens again.
     */
    private fun fitWindowTo(card: IntSize) {
        val need = windowSizeFor(card)
        resizeWindow(maxOf(need.width, wideWindowWidth()), need.height)
    }

    private fun wideWindowWidth(): Int {
        val wide = cardWidth(isLandscape(), OverlaySize.WIDE)
        return ((wide + STAGE_MARGIN * 2).value * stageUnitPx()).roundToInt().even()
    }

    /** Even sizes: the centred window then moves whole pixels, never a half-pixel shimmer when it changes. */
    private fun windowSizeFor(card: IntSize): IntSize {
        val margin = (STAGE_MARGIN.value * stageUnitPx()).roundToInt() * 2
        return IntSize((card.width + margin).even(), (card.height + margin).even())
    }

    private fun Int.even(): Int = this + (this and 1)

    /**
     * The top edge stays put and the card hangs from it, so a resize only moves the window's empty bottom: the
     * system applies a window change a frame apart from its content, which a centred card showed as a jump.
     */
    private fun resizeWindow(width: Int, height: Int) {
        if (width == params.width && height == params.height) return
        params.width = width
        params.height = height
        val slack = ((resources.displayMetrics.widthPixels - width) / 2).coerceAtLeast(0)
        params.x = params.x.coerceIn(-slack, slack)
        runCatching { windowManager.updateViewLayout(root, params) }
    }

    /** Pixels per stage dp: the design scale times the device zoom. */
    private fun stageUnitPx(): Float = resources.displayMetrics.density * stageScale()

    /** x from the centre, y the top edge: the window may go anywhere between the top edge and the dock, on screen. */
    private fun dragBy(dx: Float, dy: Float) {
        val metrics = resources.displayMetrics
        val slackX = ((metrics.widthPixels - params.width) / 2).coerceAtLeast(0)
        val minY = designPx(8)
        val maxY = (roomBelowFrameTop() - root.height).coerceAtLeast(minY)
        params.x = (params.x + dx.toInt()).coerceIn(-slackX, slackX)
        params.y = (params.y + dy.toInt()).coerceIn(minY, maxY)
        runCatching { windowManager.updateViewLayout(root, params) }
        if (quietSince != 0L) quietSince = System.currentTimeMillis()
        scheduleRender(force = false)
    }

    /** The card grows down from its top edge, as far as the device's dock widgets, which draw over overlays. */
    private fun availableStageHeight(): Dp {
        val metrics = resources.displayMetrics
        val px = (roomBelowFrameTop() - params.y).coerceAtLeast(designPx(240))
        return (px / stageUnitPx()).dp - STAGE_MARGIN * 2
    }

    /** The class's width in the stage's dp, capped to the screen, minus the window margin around the glass. */
    private fun cardWidth(landscape: Boolean, size: OverlaySize): Dp {
        val metrics = resources.displayMetrics
        val wanted = if (landscape) size.landscapeWidthDp.toFloat() else STAGE_WIDTH_PORTRAIT_DP.toFloat()
        val screenPx = if (landscape) metrics.widthPixels * size.screenShare else (metrics.widthPixels - designPx(SIDE_MARGIN_DP * 2)).toFloat()
        return (minOf(wanted, screenPx / stageUnitPx()).dp - STAGE_MARGIN * 2).coerceAtLeast(MIN_CARD_WIDTH)
    }

    /** The device's dock widgets scale with the design; elsewhere only the system bar needs room, in platform dp. */
    private fun dockReservePx(): Int = if (isCarDisplay()) designPx(CAR_DOCK_RESERVE_DP) else dp(PHONE_DOCK_RESERVE_DP)

    /** Car HUIs report ~160 dpi on a wide panel, so their smallest width is far above a tablet's. */
    private fun isCarDisplay(): Boolean = resources.configuration.smallestScreenWidthDp >= CAR_DISPLAY_MIN_WIDTH_DP

    /** The card's window starts folded away ([cardWindowParams]). */
    private fun overlayParams(): WindowManager.LayoutParams {
        val landscape = isLandscape()
        placedLandscape = landscape
        return cardWindowParams(this, homeTopY(landscape))
    }

    /** The device design at the bench zoom, scaled to this display ([UiScale]). */
    private fun stageScale(): Float = UiScale.overlayStageScale(this)

    private fun isLandscape(): Boolean {
        val metrics = resources.displayMetrics
        return metrics.widthPixels > metrics.heightPixels
    }

    private fun canDrawOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Bench pixels scaled to this display: the overlay's margins and positions keep the device's proportions. */
    private fun designPx(value: Int): Int = (value * UiScale.designUnitPx(this)).toInt()

    /** Android Use as the overlay shows it; called on the main thread by [BothyAndroidUsePresenter]. */
    private val androidUseOverlay = object : AndroidUseOverlay {
        override fun begin(onReady: () -> Unit) = beginAndroidUse(onReady)

        override fun point(x: Int, y: Int, press: Boolean, onArrived: () -> Unit) =
            if (useMode == UseMode.ON) useStage.pointAt(Offset(x.toFloat(), y.toFloat()), press, onArrived).also { noteUse() } else onArrived()

        override fun drag(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long, onStarted: () -> Unit) =
            if (useMode == UseMode.ON) {
                useStage.drag(Offset(fromX.toFloat(), fromY.toFloat()), Offset(toX.toFloat(), toY.toFloat()), durationMs, onStarted)
                noteUse()
            } else {
                onStarted()
            }

        override fun hide(onHidden: () -> Unit) = if (useMode == UseMode.ON) useStage.hide(onHidden) else onHidden()

        override fun reveal() {
            if (useMode == UseMode.ON) useStage.reveal()
        }
    }

    /** Folds the card first; once 小博 is the bubble again it becomes the pointer (see [enterAndroidUse]). */
    private fun beginAndroidUse(onReady: () -> Unit) {
        if (useMode == UseMode.ON) {
            noteUse()
            onReady()
            return
        }
        pendingUse = onReady
        wantCard = false
        when (presence) {
            Presence.BUBBLE -> if (useMode == UseMode.OFF) enterAndroidUse()
            Presence.CARD -> collapse()
            // A transition under way ends as the bubble (wantCard is false), which hands over.
            Presence.OPENING, Presence.CLOSING -> Unit
        }
    }

    /** The bubble hands 小博 to the full-screen stage, which lights the screen edges. */
    private fun enterAndroidUse() {
        val ready = pendingUse ?: return
        if (presence != Presence.BUBBLE || useMode != UseMode.OFF) return
        pendingUse = null
        useMode = UseMode.ON
        useStage.begin(bubble.centreOnScreen()) {
            bubble.driving = false
            useStage.driving = true
            bubble.hide()
            ready()
        }
        noteUse()
    }

    /** The glow fades and 小博 flies home; the bubble takes over again, and a pending reply opens the card. */
    private fun exitAndroidUse() {
        if (useMode != UseMode.ON) return
        useMode = UseMode.LEAVING
        root.removeCallbacks(useIdle)
        val home = bubble.centreAt(bubble.x, bubble.y, overlayFrame().center)
        useStage.end(home) {
            bubble.showAt(bubble.x, bubble.y, touchable = true) {
                useStage.close()
                bubble.driving = true
                useMode = UseMode.OFF
                if (pendingUse != null) enterAndroidUse() else if (wantCard) expand() else scheduleRender(force = true)
            }
        }
    }

    /** A run that stops acting without ending (or a lost end signal) still hands 小博 back. */
    private fun noteUse() {
        root.removeCallbacks(useIdle)
        root.postDelayed(useIdle, ANDROID_USE_IDLE_MS)
    }

    private enum class UseMode { OFF, ON, LEAVING }

    /** The bubble alone, opening into the card, the card, or folding back into the bubble. */
    private enum class Presence { BUBBLE, OPENING, CARD, CLOSING }

    private enum class StartRequest { OPEN, FOLDED }
}

// Tuned for 2560×1440 @160dpi car HUI; the card leaves the map and the climate/dock bar visible.
// Landscape widths come from [OverlaySize] in the stage's zoomed dp (756 / 1026 / 1458 px on the bench);
// even the wide card leaves the right ~40% of the map visible.
/** Without any Android Use call for this long, 小博 leaves the pointer role. */
private const val ANDROID_USE_IDLE_MS = 20_000L
private const val STAGE_WIDTH_PORTRAIT_DP = 420
private const val CAR_DISPLAY_MIN_WIDTH_DP = 1000
private const val TOP_MARGIN_LANDSCAPE_DP = 36
private const val TOP_MARGIN_PORTRAIT_DP = 56
private const val SIDE_MARGIN_DP = 12
// Bench HUI: the widget row starts about 340 px above the bottom edge and draws above overlay windows.
private const val CAR_DOCK_RESERVE_DP = 350
private const val PHONE_DOCK_RESERVE_DP = 72
private val MIN_CARD_WIDTH = 280.dp
private const val RENDER_DEBOUNCE_MS = 60L
/** Where the card's top rests, as a share of the height between the top margin and the dock. */
private const val HOME_TOP_SHARE = 0.1f
/** How long the card's size must stay put before its window is trimmed to it. */
private const val FIT_QUIET_MS = 700L
/** Spare height a growing window takes, in stage dp: a few streamed lines or a widget's late first frame. */
private const val GROW_STEP_DP = 96
private const val TICK_MS = 1000L
/** How long 小博 shows it noticed something before it flies to the card. */
private const val NOTICE_MS = 280L
private const val WAKE_FLIGHT_PACE = 0.5f
private const val SLEEP_MS = 650L
private const val PREVIEW_MS = 6_000L
private const val OPEN_WATCHDOG_MS = 2_000L
private const val BUBBLE_EDGE_DP = 32
private const val BUBBLE_TOP_DP = 120
private const val OVERLAY_PREFS = "debug_overlay"
private const val KEY_BUBBLE_X = "bubble_x"
private const val KEY_BUBBLE_Y = "bubble_y"
