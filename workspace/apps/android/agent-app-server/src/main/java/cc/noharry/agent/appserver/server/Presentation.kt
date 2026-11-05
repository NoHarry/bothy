package cc.noharry.agent.appserver.server

import cc.noharry.agent.appserver.InternalAppServerApi
import cc.noharry.agent.appserver.protocol.DragParams
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.EnsureRunningParams
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.MascotParams
import cc.noharry.agent.appserver.protocol.OverlayCommand
import cc.noharry.agent.appserver.protocol.PlanNoticeEntry
import cc.noharry.agent.appserver.protocol.PlanRanParams
import cc.noharry.agent.appserver.protocol.PointParams
import cc.noharry.agent.appserver.protocol.PresentCardParams
import cc.noharry.agent.appserver.protocol.PresentResult
import cc.noharry.agent.appserver.protocol.PresentStartParams
import cc.noharry.agent.appserver.protocol.PresentWakeParams
import cc.noharry.agent.appserver.protocol.PresentationNotifications
import cc.noharry.agent.appserver.protocol.PresentationRequests
import cc.noharry.agent.appserver.protocol.PresentationState
import cc.noharry.agent.appserver.protocol.WakeOrigin
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.protocol.jsonrpc.NotificationMethod
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The client presenting the assistant on this device (the app's overlay), as [hub] reaches it over the protocol: how
 * it last said it stands, and requests and notifications for it. Without a presenting client every ask answers as
 * "not presented" at once. [changed] hears the assistant coming up or going.
 */
class PresentationLink(private val hub: ServerHub) {
    @Volatile var state = PresentationState()
        private set

    @Volatile var changed: (ready: Boolean) -> Unit = {}

    /** The presenting client said how the assistant stands now. Main thread. */
    fun reported(next: PresentationState) {
        val was = state
        state = next
        if (was.ready != next.ready) changed(next.ready)
    }

    /** The answer to [method], or null when no client presents or it would not answer. */
    suspend fun <P, R> ask(method: RequestMethod<P, R>, params: P): R? {
        val presenter = hub.presenter() ?: return null
        return try {
            presenter.request(method, params)
        } catch (refused: JsonRpcException) {
            null
        }
    }

    internal fun <P> tell(method: NotificationMethod<P>, params: P) {
        hub.presenter()?.notify(method, params)
    }

    /** What a presenting ask came to: null when done, else why not; a screen that keeps no assistant up wakes the server. */
    suspend fun present(ask: suspend () -> PresentResult?): String? {
        val answer = ask() ?: return NOT_PRESENTED
        if (answer.noResting) hub.ensureStarted()
        return answer.refusal
    }
}

/**
 * The assistant on screen as the server's code asks for it: the presenting client of the running server
 * ([AppServer]), or nothing before it has one. The callbacks come on the main thread.
 */
object Presenter {
    @Volatile internal var link: PresentationLink? = null

    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Whether a report should wait for the person to finish reading. */
    fun reading(): Boolean = System.currentTimeMillis() < (link?.state?.readingUntilEpochMillis ?: 0L)

    /** Whether a full-screen front end is in front, which takes a wake itself. */
    fun inFront(): Boolean = link?.state?.inFront == true

    /** Whether the assistant is up and takes wakes. */
    fun ready(): Boolean = link?.state?.ready == true

    /** Brings the assistant up (card closed) when it should rest on this screen; [done] gets why not, null when up. */
    fun ensureRunning(forSurface: Boolean = false, done: (String?) -> Unit = {}) {
        main.launch { done(present(PresentationRequests.ENSURE_RUNNING, EnsureRunningParams(forSurface))) }
    }

    /** Starts the assistant, with its card [open]; [done] gets why not, null when started. */
    fun start(open: Boolean, done: (String?) -> Unit = {}) {
        main.launch { done(present(PresentationRequests.START, PresentStartParams(open))) }
    }

    /** Opens or folds the card: null when done (or held for later), else why it could not be. */
    suspend fun card(command: OverlayCommand): String? = present(PresentationRequests.CARD, PresentCardParams(command))

    /** Hands a wake to the assistant on screen; [taken] is false when none is up to take it. */
    fun wake(origin: WakeOrigin, taken: (Boolean) -> Unit) {
        main.launch { taken(link?.ask(PresentationRequests.WAKE, PresentWakeParams(origin))?.taken == true) }
    }

    /** Debug: plays the named motion on the assistant on screen; [played] is false when none is up or the name is unknown. */
    fun previewMascot(name: String, played: (Boolean) -> Unit) {
        main.launch { played(link?.ask(PresentationRequests.PREVIEW_MASCOT, MascotParams(name))?.taken == true) }
    }

    fun planDue(entry: PlanNoticeEntry) {
        link?.tell(PresentationNotifications.PLAN_DUE, entry)
    }

    internal fun planRan(planId: String, outcome: Finding) {
        link?.tell(PresentationNotifications.PLAN_RAN, PlanRanParams(planId, outcome))
    }

    /** Android Use on the assistant: each returns once shown, or at once when no assistant is up. */
    internal suspend fun androidUseBegin() = androidUse(PresentationRequests.ANDROID_USE_BEGIN, Empty)

    internal suspend fun androidUsePoint(x: Int, y: Int, press: Boolean) = androidUse(PresentationRequests.ANDROID_USE_POINT, PointParams(x, y, press))

    internal suspend fun androidUseDrag(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) =
        androidUse(PresentationRequests.ANDROID_USE_DRAG, DragParams(fromX, fromY, toX, toY, durationMs))

    internal suspend fun androidUseHide() = androidUse(PresentationRequests.ANDROID_USE_HIDE, Empty)

    internal fun androidUseReveal() {
        link?.tell(PresentationNotifications.ANDROID_USE_REVEAL, Empty)
    }

    private suspend fun <P> androidUse(method: RequestMethod<P, Empty>, params: P) {
        if (ready()) link?.ask(method, params)
    }

    private suspend fun <P> present(method: RequestMethod<P, PresentResult>, params: P): String? =
        link?.present { link?.ask(method, params) } ?: NOT_PRESENTED
}

private const val NOT_PRESENTED = "no assistant on screen"

/** What an A2A caller is told once [this] was applied to the device's overlay. */
val OverlayCommand.done: String
    get() = when (this) {
        OverlayCommand.SHOW -> "悬浮窗已弹出"
        OverlayCommand.HIDE -> "悬浮窗已收起"
    }

/** `carOverlay=show|hide`; anything else is no command. */
@InternalAppServerApi
fun overlayCommand(metadata: Map<String, String>): OverlayCommand? =
    when (metadata[CAR_OVERLAY_METADATA]?.trim()?.lowercase()) {
        "show" -> OverlayCommand.SHOW
        "hide" -> OverlayCommand.HIDE
        else -> null
    }

/** `carOverlayOnly=true`: the message only controls the overlay; the agent does not run and the text is ignored. */
@InternalAppServerApi
fun isOverlayControlOnly(metadata: Map<String, String>): Boolean =
    metadata[CAR_OVERLAY_ONLY_METADATA]?.trim()?.lowercase() == "true"

@InternalAppServerApi
const val CAR_OVERLAY_METADATA = "carOverlay"
@InternalAppServerApi
const val CAR_OVERLAY_ONLY_METADATA = "carOverlayOnly"
