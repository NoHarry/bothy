package cc.noharry.bothy.playground.nav
import android.content.Context
import android.content.Intent
import android.net.Uri
import cc.noharry.bothy.playground.headunit.HeadUnitModes
import cc.noharry.bothy.playground.headunit.on
import cc.noharry.bothy.playground.mcp.ToolRefusal

/**
 * The 高德地图 App, to hand a route to (owner 2026-10-22: 方案二): its own navigation has what the SDK's withholds, the
 * lights' countdown among it. Without stops it starts navigating at once; with stops it opens its route page with them
 * in order, where 开始导航 is one tap. Coordinates are AMap's own (dev=0). It drives from where the device really is, so
 * routes go to it only with real navigation and the switch on ([HeadUnitMode.handsToApp]).
 */
internal class AmapApp(context: Context) : AppNavigator {
    private val context = context.applicationContext

    override val name = "高德地图 App"

    override val on: Boolean get() = HeadUnitModes.mode.value.handsToApp && installed(context)

    override fun hand(end: Place, stops: List<Place>, preference: RoutePreference): String {
        if (!installed(context)) throw ToolRefusal("这台设备没有装高德地图 App")
        val via = stops.take(MAX_STOPS)
        val uri = if (via.isEmpty()) {
            "androidamap://navi?sourceApplication=$SOURCE&poiname=${end.name.enc()}&lat=${end.point.lat}&lon=${end.point.lng}&dev=0&style=${preference.style()}"
        } else {
            "amapuri://route/plan/?sourceApplication=$SOURCE&dname=${end.name.enc()}&dlat=${end.point.lat}&dlon=${end.point.lng}" +
                "&vian=${via.size}&vianames=${via.joinToString("|") { it.name }.enc()}" +
                "&vialats=${via.joinToString("|") { "${it.point.lat}" }}&vialons=${via.joinToString("|") { "${it.point.lng}" }}&dev=0&t=0"
        }
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val dropped = if (stops.size > via.size) "（高德最多带 $MAX_STOPS 个途经点，后面 ${stops.size - via.size} 个没带上）" else ""
        return if (via.isEmpty()) "已交给高德地图 App，开始导航去「${end.name}」"
        else "已在高德地图 App 打开去「${end.name}」的路线，途经 ${via.numbered()}$dropped，在高德里点「开始导航」就走"
    }

    private fun String.enc(): String = Uri.encode(this)

    /** The App's route style for a preference: 0 fastest, 1 cheapest, 3 no highways, 4 around jams. */
    private fun RoutePreference.style(): Int = when (this) {
        RoutePreference.SAVE_MONEY -> 1
        RoutePreference.NO_HIGHWAY -> 3
        RoutePreference.AVOID_CONGESTION -> 4
        RoutePreference.RECOMMENDED, RoutePreference.HIGHWAY_FIRST -> 0
    }

    companion object {
        const val PACKAGE = "com.autonavi.minimap"
        private const val SOURCE = "Bothy"
        private const val MAX_STOPS = 16

        fun installed(context: Context): Boolean =
            runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess
    }
}
