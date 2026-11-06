package cc.noharry.bothy.app.overlay
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.OverlayCommand
import cc.noharry.agent.appserver.protocol.PlanNoticeEntry
import cc.noharry.agent.appserver.protocol.PresentResult
import cc.noharry.bothy.app.console.ConsoleVisibility
import cc.noharry.bothy.app.console.FrontEnd
import cc.noharry.bothy.app.phoneassist.PhoneAssistant
import cc.noharry.bothy.app.phoneassist.PhoneAssistantService
import cc.noharry.bothy.protocol.WakeOrigin

/**
 * Opens the assistant's card or folds it back for an A2A caller, and opens it when any A2A task arrives, so
 * the device responds to A2A without its console ever being opened. While the full-screen console is in front the
 * overlay stays away and the request waits ([ConsoleVisibility]).
 */
internal object OverlayControl {
    /** The service whose hooks these are: [OverlayService], or the phone's [PhoneAssistantService]. */
    @Volatile var owner: Any? = null

    /** The running overlay's handler, set by [OverlayService] while it is up; called on the main thread. */
    @Volatile var presenter: ((OverlayCommand) -> Unit)? = null

    /** Closes the running overlay (the console coming to the front); set by [OverlayService]; main thread. */
    @Volatile var closer: (() -> Unit)? = null

    /**
     * Debug review of 小博's motions: plays the named act (or reaction) for a few seconds on the running overlay.
     * Returns false when no overlay is up or the name is unknown. Main thread only.
     */
    @Volatile var mascotPreview: ((String) -> Boolean)? = null

    /** Debug: wakes the running overlay as 你好，小博 would (adb `--ez wake true`); null when no overlay listens. Main thread. */
    @Volatile var waker: ((WakeOrigin) -> Unit)? = null

    /** Android Use on the running overlay (edge glow, 小博 as the pointer), set by [OverlayService]; main thread. */
    @Volatile var androidUse: AndroidUseOverlay? = null

    private val main = Handler(Looper.getMainLooper())

    /**
     * Until when the user is reading the card's result (epoch ms), set by the overlay as it renders: a delegation's
     * report waits instead of replacing the page (owner 2026-10-12: 「没看完页面就由于有别的话进来就关了」). 0 when not.
     */
    @Volatile var readingUntil: Long = 0L
        set(value) {
            field = value
            HostConnection.report { copy(readingUntilEpochMillis = value) }
        }

    /** Lets go of the hooks [service] set, unless the other assistant's service has set its own since. Main thread. */
    fun release(service: Any) {
        if (owner !== service) return
        owner = null
        presenter = null
        closer = null
        mascotPreview = null
        androidUse = null
    }

    /** Hands a wake to the running overlay; false when none is up to take it. */
    fun wake(origin: WakeOrigin): Boolean = waker?.invoke(origin) != null

    fun previewMascot(name: String): Boolean = mascotPreview?.invoke(name) == true

    fun planDue(context: Context, entry: PlanNoticeEntry) = PlanNoticePopup.show(context, entry)

    fun planRan(planId: String, outcome: Finding) = PlanNoticePopup.ran(planId, outcome)

    /** The user asked for the waiting report (the card's 查看): stop holding it. */
    fun releaseReports() {
        readingUntil = 0L
    }

    /** Returns null when done (or held while the console is in front), or why it could not be. Safe from any thread. */
    fun apply(context: Context, command: OverlayCommand): String? {
        if (command == OverlayCommand.SHOW && ConsoleVisibility.inFront) {
            ConsoleVisibility.holdOpen()
            return null
        }
        // A running overlay is told in-process: a service start from the background may be refused.
        presenter?.let { running ->
            main.post { running(command) }
            return null
        }
        // Not running: there is no card to fold.
        return if (command == OverlayCommand.HIDE) null else start(context, open = true)
    }

    /**
     * Brings 小博 up (card closed) when it is not running, as 语音唤醒 does. Null when it runs or was started, else why it
     * could not be. A phone has no resting 小博 ([PhoneAssistant]): nothing comes up there, and the wake word, which the
     * overlay would have started, listens on its own. A head unit's own surface ([forSurface]) still needs the overlay
     * running, hidden, for its reports. On a phone the server is told so ([PresentResult.noResting]), and listens itself.
     */
    fun ensureRunning(context: Context, forSurface: Boolean = false): PresentResult {
        if (presenter != null) return PresentResult()
        if (!forSurface && FrontEnd.phone(context)) return PresentResult(noResting = true)
        return PresentResult(start(context, open = false))
    }

    /**
     * Starts the assistant over other apps, with its card open when [open]: the overlay, or on a phone its own
     * ([PhoneAssistant]), which answers through the same hooks. A plain start is refused while the app is idle in the
     * background (no bound screen-control service, no activity); Android 11 still allows a foreground-service start,
     * so the overlay then runs as one, with a notification.
     */
    fun start(context: Context, open: Boolean): String? {
        val app = context.applicationContext
        // A phone has an assistant of its own over other apps; tablets and head units have the overlay.
        val intent = if (FrontEnd.phone(app)) {
            PhoneAssistant.intent(app, open)
        } else {
            Intent(app, OverlayService::class.java).apply { if (open) action = ACTION_OPEN_OVERLAY_CARD }
        }
        return launch(app, intent)
    }

    /**
     * Starts the assistant's service ([intent]); from the background, where a plain start is refused, as a
     * foreground service that promotes itself ([EXTRA_FOREGROUND]). The other one stops first (a foldable is a phone
     * folded and not open): the two share the connection and these hooks. Null when started, else why not.
     */
    fun launch(app: Context, intent: Intent): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(app)) return "没有授予悬浮窗权限"
        val other = if (intent.component?.className == OverlayService::class.java.name) PhoneAssistantService::class.java else OverlayService::class.java
        app.stopService(Intent(app, other))
        return try {
            app.startService(intent)
            null
        } catch (refused: IllegalStateException) {
            Log.w(TAG, "Overlay start refused in background; starting it in the foreground", refused)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent.putExtra(EXTRA_FOREGROUND, true))
                null
            } catch (error: RuntimeException) {
                Log.w(TAG, "Overlay foreground start refused", error)
                "系统不允许应用在后台弹出悬浮窗"
            }
        }
    }

    private const val TAG = "DebugOverlayControl"
}

/** Starts the overlay with its card open, even when there is nothing to show yet. */
internal const val ACTION_OPEN_OVERLAY_CARD = "cc.noharry.bothy.OPEN_OVERLAY_CARD"
/** The overlay was started as a foreground service and must promote itself at once. */
internal const val EXTRA_FOREGROUND = "cc.noharry.bothy.OVERLAY_FOREGROUND"
