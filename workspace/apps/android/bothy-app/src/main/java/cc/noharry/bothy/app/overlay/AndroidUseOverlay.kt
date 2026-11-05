package cc.noharry.bothy.app.overlay

/** The overlay side of Android Use, as the server asks for it ([HostConnection]); every call and callback is on the main thread. */
internal interface AndroidUseOverlay {
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
