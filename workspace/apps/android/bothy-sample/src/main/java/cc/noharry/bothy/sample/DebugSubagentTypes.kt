@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import android.content.Context
import cc.noharry.bothy.runtime.SubagentType

/**
 * Kinds of subagent with their own instructions (owner 2026-10-17: 专门做成一个子 agent，可以配置是否加载，里面可以特化
 * 提示词). The main agent names one in spawn_agent's `agent` and writes only the specifics; the child gets the type's
 * `AGENT.md` with its task, at most [maxLookups] pages and searches, and DeepSeek's Search API when [searchApi] says
 * so (null: the subagent setting). Switched on and off in 设置 → 子 agent → 子 agent 类型; a switched-off type is
 * not offered, and skills fall back to writing the whole task (`<!-- no-agent:… -->` sections). Each type may call only
 * its [tools] (owner 2026-10-18: 把它可用的工具也标出来，点击一个子智能体能进他的详情页), set on the type's page.
 */
internal data class DebugSubagentKind(
    val id: String,
    val label: String,
    /** What the model reads in spawn_agent: when to use it. */
    val description: String,
    val assetPath: String,
    val maxLookups: Int = MAX_CHILD_LOOKUPS,
    val searchApi: Boolean? = null,
    val enabled: Boolean = true,
    val tools: Set<DebugSubagentToolGroup> = TRIP_TOOLS,
    /** Offered only while some profile has DeepSeek's Search and Fetch APIs (an official key, or a relay that passed 检测). */
    val requiresDeepSeekLookup: Boolean = false,
) {
    fun toSubagentType(): SubagentType = SubagentType(id, description)

    /** Whether the child searches at all: without it, neither web_search nor the provider's own search is offered. */
    val searches: Boolean get() = DebugSubagentToolGroup.SEARCH in tools

    /** Its tools in the page's order, e.g. 联网搜索、打开网页、读文件. */
    fun toolLabels(separator: String = "、"): String = DebugSubagentToolGroup.entries.filter { it in tools }.joinToString(separator) { it.label }
}

/**
 * What a subagent type may call, by kind of tool rather than by name: the tool list stays the main agent's (one
 * prompt cache), and [DebugSubagentGuard] refuses the rest. [changes] marks the ones that change something.
 */
internal enum class DebugSubagentToolGroup(val wire: String, val label: String, val detail: String, val changes: Boolean = false) {
    SEARCH("search", "联网搜索", "web_search，或模型服务自带的搜索"),
    FETCH("fetch", "打开网页", "web_fetch"),
    READ("read", "读文件", "list_dir、read_file、grep：工作区和技能"),
    WRITE("write", "写文件", "write_file、edit_file", changes = true),
    TODO("todo", "待办", "update_todo"),
    DEVICE_QUERY("device_query", "查设备状态", "设备服务里查询类的工具"),
    DEVICE_CONTROL("device_control", "操作设备", "设备服务里的操作；「允许子 agent 操作设备」也要开着", changes = true),
    SERVICES("services", "其他服务", "地图、日历、外卖、打车、手机操作等已接入的工具", changes = true),
    ;

    companion object {
        fun fromWire(wire: String): DebugSubagentToolGroup? = entries.firstOrNull { it.wire == wire }
    }
}

/** Tools only the main agent has, whatever a type allows: shown greyed on a type's page. */
internal val DEBUG_MAIN_AGENT_ONLY_TOOLS = listOf(
    "问用户" to "ask、ask_async",
    "卡片和选项" to "show_card、offer_*",
    "计划" to "plan_manage",
    "委托个人助手" to "a2a_message_send",
)

internal fun loadDebugSubagentKinds(context: Context): List<DebugSubagentKind> {
    val preferences = context.getSharedPreferences(SUBAGENT_KIND_PREFS, Context.MODE_PRIVATE)
    return SUBAGENT_KINDS.map { kind ->
        kind.copy(
            enabled = preferences.getBoolean(kind.id, kind.enabled),
            tools = preferences.getStringSet(kind.id + KEY_TOOLS, null)?.mapNotNullTo(linkedSetOf(), DebugSubagentToolGroup::fromWire) ?: kind.tools,
            maxLookups = preferences.getInt(kind.id + KEY_MAX_LOOKUPS, kind.maxLookups),
        )
    }
}

/** A type's tools and its share of pages and searches, from its next spawn. */
internal fun saveDebugSubagentKindTools(context: Context, id: String, tools: Set<DebugSubagentToolGroup>, maxLookups: Int) {
    require(SUBAGENT_KINDS.any { it.id == id }) { "Unknown subagent type." }
    context.getSharedPreferences(SUBAGENT_KIND_PREFS, Context.MODE_PRIVATE).edit()
        .putStringSet(id + KEY_TOOLS, tools.mapTo(mutableSetOf()) { it.wire })
        .putInt(id + KEY_MAX_LOOKUPS, maxLookups.coerceIn(MIN_LOOKUPS, MAX_LOOKUPS))
        .apply()
}

