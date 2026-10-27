package cc.noharry.bothy.app.console.ui
import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * One scale for the whole Sample UI (owner, 2026-10-11): every display shows the device design scaled to its size, so a
 * tablet looks like the device bench, only smaller. The design is the bench HUI, 2560×1440 at 160 dpi, where a dp is a
 * pixel. Long and short sides are matched separately, so turning the display does not change the scale. The console
 * draws at the design size and the overlay at [CAR_OVERLAY_ZOOM] of it. A design pixel never drops below
 * [MIN_NATIVE_SHARE] of the platform dp. A phone (owner 2026-10-23: 手机布局要支持) is not a small bench: the design would
 * be unreadable there, so it keeps the platform dp, the console takes its compact layouts, and the overlay draws at
 * [PHONE_OVERLAY_ZOOM].
 */
internal object UiScale {
    /** Screen pixels per design pixel on a [longPx]×[shortPx] display of [nativeDensity]: 1 on the bench. */
    fun designUnitPx(longPx: Int, shortPx: Int, nativeDensity: Float): Float =
        if (phone(shortPx, nativeDensity)) nativeDensity
        else maxOf(minOf(longPx / DESIGN_LONG_PX, shortPx / DESIGN_SHORT_PX), nativeDensity * MIN_NATIVE_SHARE)

    /** The overlay stage's zoom over the platform density on a [longPx]×[shortPx] display: 1.35 on the bench. */
    fun overlayStageScale(longPx: Int, shortPx: Int, nativeDensity: Float): Float =
        if (phone(shortPx, nativeDensity)) PHONE_OVERLAY_ZOOM
        else designUnitPx(longPx, shortPx, nativeDensity) * CAR_OVERLAY_ZOOM / nativeDensity

    /** A display whose short side is under a tablet's, in platform dp. */
    fun phone(shortPx: Int, nativeDensity: Float): Boolean = shortPx / nativeDensity < PHONE_SHORT_SIDE_DP

    /** Whether this device is a phone, for the screens that turn it sideways. */
    fun phone(context: Context): Boolean = phone(displaySidesPx(context).second, context.resources.displayMetrics.density)

    /** Screen pixels per design pixel on this display. */
    fun designUnitPx(context: Context): Float {
        val (longPx, shortPx) = displaySidesPx(context)
        return designUnitPx(longPx, shortPx, context.resources.displayMetrics.density)
    }

    /** The overlay stage's zoom over the platform density on this display. */
    fun overlayStageScale(context: Context): Float {
        val (longPx, shortPx) = displaySidesPx(context)
        return overlayStageScale(longPx, shortPx, context.resources.displayMetrics.density)
    }

    /** The whole display, bars included, as (long side, short side) in pixels. */
    private fun displaySidesPx(context: Context): Pair<Int, Int> {
        val windowManager = context.getSystemService(WindowManager::class.java)
        val (width, height) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            val real = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(real)
            real.widthPixels to real.heightPixels
        }
        return maxOf(width, height) to minOf(width, height)
    }
}

/** Gives [content] the design density, keeping the system font scale; recomputed when the configuration changes. */
@Composable
internal fun DesignDensity(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val base = LocalDensity.current
    val density = remember(configuration, base) { Density(UiScale.designUnitPx(context), base.fontScale) }
    CompositionLocalProvider(LocalDensity provides density, content = content)
}

private const val DESIGN_LONG_PX = 2560f
private const val DESIGN_SHORT_PX = 1440f
/** The overlay is drawn larger than the console: the bench card was tuned at this zoom. */
internal const val CAR_OVERLAY_ZOOM = 1.35f
/** A 3040×1904 tablet at 440 dpi sits at 0.43. */
internal const val MIN_NATIVE_SHARE = 0.4f
/** A phone's overlay, a little under the platform dp: the portrait card's 420 then takes 378 dp of a 393 dp phone. */
internal const val PHONE_OVERLAY_ZOOM = 0.9f
/** Android's line between phones and tablets (smallest width 600 dp); the 3040×1904 tablet is 692. */
private const val PHONE_SHORT_SIDE_DP = 600f
