package cc.noharry.bothy.playground

import android.content.Context
import android.util.Log
import android.view.View
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Poi
import com.amap.api.navi.AmapNaviPage
import com.amap.api.navi.AmapNaviParams
import com.amap.api.navi.AmapNaviType
import com.amap.api.navi.AmapPageType
import com.amap.api.navi.INaviInfoCallback
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.model.AMapNaviLocation

/**
 * AMap's own navigation page, to hold the head unit against (owner 2026-10-22: 「你和公版的对比改下」): the route planned
 * now, its stops and destination, driven in AMap's stock screen, simulated or real as the head unit drives. A bench tool:
 * `adb shell am start --activity-single-top -n cc.noharry.bothy.playground/.PlaygroundActivity --ez stock_navi true` after
 * planning (single-top, or a running head unit is only brought to the front).
 */
internal object StockNavi {
    const val EXTRA = "stock_navi"

    fun open(context: Context) {
        val nav = Playground.store.read().cabin.nav
        val end = nav.destination ?: return run { Log.w(TAG, "no route planned: plan one first") }
        val start = Playground.engine?.currentLocation()?.let { Poi("起点", LatLng(it.lat, it.lng), "") }
        val params = AmapNaviParams(start, nav.waypoints.map { it.poi() }, end.poi(), AmapNaviType.DRIVER, AmapPageType.NAVI)
            .setNaviMode(if (nav.simulated) NaviType.EMULATOR else NaviType.GPS)
            .setUseInnerVoice(true)
            .setNeedCalculateRouteWhenPresent(true)
        // The head unit gives the navigator up first: AMap's page plans and drives on the same one.
        Playground.navigation?.stop()
        AmapNaviPage.getInstance().showRouteActivity(context, params, Quiet)
        Log.i(TAG, "AMap's page opened for 「${end.name}」 with ${nav.waypoints.size} stop(s), ${if (nav.simulated) "simulated" else "real"}")
    }

    private fun Place.poi() = Poi(name, LatLng(point.lat, point.lng), poiId)

    /** Nothing to hear from AMap's page: the head unit only looks at it. */
    private object Quiet : INaviInfoCallback {
        override fun onInitNaviFailure() = Unit
        override fun onGetNavigationText(text: String?) = Unit
        override fun onLocationChange(location: AMapNaviLocation?) = Unit
        override fun onArriveDestination(simulated: Boolean) = Unit
        override fun onStartNavi(type: Int) = Unit
        override fun onCalculateRouteSuccess(ids: IntArray?) = Unit
        override fun onCalculateRouteFailure(code: Int) = Unit
        override fun onStopSpeaking() = Unit
        override fun onReCalculateRoute(type: Int) = Unit
        override fun onExitPage(type: Int) = Unit
        override fun onStrategyChanged(strategy: Int) = Unit
        override fun onArrivedWayPoint(index: Int) = Unit
        override fun onMapTypeChanged(type: Int) = Unit
        override fun onNaviDirectionChanged(direction: Int) = Unit
        override fun onDayAndNightModeChanged(mode: Int) = Unit
        override fun onBroadcastModeChanged(mode: Int) = Unit
        override fun onScaleAutoChanged(auto: Boolean) = Unit
        override fun getCustomMiddleView(): View? = null
        override fun getCustomNaviView(): View? = null
        override fun getCustomNaviBottomView(): View? = null
    }

    private const val TAG = "StockNavi"
}
