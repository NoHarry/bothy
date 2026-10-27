package cc.noharry.bothy.app.overlay
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager

/** The window type of 小博's overlay windows: the card, the bubble, the flight, the task capsule and the stage. */
internal fun overlayWindowType(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

/** The card's window, folded away: 1×1 and not touchable until the card opens, its top edge at [topY]. */
internal fun cardWindowParams(context: Context, topY: Int): WindowManager.LayoutParams =
    // An explicit size, never WRAP_CONTENT: wrapping would relayout the window on every animation frame.
    // The first reported card target grows it to fit.
    WindowManager.LayoutParams(
        1,
        1,
        overlayWindowType(),
        // Never focusable unless its input box is open (see syncCardFocus): a focusable overlay, even 1×1 and
        // untouchable, takes input focus and the app below can no longer raise its keyboard.
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        // Hung from its top edge: a size change only moves the empty bottom, so the card itself never jumps.
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        x = 0
        y = topY
        // Untouchable at full size while it unfolds or folds, under the flight window (see trustOverlay).
        val trusted = trustOverlay(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Log.i(OVERLAY_TAG, "overlay windows trusted=$trusted")
    }

/** Whether the platform signature grants INTERNAL_SYSTEM_WINDOW, which TYPE_DRAG and a trusted overlay need. */
internal fun Context.grantsSystemWindows(): Boolean =
    checkSelfPermission(INTERNAL_SYSTEM_WINDOW) == PackageManager.PERMISSION_GRANTED

/**
 * Marks one of 小博's windows trusted where the platform allows it (API 31+ with INTERNAL_SYSTEM_WINDOW); true when it
 * is. From API 31 a touch for another app is dropped when the untrusted windows of one app over it add up to more than
 * 0.8 opacity, and the system turns each untouchable overlay down to 0.8 itself. The card, still untouchable while it
 * unfolds or folds, under 小博's flight window made 0.96, and the device's settings page took no taps each time the card
 * opened or closed (seen on a test device, 2026-10-20). A trusted window does not count, and keeps its own alpha.
 */
internal fun WindowManager.LayoutParams.trustOverlay(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context.grantsSystemWindows() && trusted()

/** WindowManager.LayoutParams.setTrustedOverlay() (API 31+), hidden in the public SDK; needs INTERNAL_SYSTEM_WINDOW. */
private fun WindowManager.LayoutParams.trusted(): Boolean =
    runCatching { WindowManager.LayoutParams::class.java.getMethod("setTrustedOverlay").invoke(this) }.isSuccess

/** android.permission.INTERNAL_SYSTEM_WINDOW, hidden in the public SDK; granted by the platform signature. */
private const val INTERNAL_SYSTEM_WINDOW = "android.permission.INTERNAL_SYSTEM_WINDOW"
private const val OVERLAY_TAG = "DebugOverlay"
