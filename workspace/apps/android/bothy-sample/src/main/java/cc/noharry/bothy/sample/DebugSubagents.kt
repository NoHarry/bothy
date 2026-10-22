@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import android.content.Context
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.kernel.api.HookContext
import cc.noharry.agent.kernel.api.HookDecision
import cc.noharry.agent.kernel.api.RuntimeHook
import cc.noharry.agent.kernel.api.ToolUse
import cc.noharry.agent.kernel.api.model.ModelRef
import cc.noharry.agent.runtime.SubagentConfig
import cc.noharry.agent.runtime.SubagentLauncher
import cc.noharry.agent.runtime.SubagentReport
import cc.noharry.agent.runtime.SubagentSpec
import cc.noharry.agent.runtime.SubagentState
import cc.noharry.agent.runtime.SubagentType
import cc.noharry.agent.runtime.Subagents
import java.util.concurrent.ConcurrentHashMap
import android.util.Log
import cc.noharry.agent.runtime.SubagentStatus
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Background subagents in the Sample (owner 2026-10-17): the main agent decides when to hand parallel, time-consuming
 * work to spawn_agent. One process-wide [Subagents]; each child runs on a runtime built like its parent's (the
 * parent's run registers the recipe with [rememberChildRuntime]) behind [DebugSubagentGuard]. A result or stall the
 * parent's run did not collect becomes a spoken turn, like an A2A report. While a conversation has unfinished
 * children a wake keeps it rather than starting a new session. Settings: device control off and the parent's model,
 * unless changed ([saveDebugSubagentSettings]).
 */
internal object DebugSubagents {
    private val recipes = ConcurrentHashMap<String, (SubagentSpec) -> AgentRuntime>()
    private val usage = ConcurrentHashMap<String, DebugTokenUsageState>()
    private val boardState = MutableStateFlow<Map<String, List<SubagentStatus>>>(emptyMap())
    private val lastState = ConcurrentHashMap<String, SubagentState>()
    private val deliveryState = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val settingsState = MutableStateFlow(DebugSubagentSettings())
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var lifecycle: ((SubagentStatus, DebugSubagentChange) -> Unit)? = null
    @Volatile
    private var types: List<SubagentType> = emptyList()

    val instance = Subagents(SubagentConfig(), SubagentLauncher(::launch))

    /** The Board's view: each conversation's latest children, finished ones included, oldest first. */
    val board: StateFlow<Map<String, List<SubagentStatus>>> = boardState.asStateFlow()

    /**
     * Children whose late result is on its way to the conversation, by [deliveryKey]: [REPORT_QUEUED] until the report's
     * turn starts, then when it started. The card keeps their lanes up meanwhile.
     */
    val deliveries: StateFlow<Map<String, Long>> = deliveryState.asStateFlow()

    fun install(context: Context, deliver: (SubagentReport) -> Unit) {
        appContext = context.applicationContext
        apply(context)
        instance.onStatus(::observe)
        instance.onReport { report ->
            markReport(report, REPORT_QUEUED)
            deliver(report)
        }
    }

    /** [report]'s turn has started: the agent is telling the user now. */
    fun reportDelivered(report: SubagentReport, now: Long = System.currentTimeMillis()) = markReport(report, now)

    private fun markReport(report: SubagentReport, at: Long) {
        deliveryState.update { marks -> (marks + report.statuses.associate { it.deliveryKey() to at }).entries.toList().takeLast(MAX_DELIVERY_MARKS).associate { it.toPair() } }
    }

    /** Started, stalled, moving again, finished: for the transcript of the conversation on screen. */
    fun onLifecycle(listener: ((SubagentStatus, DebugSubagentChange) -> Unit)?) {
        lifecycle = listener
    }

    /** The saved settings, for the Settings page. */
    val settings: StateFlow<DebugSubagentSettings> = settingsState.asStateFlow()

    /** Reads the saved settings and the switched-on subagent types into the next spawns. */
    fun apply(context: Context) {
        types = offeredTypes(context, DebugProviderConfig.load(context.applicationContext).hasDeepSeekKey(context))
        applySettings(loadDebugSubagentSettings(context))
    }

