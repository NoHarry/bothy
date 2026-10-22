package cc.noharry.bothy.playground

import com.amap.api.navi.model.AMapLaneInfo
import com.amap.api.navi.model.AMapNaviCross
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
    val cross = MutableStateFlow<AMapNaviCross?>(null)
    /** The whole route from its start, stretch by stretch. */
    val traffic = MutableStateFlow<List<TrafficStretch>>(emptyList())

    fun clear() {
        lanes.value = null
        cross.value = null
        traffic.value = emptyList()
    }
}
