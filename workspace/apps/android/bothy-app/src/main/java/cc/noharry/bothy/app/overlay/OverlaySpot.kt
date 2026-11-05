package cc.noharry.bothy.app.overlay

import android.content.Context

/** Where the person left 小博, kept across starts; [defaultX], [defaultY] the first time. */
internal fun Context.bubbleSpot(defaultX: Int, defaultY: Int): Pair<Int, Int> =
    getSharedPreferences(OVERLAY_PREFS, Context.MODE_PRIVATE).let { it.getInt(KEY_BUBBLE_X, defaultX) to it.getInt(KEY_BUBBLE_Y, defaultY) }

internal fun Context.saveBubbleSpot(x: Int, y: Int) {
    getSharedPreferences(OVERLAY_PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_BUBBLE_X, x).putInt(KEY_BUBBLE_Y, y).apply()
}

private const val OVERLAY_PREFS = "debug_overlay"
private const val KEY_BUBBLE_X = "bubble_x"
private const val KEY_BUBBLE_Y = "bubble_y"