    /**
     * Before a main agent's run: a type that needs DeepSeek's lookups comes and goes with them. That changes the tool
     * list, so only an actual change is applied (it happens when a key is added or removed, not run to run).
     */
    fun refreshTypes(context: Context, deepSeekLookup: Boolean) {
        val offered = offeredTypes(context, deepSeekLookup)
        if (offered == types) return
        types = offered
        instance.config = instance.config.copy(types = offered)
    }

    /** Whether spawn_agent offers the type [name] now. */
    fun offers(name: String): Boolean = types.any { it.name == name }

    private fun offeredTypes(context: Context, deepSeekLookup: Boolean): List<SubagentType> =
        loadDebugSubagentKinds(context).filter { it.enabled && (deepSeekLookup || !it.requiresDeepSeekLookup) }.map { it.toSubagentType() }

    fun applySettings(settings: DebugSubagentSettings) {
        settingsState.value = settings
        instance.config = settings.toConfig().copy(types = types)
    }

    /** How to build a child of [sessionId]: the latest parent run's runtime recipe. */
    fun rememberChildRuntime(sessionId: String, recipe: (SubagentSpec) -> AgentRuntime) {
        recipes[sessionId] = recipe
    }

    /** What [sessionId]'s children used this process, for the Board's session total. */
    fun usage(sessionId: String): DebugTokenUsageState = usage[sessionId] ?: DebugTokenUsageState()

    fun forget(sessionId: String?) {
        instance.cancelSession(sessionId)
        if (sessionId == null) {
            recipes.clear()
            usage.clear()
            boardState.value = emptyMap()
            deliveryState.value = emptyMap()
        } else {
            recipes.remove(sessionId)
            usage.remove(sessionId)
            boardState.update { it - sessionId }
            deliveryState.update { marks -> marks.filterKeys { !it.startsWith("$sessionId/") } }
        }
    }

    private fun observe(status: SubagentStatus) {
        boardState.update { boards ->
            val rows = boards[status.parentSessionId].orEmpty()
            val updated = if (rows.any { it.id == status.id }) rows.map { if (it.id == status.id) status else it } else rows + status
            boards + (status.parentSessionId to updated.takeLast(MAX_BOARD_ROWS))
        }
        val before = lastState.put("${status.parentSessionId}/${status.id}", status.state)
        val change = debugSubagentChange(before, status.state) ?: return
        val type = status.agentType.takeIf(String::isNotEmpty)?.let { " [$it]" }.orEmpty()
        Log.i(TAG, "${status.parentSessionId} ${status.id} 「${status.label}」$type ${change.name.lowercase()}: ${status.logLine()}")
        if (change == DebugSubagentChange.FINISHED) appContext?.let { record(it, status) }
        lifecycle?.invoke(status, change)
    }

    /** One line per finished child in its conversation's log folder, for looking back after the fact. */
    private fun record(context: Context, status: SubagentStatus) {
        runCatching {
            val line = buildJsonObject {
                put("id", status.id)
                put("label", status.label)
                if (status.agentType.isNotEmpty()) put("agentType", status.agentType)
                put("state", status.state.wireName)
                put("sessionId", status.sessionId)
                put("task", status.task)
                put("startedAt", status.startedAtEpochMillis)
                put("finishedAt", status.finishedAtEpochMillis)
                put("toolCalls", status.toolCalls)
                put("modelCalls", status.modelCalls)
                put("inputTokens", status.inputTokens)
                put("cachedTokens", status.cachedTokens)
                put("outputTokens", status.outputTokens)
                put("result", status.result)
                put("error", status.error)
            }.toString()
            File(debugSessionEventLogRoot(context, status.parentSessionId), SUBAGENT_RECORDS).apply {
                parentFile?.mkdirs()
                appendText(line + "\n")
            }
        }.onFailure { Log.w(TAG, "subagent record not written: ${it.message}") }
    }

    private fun launch(spec: SubagentSpec): Flow<AgentEvent> = flow {
        val recipe = recipes[spec.parentSessionId] ?: error("No runtime recipe for ${spec.parentSessionId}")
        val runtime = recipe(spec)
        try {
            val kind = spec.agentType?.let { id -> appContext?.let { debugSubagentKind(it, id) } }
            val instructions = kind?.let { appContext?.let { context -> debugSubagentKindText(context, it) } }.orEmpty()
            val request = AgentRequest(
                requestId = "${spec.sessionId}-run",
                sessionId = spec.sessionId,
                input = debugSubagentInput(spec, kind, instructions),
                modelRef = spec.modelRef,
            )
            runtime.run(request).collect { event ->
                if (event is AgentEvent.TokenUsageDelta) {
                    usage.merge(spec.parentSessionId, DebugTokenUsageState().plus(event.toDebugUsageDelta()), DebugTokenUsageState::plus)
                }
                emit(event)
            }
        } finally {
            runtime.close()
        }
    }
}

