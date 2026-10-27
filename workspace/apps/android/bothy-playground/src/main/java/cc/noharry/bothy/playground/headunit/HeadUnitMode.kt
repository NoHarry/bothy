package cc.noharry.bothy.playground.headunit
import android.content.Context
import cc.noharry.bothy.playground.Playground
import cc.noharry.bothy.playground.link.Assistant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How the head unit looks, set by the mode (owner 2026-10-25: 「主题不用弄2个了，真实模式就用全屏地图，模拟就用分栏」). Neutral
 * names: the layouts are borrowed, no maker's name or mark is.
 */
enum class HeadUnitTheme {
    /** The simulated car's head unit: the car in the left column, cards beside it. */
    CLASSIC,

    /** Real navigation: the map under everything and a bar along the bottom; 小博 in a panel over the driver's third. */
    FULL_MAP,
}

/**
 * The simulated car and home ([realNav] off), or real navigation and 小博 alone (owner 2026-10-22: 「切换这个模式后那些模拟的
 * mcp都下掉，就剩真实导航和小博」): the device's own position, real driving, only the navigation tools served.
 */
data class HeadUnitMode(
    val realNav: Boolean = false,
    /** Routes driven in the 高德地图 App instead ([AmapApp], owner 2026-10-22: 「增加一个开关可以切方案二」); real navigation only. */
    val amapApp: Boolean = false,
) {
    /** The layout shown: the car's own with the simulated car, the whole map with real navigation. */
    val layout: HeadUnitTheme get() = if (realNav) HeadUnitTheme.FULL_MAP else HeadUnitTheme.CLASSIC

    /** Whether a route is handed to the 高德地图 App to drive: it drives where the device is, so never a simulated one. */
    val handsToApp: Boolean get() = realNav && amapApp
}

/** The head unit's mode for the process, kept across starts; the screen and the MCP server both read it. */
object HeadUnitModes {
    private val state = MutableStateFlow(HeadUnitMode())
    @Volatile private var loaded = false
    val mode: StateFlow<HeadUnitMode> = state
    val realNav: Boolean get() = state.value.realNav

    fun load(context: Context) {
        if (loaded) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        state.value = HeadUnitMode(prefs.getBoolean(REAL_NAV, false), prefs.getBoolean(AMAP_APP, false))
        loaded = true
    }

    /**
     * Switches to [next]. Entering or leaving real navigation ends the route under way (a simulated drive does not go on
     * as a real one, nor the other way round), puts the car where the device is or back at the demo's place, and tells
     * Bothy, which lists the servers' tools again and starts a new conversation ([Assistant.modeChanged]).
     */
    fun set(context: Context, next: HeadUnitMode) {
        val before = state.value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(REAL_NAV, next.realNav).putBoolean(AMAP_APP, next.amapApp).apply()
        state.value = next
        if (before.realNav == next.realNav) return
        Playground.engine?.realNav = next.realNav
        Playground.navigation?.let { navigation ->
            navigation.stop()
            navigation.setSimulated(!next.realNav)
        }
        Assistant.modeChanged(context)
    }

    private const val PREFS = "head_unit"
    private const val REAL_NAV = "real_nav"
    private const val AMAP_APP = "amap_app"
}
