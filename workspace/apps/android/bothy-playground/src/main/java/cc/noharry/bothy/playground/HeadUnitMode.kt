package cc.noharry.bothy.playground

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How the head unit looks with real navigation on (owner 2026-10-22). Neutral names: the layouts are borrowed, no
 * maker's name or mark is.
 */
enum class HeadUnitTheme(val id: String, val label: String) {
    /** The columns of the simulated head unit, 小博 in the car's column and, with something to show, the next one too. */
    COLUMNS("columns", "分栏卡片"),

    /** The map under everything and a bar along the bottom; 小博 in a panel over the driver's third of the map. */
    FULL_MAP("full_map", "全屏地图"),
}

/**
 * The simulated car and home ([realNav] off), or real navigation and 小博 alone (owner 2026-10-22: 「切换这个模式后那些模拟的
 * mcp都下掉，就剩真实导航和小博」): the device's own position, real driving, only the navigation tools served.
 */
data class HeadUnitMode(val realNav: Boolean = false, val theme: HeadUnitTheme = HeadUnitTheme.COLUMNS)

/** The head unit's mode for the process, kept across starts; the screen and the MCP server both read it. */
object HeadUnitModes {
    private val state = MutableStateFlow(HeadUnitMode())
    @Volatile private var loaded = false
    val mode: StateFlow<HeadUnitMode> = state
    val realNav: Boolean get() = state.value.realNav

    fun load(context: Context) {
        if (loaded) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val theme = HeadUnitTheme.entries.firstOrNull { it.id == prefs.getString(THEME, null) } ?: HeadUnitTheme.COLUMNS
        state.value = HeadUnitMode(prefs.getBoolean(REAL_NAV, false), theme)
        loaded = true
    }

    /**
     * Switches to [next]. Entering or leaving real navigation ends the route under way (a simulated drive does not go on
     * as a real one, nor the other way round), puts the car where the device is or back at the demo's place, and tells
     * Bothy the servers' tools changed.
     */
    fun set(context: Context, next: HeadUnitMode) {
        val before = state.value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(REAL_NAV, next.realNav).putString(THEME, next.theme.id).apply()
        state.value = next
        if (before.realNav == next.realNav) return
        Playground.engine?.realNav = next.realNav
        Playground.navigation?.let { navigation ->
            navigation.stop()
            navigation.setSimulated(!next.realNav)
        }
        Assistant.toolsChanged(context)
    }

    private const val PREFS = "head_unit"
    private const val REAL_NAV = "real_nav"
    private const val THEME = "theme"
}