internal enum class DebugSubagentChange { STARTED, STALLED, RESUMED, FINISHED }

/** The transition worth a transcript line and a log line, or null when only progress moved. */
internal fun debugSubagentChange(before: SubagentState?, now: SubagentState): DebugSubagentChange? = when {
    before == now -> null
    now.finished -> DebugSubagentChange.FINISHED
    now == SubagentState.STALLED -> DebugSubagentChange.STALLED
    before == null -> DebugSubagentChange.STARTED
    before == SubagentState.STALLED -> DebugSubagentChange.RESUMED
    else -> null
}

/** Seconds since start (or until the end), tool and model calls, tokens and cache hit. */
internal fun SubagentStatus.logLine(now: Long = System.currentTimeMillis()): String {
    val seconds = ((if (finishedAtEpochMillis > 0) finishedAtEpochMillis else now) - startedAtEpochMillis) / 1_000
    val cache = if (inputTokens > 0) " 缓存 ${cachedTokens * 100 / inputTokens}%" else ""
    return "${seconds}s · 工具 $toolCalls 次 · 模型 $modelCalls 次 · 输入 $inputTokens$cache · 输出 $outputTokens" +
        (if (error.isNotEmpty()) " · $error" else "")
}

/** The transcript line for a change, in the conversation's own words. */
internal fun SubagentStatus.transcriptLine(change: DebugSubagentChange): String = when (change) {
    DebugSubagentChange.STARTED -> "后台任务「$label」开始（$id）"
    DebugSubagentChange.STALLED -> "后台任务「$label」${(System.currentTimeMillis() - lastActivityEpochMillis) / 1_000} 秒没有进展" +
        (if (latest.isNotEmpty()) "，最近：${latest.take(60)}" else "")
    DebugSubagentChange.RESUMED -> "后台任务「$label」又有进展了"
    DebugSubagentChange.FINISHED -> "后台任务「$label」${state.chinese()}：${logLine()}"
}

internal fun SubagentState.chinese(): String = when (this) {
    SubagentState.RUNNING -> "进行中"
    SubagentState.STALLED -> "没有进展"
    SubagentState.COMPLETED -> "完成"
    SubagentState.FAILED -> "失败"
    SubagentState.CANCELLED -> "已取消"
    SubagentState.TIMED_OUT -> "超时停止"
}

/**
 * What a child may call. Never: questions to the user (no one is there), cards, plans and external delegation (the
 * main agent's). Device tools only when the settings allow it; otherwise their queries only. At most [MAX_CHILD_LOOKUPS]
 * pages and searches of its own (owner 2026-10-17: 开了子 agent 后用得好快): a child hunting train times opened 20
 * pages, and what pages bring in is new input every round, the part of a trip that costs (cache hits are 1/50 of it).
 * A typed child also keeps to its type's [tools]; the tool list itself stays the main agent's.
 */
internal class DebugSubagentGuard(
    private val allowDeviceControl: Boolean,
    /** A typed child's own share ([DebugSubagentKind.maxLookups]). */
    private val maxLookups: Int = MAX_CHILD_LOOKUPS,
    /** A typed child's tools ([DebugSubagentKind.tools]); null for a general child. */
    private val tools: Set<DebugSubagentToolGroup>? = null,
    private val kindId: String = "",
) : RuntimeHook {
    private val lookups = java.util.concurrent.atomic.AtomicInteger()

    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
        val group = debugSubagentToolGroup(call.toolName)
        if (tools != null && group != null && group !in tools) {
            return HookDecision.Deny(reason = "这个子 agent（$kindId）不能用「${group.label}」：用能用的工具完成，或把要做的写进结果。")
        }
        if (call.toolName in CHILD_LOOKUP_TOOLS && lookups.incrementAndGet() > maxLookups) {
            return HookDecision.Deny(reason = lookupsSpent(maxLookups))
        }
        val name = call.toolName
        val server = name.removePrefix(MCP_PREFIX).substringBefore("__").takeIf { name.startsWith(MCP_PREFIX) }.orEmpty()
        val tool = name.substringAfterLast("__")
        return when {
            name in USER_FACING_TOOLS ->
                HookDecision.Deny(reason = "A subagent cannot ask the user; decide on the likeliest reading and say so in the result.")
            server in MAIN_AGENT_SERVERS || server.startsWith(A2A_SERVER_PREFIX) ->
                HookDecision.Deny(reason = "Only the main agent shows cards, makes plans or delegates; put what should be done in the result.")
            server in DEVICE_SERVERS && !allowDeviceControl && !tool.isQuery() ->
                HookDecision.Deny(reason = "A subagent cannot operate the device; put the change you would make in the result.")
            else -> HookDecision.Allow()
        }
    }
}

