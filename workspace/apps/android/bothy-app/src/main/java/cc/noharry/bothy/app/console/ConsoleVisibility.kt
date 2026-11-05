package cc.noharry.bothy.app.console
import android.content.Context
import android.os.Handler
import android.os.Looper
import cc.noharry.agent.appserver.protocol.FeatureRequests
import cc.noharry.agent.appserver.protocol.FlagParams
import cc.noharry.bothy.app.frontend.AppClient
import cc.noharry.bothy.app.overlay.HostConnection
import cc.noharry.bothy.app.overlay.OverlayControl
import cc.noharry.bothy.protocol.voice

/**
 * The overlay steps aside for the full-screen console: while the console is in front the overlay is closed and an
 * A2A request to show it is held; once the console leaves, the overlay comes back if it was up, folded (owner
 * 2026-10-25: 「从sample退出到桌面的时候不要弹出卡片」), or with its card open if a request came meanwhile. Safe from any
 * thread.
 */
internal object ConsoleVisibility {
    @Volatile var inFront: Boolean = false
        private set
    @Volatile private var restoreOverlay = false
    @Volatile private var openCard = false
    private val main = Handler(Looper.getMainLooper())

    /** The console became visible ([visible]) or left; called from its onStart/onStop. */
    fun set(context: Context, visible: Boolean) {
        if (inFront == visible) return
        inFront = visible
        HostConnection.report { copy(inFront = visible) }
        val app = context.applicationContext
        if (visible) {
            // In front: the one moment Android lets the wake word's microphone service start.
            AppClient.send(app, FeatureRequests.APP_IN_FRONT, FlagParams(true))
            OverlayControl.closer?.let { close ->
                restoreOverlay = true
                main.post { close() }
            }
        } else {
            // Gone from the front: the next run draws no page, which only the app's own screens show.
            AppClient.send(app, FeatureRequests.APP_IN_FRONT, FlagParams(false))
            // With 语音唤醒 on 小博 comes back too: the overlay may have closed itself on the way in (its card's 打开页面).
            // A phone has no resting 小博: only a card asked for meanwhile comes up there.
            val open = openCard
            val phone = FrontEnd.phone(app)
            val resting = !phone && (restoreOverlay || AppClient.state(app).voice.wakeWordEnabled)
            restoreOverlay = false
            openCard = false
            when {
                open || resting -> OverlayControl.start(app, open)
                // A task left going on a phone shows on its assistant's capsule at the top, the card folded.
                phone && AppClient.state(app).isRunning -> OverlayControl.start(app, open = false)
            }
        }
    }

    /** An A2A request to show the overlay while the console is in front: shown when the console leaves. */
    fun holdOpen() {
        openCard = true
    }
}
