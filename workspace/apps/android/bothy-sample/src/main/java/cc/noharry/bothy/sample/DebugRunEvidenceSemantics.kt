package cc.noharry.bothy.sample

/**
 * Tools that count toward replay "business action" for actionable prompts
 * (navi/music/AC backend, plus hosted web_search for chat-style song trivia).
 * [web_search] is intentionally not internal so SearchActivity-backed runs pass.
 */
internal fun DebugRunToolEvidence.isMeaningfulBusinessTool(): Boolean =
    toolName.isNotBlank() && !toolName.isInternalDebugToolName()

internal fun String.isInternalDebugToolName(): Boolean =
    this in INTERNAL_DEBUG_TOOL_NAMES

/** Hosted / provider web_search — not MCP, but still a real user-visible capability. */
internal fun String.isHostedSearchToolName(): Boolean =
    equals(WEB_SEARCH_TOOL_NAME, ignoreCase = true)

internal fun String.requiresBusinessAction(): Boolean {
    // A delegation report is the device passing on another agent's result, not a request to act on.
    if (isDelegationReport()) return false
    val normalized = lowercase()
    return ACTION_KEYWORDS.any { keyword -> normalized.contains(keyword) }
}

private val ACTION_KEYWORDS = listOf(
    "导航",
    "去",
    "停车",
    "厕所",
    "洗手间",
    "沿途",
    "高速",
    "路线",
    "回家",
    "音乐",
    "歌曲",
    "歌",
    "播放",
    "听",
    "主题曲",
    "电视剧",
    "周杰伦",
    "王菲",
    "空调",
    "温度",
    "热",
    "冷",
    "音量",
    "navigate",
    "parking",
    "toilet",
    "restroom",
    "route",
    "music",
    "song",
    "play",
    "hot",
    "cold",
    "warmer",
    "cooler",
)

private const val WEB_SEARCH_TOOL_NAME = "web_search"

/** Scaffold / harness tools — alone do not satisfy actionable car or music chat requests. */
private val INTERNAL_DEBUG_TOOL_NAMES = setOf(
    "finish",
    "list_dir",
    "read_file",
    "grep",
    "write_file",
    "edit_file",
    "update_todo",
    "search_tools",
    "exec_js",
    "unknown",
)