/**
 * The child's input: its role, a typed child's tools and own instructions ([kind] and its text), then the task the
 * main agent wrote. A type says itself what pictures it brings, so the general picture rule is for untyped children only.
 */
internal fun debugSubagentInput(spec: SubagentSpec, kind: DebugSubagentKind? = null, instructions: String = ""): String = buildString {
    val maxLookups = kind?.maxLookups ?: MAX_CHILD_LOOKUPS
    val tools = kind?.tools
    appendLine("你是主助手派出的后台子助手，独立完成下面的任务，然后用 finish 交回结果。")
    appendLine("- 你看不到和用户的对话，也不能问用户；信息不全时按最合理的理解去做，并在结果里说明。")
    if (spec.allowDeviceControl && (tools == null || DebugSubagentToolGroup.DEVICE_CONTROL in tools)) {
        appendLine("- 只做任务要求的设备操作，不展示卡片。")
    } else {
        appendLine("- 不能操作设备、不能展示卡片，只能查询；需要操作的写进结果，由主助手决定。")
    }
    if (kind != null) appendLine("- 能用的工具：${kind.toolLabels()}；别的工具会被拒绝。")
    appendLine("- 结果要完整、简洁，写成主助手可以直接转述的要点。")
    if (kind == null || kind.searches) {
        appendLine("- 查资料先联网搜索：有 web_search 就用它，没有时服务端搜索一直可用。web_fetch 只用来打开搜索结果里出现过的详情页或你的分工里写明的网址，不要猜网址。")
    }
    if (tools == null || tools.any { it == DebugSubagentToolGroup.SEARCH || it == DebugSubagentToolGroup.FETCH }) {
        appendLine("- 打开网页和搜索一共最多 $maxLookups 次，够用就停；搜索摘要里已有的信息不用再打开详情页。互不依赖的几次查询在同一步里一起发（一次回复里调用多次），不要一步只查一个。")
    }
    if (kind == null) {
        appendLine("- 查活动、景点、展览、餐厅这类有图的东西时，挑最值得推荐的 2–3 项，用 web_fetch 打开搜索结果里它们的详情页（最多 3 次）：结果里的 image 是那一页的配图，在该项后面另起一行写「图片：https://…」。只用工具结果给出的图片地址，没有就不写，不要编造。")
    } else if (instructions.isNotBlank()) {
        appendLine()
        append("你的分工（").append(kind.id).appendLine("）：")
        appendLine(instructions.trim())
    }
    appendLine()
    append("任务（").append(spec.label).appendLine("）：")
    append(spec.task)
}

/**
 * A report as one turn: [DebugA2aReport.display] for the transcript and card, [DebugA2aReport.model] for the agent.
 * It carries every child of the batch (owner 2026-10-17: a turn per child made the agent repeat itself and say "还在查"
 * about results it had not been handed yet), and [stillWorking] names siblings that are not done.
 */
