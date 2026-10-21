package cc.noharry.bothy.sample

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Which stage the overlay shows outside client mode: the A2A view once an inbound task arrives, the device's own
 * session again once a run starts on the device. Process-wide, so an overlay that starts later (after the console
 * closes, or on the task's own popup) opens on the right one.
 */
internal object DebugOverlayFocus {
    @Volatile var external: Boolean = false
        private set

    fun inbound() {
        external = true
    }

    fun localRun() {
        external = false
    }
}

/**
 * The overlay steps aside for the full-screen console: while the console is in front the overlay is closed and an
 * A2A request to show it is held; once the console leaves, the overlay comes back if it was up, with its card open
 * if a request came meanwhile. Safe from any thread.
 */
internal object DebugConsoleVisibility {
    @Volatile var inFront: Boolean = false
        private set
    @Volatile private var restoreOverlay = false
    @Volatile private var openCard = false
    private val main = Handler(Looper.getMainLooper())

    /** The console became visible ([visible]) or left; called from its onStart/onStop. */
    fun set(context: Context, visible: Boolean) {
        if (inFront == visible) return
        inFront = visible
        val app = context.applicationContext
        if (visible) {
            // In front: the one moment Android lets the wake word's microphone service start.
            DebugVoiceForegroundService.sync(app)
            DebugOverlayControl.closer?.let { close ->
                restoreOverlay = true
                main.post { close() }
            }
        } else if (restoreOverlay || openCard || DebugWakeWord.enabled.value) {
            // With 语音唤醒 on 小博 comes back too: the overlay may have closed itself on the way in (its card's 打开页面).
            val open = openCard
            restoreOverlay = false
            openCard = false
            DebugOverlayControl.start(app, open)
        }
    }

    /** An A2A request to show the overlay while the console is in front: shown when the console leaves. */
    fun holdOpen() {
        openCard = true
    }
}