/** Back to the type's own tools and share; its switch stays as it is. */
internal fun resetDebugSubagentKindTools(context: Context, id: String) {
    context.getSharedPreferences(SUBAGENT_KIND_PREFS, Context.MODE_PRIVATE).edit()
        .remove(id + KEY_TOOLS)
        .remove(id + KEY_MAX_LOOKUPS)
        .apply()
}

/** The type as it ships, for 恢复默认. */
internal fun debugSubagentKindDefaults(id: String): DebugSubagentKind? = SUBAGENT_KINDS.firstOrNull { it.id == id }

internal fun debugSubagentKind(context: Context, id: String): DebugSubagentKind? = loadDebugSubagentKinds(context).firstOrNull { it.id == id && it.enabled }

/** Saves the switch and offers the change from the next spawn. */
internal fun saveDebugSubagentKind(context: Context, id: String, enabled: Boolean) {
    require(SUBAGENT_KINDS.any { it.id == id }) { "Unknown subagent type." }
    context.getSharedPreferences(SUBAGENT_KIND_PREFS, Context.MODE_PRIVATE).edit().putBoolean(id, enabled).apply()
    DebugSubagents.apply(context)
}

/** The type's instructions without the front matter. */
internal fun debugSubagentKindText(context: Context, kind: DebugSubagentKind): String =
    context.assets.open(kind.assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }.replace("\r\n", "\n")
        .let { text -> FRONT_MATTER.replace(text, "").trim() }

/** `agent:<id>` for each switched-on type, the keys of a skill's conditional sections. */
internal fun debugSubagentKindSections(context: Context): Set<String> =
    loadDebugSubagentKinds(context).filter { it.enabled }.mapTo(linkedSetOf()) { "$AGENT_SECTION_PREFIX${it.id}" }

/** What a trip type needs: the web, its skills, a todo list, and services such as maps; nothing that changes the device. */
private val TRIP_TOOLS = setOf(
    DebugSubagentToolGroup.SEARCH, DebugSubagentToolGroup.FETCH, DebugSubagentToolGroup.READ, DebugSubagentToolGroup.TODO,
    DebugSubagentToolGroup.SERVICES,
)

/** What 网上核实 needs: the web and nothing else. */
private val VERIFY_TOOLS = setOf(DebugSubagentToolGroup.SEARCH, DebugSubagentToolGroup.FETCH)

private val SUBAGENT_KINDS = listOf(
    DebugSubagentKind(
        "trip-transport", "行程 · 交通",
        "travel legs: flights, high-speed trains or a self-drive route between two cities on given dates, with times and prices",
        "runtime-workspace/agents/trip-transport/AGENT.md",
        // A self-drive leg may check the range.
        tools = TRIP_TOOLS + DebugSubagentToolGroup.DEVICE_QUERY,
    ),
    DebugSubagentKind(
        "trip-stay", "行程 · 住宿",
        "where to stay on a trip: areas and hotels for the itinerary, with prices, transit and parking",
        "runtime-workspace/agents/trip-stay/AGENT.md",
        maxLookups = 6,
    ),
    DebugSubagentKind(
        "trip-days", "行程 · 每天安排",
        "each day of a trip: the weather forecast first, then 2–3 places a day in route order with times, indoor on rainy days",
        "runtime-workspace/agents/trip-days/AGENT.md",
    ),
    // owner 2026-10-19: 除了导航给出的数据，也派一个核实的 agent 去网页上搜一下、加点信息，不一定在哪种场景下触发;
    // 先答再补报.
    DebugSubagentKind(
        WEB_VERIFY_KIND, "网上核实",
        "check online and add to what a map or another service returned, or to a flight or train the user named: for " +
            "places (restaurants, hotels, sights) the rating, average price, opening hours and what people say; for a flight " +
            "or train today's times, terminal, gate and delays. Spawn it in the same round as your answer from the service " +
            "data, say you are checking online, and finish without waiting: its result comes back as a report to pass on",
        "runtime-workspace/agents/web-verify/AGENT.md",
        maxLookups = 6,
        searchApi = true,
        tools = VERIFY_TOOLS,
        requiresDeepSeekLookup = true,
    ),
)

internal const val AGENT_SECTION_PREFIX = "agent:"
internal const val WEB_VERIFY_KIND = "web-verify"
private const val SUBAGENT_KIND_PREFS = "debug_subagent_types"
private const val KEY_TOOLS = ".tools"
private const val KEY_MAX_LOOKUPS = ".max_lookups"
internal const val MIN_LOOKUPS = 1
internal const val MAX_LOOKUPS = 20
private val FRONT_MATTER = Regex("""\A---\n.*?\n---\n""", RegexOption.DOT_MATCHES_ALL)
