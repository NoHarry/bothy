package cc.noharry.bothy.app.overlay
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import cc.noharry.bothy.app.androiduse.AndroidUseOverlay
import cc.noharry.bothy.app.console.ConsoleVisibility
import cc.noharry.bothy.app.plans.reading
import cc.noharry.bothy.app.voice.WakeSource

/** What an A2A caller asks of the device's overlay through `metadata.carOverlay`. */
internal enum class OverlayCommand(val done: String) {
    SHOW("悬浮窗已弹出"),
    HIDE("悬浮窗已收起"),
}

/** `carOverlay=show|hide`; anything else is no command. */
internal fun overlayCommand(metadata: Map<String, String>): OverlayCommand? =
    when (metadata[CAR_OVERLAY_METADATA]?.trim()?.lowercase()) {
        "show" -> OverlayCommand.SHOW
        "hide" -> OverlayCommand.HIDE
        else -> null
    }

/** `carOverlayOnly=true`: the message only controls the overlay; the agent does not run and the text is ignored. */
internal fun isOverlayControlOnly(metadata: Map<String, String>): Boolean =
    metadata[CAR_OVERLAY_ONLY_METADATA]?.trim()?.lowercase() == "true"

/**
 * Opens the overlay's card or folds it back into 小博 for an A2A caller, and opens it when any A2A task arrives, so
 * the device responds to A2A without its console ever being opened. While the full-screen console is in front the
 * overlay stays away and the request waits ([ConsoleVisibility]).
 */
internal object OverlayControl {
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
    @Volatile var waker: ((WakeSource) -> Unit)? = null

    /** Android Use on the running overlay (edge glow, 小博 as the pointer), set by [OverlayService]; main thread. */
    @Volatile var androidUse: AndroidUseOverlay? = null

    private val main = Handler(Looper.getMainLooper())

    /**
     * Until when the user is reading the card's result (epoch ms), set by the overlay as it renders: a delegation's
     * report waits instead of replacing the page (owner 2026-10-12: 「没看完页面就由于有别的话进来就关了」). 0 when not.
     */
    @Volatile var readingUntil: Long = 0L

    /** Whether a report should wait for the user to finish reading. */
    fun reading(now: Long = System.currentTimeMillis()): Boolean = now < readingUntil

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
     * could not be.
     */
    fun ensureRunning(context: Context): String? = if (presenter != null) null else start(context, open = false)

    /**
     * Starts the overlay, with its card open when [open]. A plain start is refused while the app is idle in the
     * background (no bound screen-control service, no activity); Android 11 still allows a foreground-service start,
     * so the overlay then runs as one, with a notification.
     */
    fun start(context: Context, open: Boolean): String? {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(app)) return "没有授予悬浮窗权限"
        val intent = Intent(app, OverlayService::class.java).apply { if (open) action = ACTION_OPEN_OVERLAY_CARD }
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
internal const val CAR_OVERLAY_METADATA = "carOverlay"
internal const val CAR_OVERLAY_ONLY_METADATA = "carOverlayOnly"
