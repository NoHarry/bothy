package cc.noharry.agent.appserver.androiduse

import cc.noharry.agent.androiduse.AndroidUseGesturePreview
import cc.noharry.agent.androiduse.AndroidUsePresenter
import cc.noharry.agent.appserver.server.Presenter

/**
 * Shows Android Use on the assistant on screen ([Presenter], over the protocol): the card folds, the screen edges glow,
 * and the assistant points at each tap and drags along each swipe. Without an assistant up every call returns at once;
 * the provider bounds each call anyway.
 */
internal class AppAndroidUsePresenter : AndroidUsePresenter {
    override suspend fun onRunStarted(runId: String) = Presenter.androidUseBegin()

    override suspend fun beforeCapture() = Presenter.androidUseHide()

    override suspend fun afterCapture() = Presenter.androidUseReveal()

    override suspend fun beforeGesture(preview: AndroidUseGesturePreview) {
        // The overlay covers the main display only.
        if (preview.displayId != MAIN_DISPLAY_ID) return
        when (preview.kind) {
            AndroidUseGesturePreview.Kind.SWIPE -> Presenter.androidUseDrag(preview.x, preview.y, preview.toX, preview.toY, preview.durationMs)
            AndroidUseGesturePreview.Kind.TAP, AndroidUseGesturePreview.Kind.LONG_PRESS -> Presenter.androidUsePoint(preview.x, preview.y, press = true)
            AndroidUseGesturePreview.Kind.TYPE -> Presenter.androidUsePoint(preview.x, preview.y, press = false)
        }
    }
}

private const val MAIN_DISPLAY_ID = 0
