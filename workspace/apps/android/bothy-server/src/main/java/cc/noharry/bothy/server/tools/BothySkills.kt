package cc.noharry.bothy.server.tools

import android.content.Context
import cc.noharry.agent.appserver.skills.DEFAULT_SUBAGENT_TOOLS
import cc.noharry.agent.appserver.skills.RuntimeSkill
import cc.noharry.agent.appserver.skills.SubagentKind
import cc.noharry.agent.appserver.skills.SubagentToolGroup
import cc.noharry.agent.appserver.skills.WEB_VERIFY_KIND
import cc.noharry.bothy.server.R

/**
 * Workflow skills default on: a matched skill pre-activates its tools before the first model request, which
 * removes pure discovery rounds and keeps the tool list stable for the prompt cache. Saved choices still win.
 * Their names and summaries, shown in Settings, are in [context]'s language.
 */
internal fun bothySkills(context: Context): List<RuntimeSkill> = listOf(
    RuntimeSkill(
        "debug_voice_progress", context.getString(R.string.skills_voice_progress),
        context.getString(R.string.skills_voice_progress_about),
        "runtime-workspace/skills/debug_voice_progress/SKILL.md", enabled = true, preload = true, systemPrompt = true,
    ),
    RuntimeSkill(
        "mcp-task-orchestration", context.getString(R.string.skills_task_orchestration),
        context.getString(R.string.skills_task_orchestration_about),
        "runtime-workspace/skills/mcp-task-orchestration/SKILL.md", enabled = false, preload = true,
    ),
    RuntimeSkill(
        "travel-planning", context.getString(R.string.skills_travel_planning),
        context.getString(R.string.skills_travel_planning_about),
        "runtime-workspace/skills/travel-planning/SKILL.md", enabled = true, preload = true,
        manifestPath = "runtime-workspace/skills/travel-planning/runtime.json", mainAgentOnly = true,
    ),
    RuntimeSkill(
        "personal-agent-delegation", context.getString(R.string.skills_delegation),
        context.getString(R.string.skills_delegation_about),
        "runtime-workspace/skills/personal-agent-delegation/SKILL.md", enabled = true, preload = true, requiresA2aDelegate = true,
        mainAgentOnly = true,
    ),
    RuntimeSkill(
        "android-use", context.getString(R.string.skills_android_use),
        context.getString(R.string.skills_android_use_about),
        "runtime-workspace/skills/android-use/SKILL.md", enabled = true, preload = true, requiresAndroidUse = true,
    ),
    // In the catalog, read when the model needs it: playground_open's own description covers the common asks; the
    // skill adds what to do when a system dialog keeps it from the front or the Playground is not installed.
    RuntimeSkill(
        "playground", context.getString(R.string.skills_playground),
        context.getString(R.string.skills_playground_about),
        "runtime-workspace/skills/playground/SKILL.md", enabled = true,
    ),
)

/** What a trip type needs: the web, its skills, a todo list, and services such as maps; nothing that changes the device. */
private val TRIP_TOOLS = DEFAULT_SUBAGENT_TOOLS

/** What 网上核实 needs: the web and nothing else. */
private val VERIFY_TOOLS = setOf(SubagentToolGroup.SEARCH, SubagentToolGroup.FETCH)

/** Bothy's subagent types: the three trip planners and 网上核实. */
internal val BOTHY_SUBAGENT_KINDS = listOf(
    SubagentKind(
        "trip-transport", R.string.skills_kind_trip_transport,
        "travel legs: flights, high-speed trains or a self-drive route between two cities on given dates, with times and prices",
        "runtime-workspace/agents/trip-transport/AGENT.md",
        // A self-drive leg may check the range.
        tools = TRIP_TOOLS + SubagentToolGroup.DEVICE_QUERY,
    ),
    SubagentKind(
        "trip-stay", R.string.skills_kind_trip_stay,
        "where to stay on a trip: areas and hotels for the itinerary, with prices, transit and parking",
        "runtime-workspace/agents/trip-stay/AGENT.md",
        maxLookups = 6,
    ),
    SubagentKind(
        "trip-days", R.string.skills_kind_trip_days,
        "each day of a trip: the weather forecast first, then 2–3 places a day in route order with times, indoor on rainy days",
        "runtime-workspace/agents/trip-days/AGENT.md",
    ),
    // owner 2026-10-19: 除了导航给出的数据，也派一个核实的 agent 去网页上搜一下、加点信息，不一定在哪种场景下触发;
    // 先答再补报.
    SubagentKind(
        WEB_VERIFY_KIND, R.string.skills_kind_web_verify,
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
