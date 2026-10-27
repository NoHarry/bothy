package cc.noharry.bothy.app.runtime
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.core.MAX_HOST_SYSTEM_PROMPT_BYTES
import cc.noharry.agent.kernel.core.validateHostSystemPrompt
import cc.noharry.bothy.app.model.appSystemPrompt
import cc.noharry.bothy.app.plans.PLAN_WEEKDAYS
import cc.noharry.bothy.app.plans.PlanRunner
import cc.noharry.bothy.app.trajectory.render

/**
 * The host prompt in two parts. [system] is the same every run (base, tool settings, voice skill); [runContext] is what
 * changes per run (session recap, workflow skills picked for this input, the previous exchange) and is sent after the
 * input, so the provider's prompt cache keeps the system prompt and tool list from run to run. [text] is both together,
 * which is what the 16 KiB host budget covers.
 */
internal data class RuntimePrompt(val system: String, val runContext: String) {
    val text: String get() = listOf(system, runContext).joinPromptSections()
}

/** Budget the final host prompt, including separators and UTF-8, before constructing a runtime. */
internal fun runtimePrompt(
    sessionContext: String,
    basicTools: List<BasicTool>,
    skillBodies: List<Pair<String, String>>,
    previousExchange: PreviousExchange? = null,
    nativeTools: Boolean = false,
    /** 「现在：2026-10-19 周一 11:35」 ([clockLine]): per run, so it goes with the run context, never the system prompt. */
    clock: String = "",
    /** 网上核实 is offered ([WEB_VERIFY_RULE]); that changes only when a DeepSeek key comes or goes, so it stays cached. */
    verifyAgent: Boolean = false,
    /** The main agent can search the web itself ([WEB_SEARCH_RULE]); fixed for the profile, so it stays cached too. */
    webSearch: Boolean = false,
): RuntimePrompt {
    // Reserve enabled voice instructions before history and optional workflow bodies.
    val (voice, workflows) = skillBodies.partition { it.first == "debug_voice_progress" }
    val rules = listOf(if (webSearch) WEB_SEARCH_RULE else "", if (verifyAgent) WEB_VERIFY_RULE else "")
    val fixed = (listOf(appSystemPrompt("", nativeTools), basicToolPrompt(basicTools)) + rules +
        voice.map { (id, body) -> "$ACTIVE_SKILL_PREFIX$id\n$body" }).joinPromptSections()
    val canRead = "read_file" !in disabledBasicTools(basicTools)
    val replyContext = previousExchange?.prompt(canRead).orEmpty()
    val sections = workflows.map { (id, _) ->
        "Deferred runtime skill: $id. Before using its workflow, read /.agents/skills/$id/SKILL.md in full."
    }.toMutableList()
    // Reserve the immediate exchange before background history/skills.
    // The thinking's language last, nearest to where the model starts thinking.
    fun render(context: String) = RuntimePrompt(fixed, (listOf(clock, context) + sections + replyContext + THINK_IN_CHINESE).joinPromptSections())

    // Continuity takes priority over eager loading. Keep whole history lines; the full history
    // and all enabled skill files remain in the same VFS snapshot used by this runtime.
    val contextBudget = MAX_HOST_SYSTEM_PROMPT_BYTES - render("").text.utf8Bytes() - 2
    val context = budgetSessionContext(sessionContext, contextBudget, canRead)
    validateHostSystemPrompt(render(context).text)
    workflows.forEachIndexed { index, (id, body) ->
        val reference = sections[index]
        sections[index] = "$ACTIVE_SKILL_PREFIX$id\n$body"
        if (render(context).text.utf8Bytes() > MAX_HOST_SYSTEM_PROMPT_BYTES) {
            sections[index] = reference
            require(canRead) {
                "Runtime skills exceed the host prompt budget. Enable read_file to load deferred skills."
            }
        }
    }
    return render(context).also { validateHostSystemPrompt(it.text) }
}

private fun budgetSessionContext(context: String, maxBytes: Int, canRead: Boolean): String {
    if (context.isBlank()) return ""
    val header = "Current debug session context for continuity:"
    val full = "$header\n$context"
    if (full.utf8Bytes() <= maxBytes) return full
    require(canRead) { "Session context exceeds the host prompt budget. Enable read_file to read full history." }
    val notice = "History preview limited by host prompt budget. Read /context/session/current.json for omitted history before resolving context-dependent requests."
    var preview = header
    require("$preview\n$notice".utf8Bytes() <= maxBytes) { "Host instructions leave no space for session context." }
    for (line in context.lineSequence()) {
        if ("$preview\n$line\n$notice".utf8Bytes() > maxBytes) break
        preview += "\n$line"
    }
    return "$preview\n$notice"
}

/** Whether an earlier run already sent workflow skill [id] in full (the voice skill lives in the system prompt). */
internal fun List<ModelMessage>.carriesSkill(id: String): Boolean =
    any { "$ACTIVE_SKILL_PREFIX$id\n" in it.content }

private const val ACTIVE_SKILL_PREFIX = "Active runtime skill: "