internal fun SubagentReport.toDebugReport(stillWorking: List<SubagentStatus> = emptyList(), now: Long = System.currentTimeMillis()): DebugA2aReport {
    val labels = statuses.joinToString("") { "「${it.label}」" }
    val display = when {
        statuses.size == 1 -> statuses.single().reportHeadline()
        statuses.all { it.state == SubagentState.COMPLETED } -> "后台任务${labels}都完成了"
        else -> "后台任务${labels}有消息了"
    }
    val model = buildString {
        appendLine("【后台子任务回报】你之前用 spawn_agent 委派的后台任务：")
        statuses.forEach { append(it.reportSection(now)) }
        if (stillWorking.isNotEmpty()) {
            appendLine("还在进行：${stillWorking.joinToString("") { "「${it.label}」（${it.id}）" }}，结束后会再回报，不要现在等它们。")
        } else if (statuses.none { it.state == SubagentState.STALLED }) {
            appendLine("这一批都在这里了，不用再调用 wait_agent。")
        }
        if (statuses.any { IMAGE_LINE in it.result }) appendLine(DEBUG_PICTURE_CARD_HINT)
        // 网上核实 adds to an answer already spoken: the details belong on the card, the voice keeps to what changed (on the
        // bench a 「汇总」 here outweighed the system rule and the report was read out for 35–45 s).
        if (statuses.all { it.agentType == WEB_VERIFY_KIND }) append(VERIFY_REPORT_CLOSING)
        else append("用一段话把对用户有用的部分汇总告诉用户，不要逐个复述过程，也不要用「好的」之类的话开头。")
    }
    return DebugA2aReport(display = display, model = model)
}

private const val VERIFY_REPORT_CLOSING =
    "这是对你刚才那个回答的网上核实。把核实到的细节更新到卡片上（show_card），嘴上最多说两句短话：只说会改变选择的" +
        "（哪家关了或对不上号、现在最推荐哪家），其余留在卡片上；不要再列选项，不要逐家复述，也不要用「好的」「核实回来」之类的话开头。"

private fun SubagentStatus.reportHeadline(): String = when (state) {
    SubagentState.COMPLETED -> "后台任务「$label」完成了"
    SubagentState.STALLED -> "后台任务「$label」没有进展"
    else -> "后台任务「$label」没有完成"
}

private fun SubagentStatus.reportSection(now: Long): String {
    val name = "「$label」（$id）"
    val seconds = ((if (finishedAtEpochMillis > 0) finishedAtEpochMillis else now) - startedAtEpochMillis) / 1_000
    return when (state) {
        SubagentState.COMPLETED -> "$name 已完成，用时 $seconds 秒。结果：\n$result\n"
        SubagentState.STALLED -> "$name 已经 ${(now - lastActivityEpochMillis) / 1_000} 秒没有动静" +
            (if (latest.isNotEmpty()) "（最近：$latest）" else "") + "，可以用 wait_agent 继续等，或用 cancel_agent 取消。\n"
        else -> "$name 没有完成（${state.wireName}：$error），需要的话重新委派或自己处理。\n"
    }
}

/** The key of [deliveries][DebugSubagents.deliveries]. */
internal fun SubagentStatus.deliveryKey(): String = "$parentSessionId/$id"

/**
 * [enabled]: the agent may start subagents on its own (Settings → Runtime); off hides the three tools. [searchApi]:
 * children search with DeepSeek's Search API whatever the main agent uses (owner 2026-10-17: 子 agent 搜索改用 API 接口再对比下费用).
 */
