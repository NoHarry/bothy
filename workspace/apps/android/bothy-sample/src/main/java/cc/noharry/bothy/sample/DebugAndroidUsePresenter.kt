package cc.noharry.bothy.sample

import android.os.Handler
import android.os.Looper
import cc.noharry.bothy.androiduse.AndroidUseGesturePreview
import cc.noharry.bothy.androiduse.AndroidUsePresenter
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** The overlay side of Android Use; every call and callback is on the main thread. */
internal interface DebugAndroidUseOverlay {
    /** Folds the card, lights the screen edges and makes 小博 the pointer. */
    fun begin(onReady: () -> Unit)

    /** Flies 小博's fingertip to ([x], [y]) in screen pixels; [press] ripples the target. */
    fun point(x: Int, y: Int, press: Boolean, onArrived: () -> Unit)

    /** Puts the fingertip on the start, reports [onStarted], then drags along with the gesture. */
    fun drag(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long, onStarted: () -> Unit)

    /** Hides glow and 小博 for a screenshot. */
    fun hide(onHidden: () -> Unit)

    fun reveal()
}

/**
 * Shows Android Use on the overlay: the card folds, the screen edges glow, and 小博 points at each tap and drags along
 * each swipe. Without a running overlay every call returns at once; the provider bounds each call anyway.
 */
internal class DebugAndroidUsePresenter : AndroidUsePresenter {
    private val main = Handler(Looper.getMainLooper())

    override suspend fun onRunStarted(runId: String) = await { done -> begin(done) }

    override suspend fun beforeCapture() = await { done -> hide(done) }

    override suspend fun afterCapture() {
        main.post { DebugOverlayControl.androidUse?.reveal() }
    }

    override suspend fun beforeGesture(preview: AndroidUseGesturePreview) {
        // The overlay covers the main display only.
        if (preview.displayId != MAIN_DISPLAY_ID) return
        await { done ->
            when (preview.kind) {
                AndroidUseGesturePreview.Kind.SWIPE -> drag(preview.x, preview.y, preview.toX, preview.toY, preview.durationMs, done)
                AndroidUseGesturePreview.Kind.TAP, AndroidUseGesturePreview.Kind.LONG_PRESS -> point(preview.x, preview.y, press = true, done)
                AndroidUseGesturePreview.Kind.TYPE -> point(preview.x, preview.y, press = false, done)
            }
        }
    }

    private suspend fun await(call: DebugAndroidUseOverlay.(done: () -> Unit) -> Unit) =
        suspendCancellableCoroutine { continuation ->
            main.post {
                val overlay = DebugOverlayControl.androidUse
                if (overlay == null) {
                    if (continuation.isActive) continuation.resume(Unit)
                } else {
                    overlay.call { if (continuation.isActive) continuation.resume(Unit) }
                }
            }
        }
}

private const val MAIN_DISPLAY_ID = 0