private fun String.utf8Bytes(): Int = toByteArray(Charsets.UTF_8).size
private fun List<String>.joinPromptSections(): String = filter { it.isNotBlank() }.joinToString("\n\n")

/**
 * The device's date and time for the run context (owner 2026-10-19: 「现在出发早不早」 needs the time, and the plans'
 * context gives only the date, kept stable for the prompt cache).
 */
internal fun clockLine(now: Long, zone: java.time.ZoneId = PlanRunner.zone()): String {
    val time = java.time.Instant.ofEpochMilli(now).atZone(zone)
    return "现在：%s %s %02d:%02d".format(time.toLocalDate(), PLAN_WEEKDAYS[time.dayOfWeek.value - 1], time.hour, time.minute)
}

/**
 * The app in front, as Android Use's service sees it (owner 2026-10-25: with 抖音 open, 小博 still offered to open it):
 * named each run, so it is not opened again. Empty without the service, or with Bothy itself in front.
 */
internal fun appForegroundLine(context: android.content.Context): String {
    val app = cc.noharry.agent.androiduse.AndroidUseAccessibility.foregroundApp(context) ?: return ""
    return if (app.packageName == context.packageName) "" else foregroundLine(app.label, app.packageName)
}

internal fun foregroundLine(label: String, packageName: String): String =
    "屏幕前台：「$label」（$packageName），已经开着，操作它不用再打开；用户没说对象的「暂停」「继续」「下一个」「这个」，先想到它里面正在放、正在看的内容。"

/**
 * When to send 网上核实 (owner 2026-10-19: 除了导航给出的数据，也派一个核实的 agent 去网页上搜一下，不一定在哪种场景下
 * 触发; 先答再补报; 不能拖慢正常导航，只在需要印证的查询里触发). A rule of the main agent's, not a skill matched by
 * words, so any scene can use it; on the bench the type's description alone left a nearby-food answer at the map's data,
 * and a spawn of its own cost the answer a model round (≈3.5 s), so it goes out with the card.
 */
internal const val WEB_VERIFY_RULE =
    "Verify online with web-verify only where the map's data is not enough to choose: places to eat, stay or visit the " +
        "user asked you to find or recommend (from a place search), or a flight or train the user named. Answer from the " +
        "service data and call spawn_agent with agent=web-verify in the same response as show_card, not as a step of its " +
        "own (task: the items as the service gave them with their addresses, the city, today's date, what to check). Say in " +
        "the reply that you are checking online; do not wait_agent (except for a flight or train whose arrival decides when " +
        "the user sets off: wait, then say when to leave): its result comes back as a report. When it does, put " +
        "what it found on the card (show_card) and speak at most two short sentences: only what changes the choice (a place " +
        "that is closed or not what it seemed, the best pick now); the details stay on the card. Do not read the list or " +
        "the options again. Never for navigating to a place the user named; fuel, charging, parking, toilets or service " +
        "areas; a route, distance or arrival time; device control; what you found by searching the web yourself (check " +
        "that in the same turn instead); or things the user did not ask about."

/**
 * Questions the web answers (owner 2026-10-19: 「这周末有什么活动」本来就该走 search，不用再派核实; 需要印证的同时发出去，
 * 最后综合判断一起发出来). On the bench the agent searched once, answered, then sent web-verify, which searched the
 * same events one per step for 35 s and came back as a second answer; parallel calls of one response run together.
 * Sightseeing routes are here too, not a skill of their own (owner 2026-10-19: 不要一个功能一个 skill): on the bench
 * 「八景都玩一遍」 was filled from memory and routed all eight for one afternoon although two closed before arrival.
 * Weather too (owner 2026-10-25: 「天气直接走deepseek的搜索就行了」): asked with the time, the agent looked for a tool and
 * listed files twice before it searched.
 */
internal const val WEB_SEARCH_RULE =
    "Weather (now, today, tomorrow, the coming days; rain, temperature, wind) is a web search: search in the first step, " +
        "not search_tools for a weather tool and not files, and answer with the place and the day. " +
        "Questions the web answers (events and shows, news, opening times, prices): search. When the results leave several " +
        "items the user would pick from unsure (dates, venue or tickets only from a listing or an old page), search those " +
        "all in one step, several search calls in the same response rather than one per step, then answer once with what " +
        "you checked on the card (show_card). Do not answer before those checks, and do not spawn web-verify for them. " +
        "A route through places with opening hours (sights, shops; for a named set such as 「经典景点」 search which they " +
        "are, not from memory): check today's hours in that same step, estimate each arrival from now with time to look " +
        "around, decide which fit today before you calculate, route today only those, and say what moves to tomorrow " +
        "with an offer to remind; what you say and the route you start must match."

/**
 * A car's screen shows the thinking as it comes, for the user to read; DeepSeek thinks in English for an English system
 * prompt and keeps to it even when told there. This line, last in the run's own context, comes right before it starts
 * thinking; at the start of that context it was followed only now and then (2026-10-21).
 */
internal const val THINK_IN_CHINESE = "本轮的思考过程（thinking）全程用简体中文写，不要用英文。"