internal data class DebugSubagentSettings(
    val allowDeviceControl: Boolean = false,
    val model: String = "",
    val enabled: Boolean = true,
    val searchApi: Boolean = false,
) {
    /** [model] is `provider:model[:variant]`; blank keeps the parent run's model. */
    fun toConfig(): SubagentConfig = SubagentConfig(allowDeviceControl = allowDeviceControl, modelRef = parseModelRef(model))

    private fun parseModelRef(text: String): ModelRef? {
        val parts = text.trim().split(':').map(String::trim)
        if (parts.size < 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null
        return ModelRef(parts[0], parts[1], parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
    }
}

internal fun loadDebugSubagentSettings(context: Context): DebugSubagentSettings {
    val prefs = context.getSharedPreferences(SUBAGENT_PREFS, Context.MODE_PRIVATE)
    return DebugSubagentSettings(
        allowDeviceControl = prefs.getBoolean(KEY_DEVICE_CONTROL, false),
        model = prefs.getString(KEY_MODEL, "").orEmpty(),
        enabled = prefs.getBoolean(KEY_ENABLED, true),
        searchApi = prefs.getBoolean(KEY_SEARCH_API, false),
    )
}

internal fun saveDebugSubagentSettings(context: Context, settings: DebugSubagentSettings) {
    context.getSharedPreferences(SUBAGENT_PREFS, Context.MODE_PRIVATE).edit()
        .putBoolean(KEY_DEVICE_CONTROL, settings.allowDeviceControl)
        .putString(KEY_MODEL, settings.model)
        .putBoolean(KEY_ENABLED, settings.enabled)
        .putBoolean(KEY_SEARCH_API, settings.searchApi)
        .apply()
    DebugSubagents.applySettings(settings)
}

/** With subagents switched off the three tools are switched off too, whatever the basic tools page says. */
internal fun List<DebugBasicTool>.withSubagentSwitch(enabled: Boolean): List<DebugBasicTool> =
    if (enabled) this else map { if (it.name in DEBUG_SUBAGENT_TOOL_NAMES) it.copy(enabled = false) else it }

internal val DEBUG_SUBAGENT_TOOL_NAMES = setOf("spawn_agent", "wait_agent", "cancel_agent")

private val CHILD_LOOKUP_TOOLS = setOf("web_fetch", "web_search")

/**
 * The kind of tool [toolName] is, for a type's tools; null for what every child has (finish, search_tools, the subagent
 * tools) and for the main agent's own, which [DebugSubagentGuard] refuses anyway.
 */
internal fun debugSubagentToolGroup(toolName: String): DebugSubagentToolGroup? {
    when (toolName) {
        "web_search" -> return DebugSubagentToolGroup.SEARCH
        "web_fetch" -> return DebugSubagentToolGroup.FETCH
        "list_dir", "read_file", "grep" -> return DebugSubagentToolGroup.READ
        "write_file", "edit_file" -> return DebugSubagentToolGroup.WRITE
        "update_todo" -> return DebugSubagentToolGroup.TODO
    }
    if (!toolName.startsWith(MCP_PREFIX)) return null
    val server = toolName.removePrefix(MCP_PREFIX).substringBefore("__")
    return when {
        server in MAIN_AGENT_SERVERS || server.startsWith(A2A_SERVER_PREFIX) -> null
        server in DEVICE_SERVERS -> if (toolName.substringAfterLast("__").isQuery()) DebugSubagentToolGroup.DEVICE_QUERY else DebugSubagentToolGroup.DEVICE_CONTROL
        else -> DebugSubagentToolGroup.SERVICES
    }
}
internal const val MAX_CHILD_LOOKUPS = 8

private fun lookupsSpent(max: Int) = "已经查了 $max 次网页和搜索，不再查了：用已有的结果调用 finish，查不到的写「待确认」。"

private const val IMAGE_LINE = "图片："
/** 图文 (owner 2026-10-17): a subagent's 「图片：https://…」 lines go into the summary card. */
internal const val DEBUG_PICTURE_CARD_HINT =
    "子 agent 的结果里有「图片：https://…」时，汇总时先用一次 show_card 把这一批结果做成一张图文卡片（卡片一次只显示一张，不同城市或主题用 " +
        "items 的 group 分组）：每项 image 填它对应的图片，头图 image 用最有代表性的一张，没有图片的项照样列出；然后 finish 只说要点。"

private const val TAG = "DebugSubagents"
private const val MAX_BOARD_ROWS = 8
/** In the parent conversation's event-log folder; `tools/session_cache.py --children` reads it. */
internal const val SUBAGENT_RECORDS = "subagents.jsonl"
/** A late result waiting for its turn, in [DebugSubagents.deliveries]. */
internal const val REPORT_QUEUED = 0L
private const val MAX_DELIVERY_MARKS = 64
private const val MCP_PREFIX = "mcp__"
private val USER_FACING_TOOLS = setOf("ask", "ask_async")
private val MAIN_AGENT_SERVERS = setOf("car_ui", "plans")
private const val A2A_SERVER_PREFIX = "experimental_a2a"
private val DEVICE_SERVERS = setOf(DEBUG_MCP_PROVIDER_TOOLS_JSON)
/** Whole words only ("TemperatureAdjust" holds "reAd" but no word "read"): split at underscores and capitals. */
private fun String.isQuery(): Boolean =
    split('_').flatMap { it.split(CAMEL_BOUNDARY) }.any { it.lowercase() in QUERY_WORDS }

private val CAMEL_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")
private val QUERY_WORDS = setOf("query", "get", "read", "list", "search", "info", "status", "check")
private const val SUBAGENT_PREFS = "debug_subagents"
private const val KEY_SEARCH_API = "search_api"
private const val KEY_DEVICE_CONTROL = "allow_device_control"
private const val KEY_MODEL = "model"
private const val KEY_ENABLED = "enabled"
