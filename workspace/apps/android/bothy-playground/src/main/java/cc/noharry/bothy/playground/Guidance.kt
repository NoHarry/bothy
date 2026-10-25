package cc.noharry.bothy.playground

import android.graphics.Bitmap
import com.amap.api.navi.model.AMapLaneInfo
import kotlinx.coroutines.flow.MutableStateFlow

/** A stretch of the route ahead and how it flows: AMap's 0 unknown, 1 smooth, 2 slow, 3 jammed, 4 badly jammed. */
data class TrafficStretch(val meters: Int, val status: Int)

/**
 * AMap's own drawings of the guidance, for the head unit to place as its stock screen does (owner 2026-10-22: 方案三):
 * the lanes at the junction ahead, the junction's picture, the route's traffic. Kept apart from the world, which goes to
 * the assistant as JSON: these are AMap's types and bitmaps, only for the screen.
 */
object Guidance {
    val lanes = MutableStateFlow<AMapLaneInfo?>(null)
    /** The junction's picture, a copy of AMap's: its own may be recycled once it hides it, while the screen still draws. */
    val cross = MutableStateFlow<Bitmap?>(null)
    /** The whole route from its start, stretch by stretch. */
    val traffic = MutableStateFlow<List<TrafficStretch>>(emptyList())

    /** AMap shows the junction ahead as [picture] (or stops, with null). */
    fun showCross(picture: Bitmap?) {
        cross.value = picture?.takeUnless { it.isRecycled }?.let { it.copy(it.config ?: Bitmap.Config.ARGB_8888, false) }
    }

    fun clear() {
        lanes.value = null
        cross.value = null
        traffic.value = emptyList()
    }
}
