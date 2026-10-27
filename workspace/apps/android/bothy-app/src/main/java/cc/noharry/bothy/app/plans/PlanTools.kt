package cc.noharry.bothy.app.plans
import android.content.Context
import cc.noharry.agent.kernel.api.ContextStability
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.RuntimeContextBlock
import cc.noharry.agent.kernel.api.RuntimeContextContribution
import cc.noharry.agent.kernel.api.RuntimeContextDatum
import cc.noharry.agent.kernel.api.RuntimeContextDatumKind
import cc.noharry.agent.kernel.api.RuntimeContextProvider
import cc.noharry.agent.kernel.api.RuntimeContextRequest
import cc.noharry.agent.kernel.api.RuntimeContextScalar
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolCallTrace
import cc.noharry.agent.kernel.api.ToolCatalogCacheIdentity
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolPrefetchProvider
import cc.noharry.agent.kernel.api.ToolPrefetchRequest
import cc.noharry.agent.kernel.api.ToolPrefetchTrace
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.bothy.app.a2a.describe
import cc.noharry.bothy.app.console.singleLine
import cc.noharry.bothy.app.mcp.McpResource
import cc.noharry.bothy.app.mcp.McpResourceCatalog
import cc.noharry.bothy.app.mcp.McpToolCatalogs
import cc.noharry.bothy.app.mcp.loadMcpServers
import cc.noharry.bothy.app.mcp.readMcpResourceAll
import cc.noharry.bothy.app.mcp.readMcpTool
import cc.noharry.bothy.app.mcp.toolReading
import cc.noharry.bothy.app.model.describe
import cc.noharry.bothy.app.tools.SchemaValidation
import cc.noharry.bothy.app.tools.isTransaction
import cc.noharry.bothy.app.tools.validateInputSchema
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** Where the plan tool writes and what it can read; tests give it a book of their own. */
internal open class PlanHost(private val context: Context?) {
    open fun <T> write(change: (PlanBook) -> T): T = PlanRunner.write(requireNotNull(context), change)

    open fun plans(): List<Plan> = PlanRunner.book(requireNotNull(context)).plans()

    /** What the MCP servers loaded now offer to watch. */
    open suspend fun resources(): List<McpResource> = McpResourceCatalog.resources(requireNotNull(context))

    open suspend fun readings(server: String, uri: String): List<JsonObject>? = readMcpResourceAll(requireNotNull(context), server, uri)

    /** One call of [server]'s read-only [tool] as a watch reads it. */
    open suspend fun toolReading(server: String, tool: String, arguments: JsonObject): JsonObject? =
        readMcpTool(requireNotNull(context), server, tool, arguments)

    /** [server]'s tools as loaded now; none while it is switched off. */
    open suspend fun tools(server: String): List<ToolDescriptor> {
        val app = requireNotNull(context)
        val config = loadMcpServers(app).firstOrNull { it.id == server && it.enabled } ?: return emptyList()
        return McpToolCatalogs.tools(app, config)
    }

    open fun policy(): AutomationPolicy = AutomationPolicy.load(requireNotNull(context))

    /** The configured server [raw] names: its id, or the id as the runtime shows it in 「mcp__<server>__」. */
    open fun serverId(raw: String): String {
        val ids = loadMcpServers(requireNotNull(context)).map { it.id }
        return ids.firstOrNull { it == raw } ?: ids.firstOrNull { it.replace(NON_NAME, "_") == raw } ?: raw
    }

    open fun now(): Long = System.currentTimeMillis()

    open fun zone(): ZoneId = PlanRunner.zone()
}

/**
 * `plan_manage`: 小博 makes, changes, pauses, resumes, deletes and lists plans (the console's 计划 tab shows them). The
 * host turns the model's 「两小时后」「每个工作日 8 点」 into times ([resolvePlanWhen]) and says back what it worked out,
 * so the model never computes a date. Words about reminders and times ([PLAN_CUES]) offer it on the first round.
 */
@OptIn(ExperimentalAgentRuntimeApi::class)
internal class PlanToolProvider(private val host: PlanHost) : McpClientProvider, ToolPrefetchProvider, ToolCatalogCacheIdentity {
    constructor(context: Context) : this(PlanHost(context.applicationContext))

    override val serverId: String = PLAN_SERVER_ID
    override val displayName: String = "计划"
    override val catalogCacheIdentity: String = "bothy-app:$PLAN_SERVER_ID:v1"
    override val providerConcurrency: ProviderConcurrencyDeclaration = ProviderConcurrencyDeclaration.threadSafe()
    override val remotePrefetchEnabled: Boolean get() = false

    override suspend fun listTools(): List<ToolDescriptor> = listOf(
        ToolDescriptor(name = PLAN_TOOL, title = "安排计划", description = PLAN_DESCRIPTION, inputSchemaJson = PLAN_SCHEMA),
    )

    override fun localToolNames(request: ToolPrefetchRequest): List<String> =
        if (PLAN_CUES.any { it in request.input }) listOf(PLAN_TOOL) else emptyList()

    override suspend fun prefetchTools(request: ToolPrefetchRequest, trace: ToolPrefetchTrace): List<String> = emptyList()

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
        if (toolName != PLAN_TOOL) return failure("UNKNOWN_TOOL", "Unknown plan tool: $toolName", validation = false)
        val invalid = validateInputSchema(PLAN_SCHEMA, argumentsJson) as? SchemaValidation.Invalid
        if (invalid != null) return failure(invalid.code, invalid.message, validation = false)
        val args = Json.parseToJsonElement(argumentsJson) as JsonObject
        val result = runCatching {
            when (args.text("action")) {
                "create" -> create(args)
                "update" -> update(args)
                "pause" -> toggle(args, on = false)
                "resume" -> toggle(args, on = true)
                "delete" -> delete(args)
                "signals" -> planSignalsJson(host.resources(), args.text("query"))
                "inspect" -> inspect(args)
                else -> list()
            }
        }
        return result.fold(
            onSuccess = { ToolCallResult(success = true, resultJson = it.toString(), trace = ToolCallTrace(validationOk = true, validationCode = "OK")) },
            onFailure = { failure("NOT_SAVED", it.message ?: it.javaClass.simpleName, validation = true) },
        )
    }

    private suspend fun create(args: JsonObject): JsonObject {
        val actions = planActionsOf(args, host.policy(), host::tools, host::serverId).orEmpty()
        val trigger = triggerOf(args, actions.isNotEmpty()) ?: throw IllegalArgumentException("需要 when（时间）或 watch（服务的状态）其中一个")
        requireWatchable(trigger)
        val plan = host.write {
            it.add(args.text("title").orEmpty(), args.text("message").orEmpty(), args.text("instruction"), trigger, args.text("said"), actions)
        }.getOrThrow()
        val retell = when {
            plan.actions.isNotEmpty() -> "什么时候自动做什么"
            plan.instruction != null -> "什么时候去办什么"
            else -> "什么时候提醒什么"
        }
        return planJson(plan, "已安排。用一句话向用户复述：$retell。")
    }

    private suspend fun update(args: JsonObject): JsonObject {
        val id = args.text("id") ?: throw IllegalArgumentException("update 需要 id")
        val actions = planActionsOf(args, host.policy(), host::tools, host::serverId)
        val old = host.plans().firstOrNull { it.id == id } ?: throw IllegalArgumentException("没有这个计划（$id）")
        val trigger = triggerOf(args, (actions ?: old.actions).isNotEmpty())?.also { requireWatchable(it) }
        val now = host.now()
        val plan = host.write { book ->
            book.update(id) { current ->
                val retimed = trigger?.let { current.retimed(it, now) } ?: current
                retimed.copy(
                    title = args.text("title") ?: retimed.title,
                    message = args.text("message") ?: retimed.message,
                    instruction = args.text("instruction") ?: retimed.instruction,
                    actions = actions ?: retimed.actions,
                )
            }
        } ?: throw IllegalArgumentException("没有这个计划（$id）")
        return planJson(plan, "已修改。用一句话向用户复述改后的安排。")
    }

    private suspend fun inspect(args: JsonObject): JsonObject {
        val server = args.text("server") ?: throw IllegalArgumentException("inspect 需要 server")
        args.text("tool")?.let(::serverToolName)?.let { tool ->
            requireWatchableTool(server, tool)
            val arguments = args["arguments"] as? JsonObject ?: JsonObject(emptyMap())
            return planInspectJson(server, "", host.toolReading(server, tool, arguments)?.let(::listOf), tool = tool)
        }
        val uri = args.text("uri") ?: throw IllegalArgumentException("inspect 需要 uri（用 signals 查）或 tool")
        return planInspectJson(server, uri, host.readings(server, uri))
    }

    private fun toggle(args: JsonObject, on: Boolean): JsonObject {
        val id = args.text("id") ?: throw IllegalArgumentException("需要 id")
        val now = host.now()
        val plan = host.write { book ->
            val old = book.plans().firstOrNull { it.id == id } ?: throw IllegalArgumentException("没有这个计划（$id）")
            require(!on || old.end == null) { "这个计划${old.end?.label}了；要重新安排就用 update 改时间" }
            require(!on || (old.trigger as? PlanTrigger.Once)?.let { it.atMillis > now } != false) {
                "这个计划的时间在暂停期间已经过了；要重新安排就用 update 改时间"
            }
            book.update(id) { if (on) it.resumed(now) else it.copy(enabled = false) }
        } ?: throw IllegalArgumentException("没有这个计划（$id）")
        return planJson(plan, if (on) "已恢复。" else "已暂停，用户可以在「计划」页再打开。")
    }

    private fun delete(args: JsonObject): JsonObject {
        val id = args.text("id") ?: throw IllegalArgumentException("delete 需要 id")
        val plan = host.write { it.remove(id) } ?: throw IllegalArgumentException("没有这个计划（$id）")
        return planJson(plan, "已删除。")
    }

    private fun list(): JsonObject {
        val plans = host.plans()
        return buildJsonObject {
            put("ok", true)
            put("plans", buildJsonArray { plans.forEach { add(planFields(it)) } })
        }
    }

    private fun triggerOf(args: JsonObject, automates: Boolean): PlanTrigger? {
        val time = args["when"] as? JsonObject
        val watch = args["watch"] as? JsonObject
        require(time == null || watch == null) { "when 和 watch 只能给一个" }
        time?.let { return resolvePlanWhen(planWhen(it), host.now(), host.zone()).getOrThrow() }
        // An automation, and a value the device changes into, go on every time; a number reminds once unless asked.
        watch?.let { val ask = planWatchAsk(it); return resolvePlanWatch(ask, automates || WatchOp.byId(ask.op)?.numeric == false).getOrThrow() }
        return null
    }

    /** A watch on a resource, or a read-only tool, that a loaded server offers now; nothing else could ever come. */
    private suspend fun requireWatchable(trigger: PlanTrigger) {
        val watch = trigger.watch() ?: return
        watch.tool?.let { return requireWatchableTool(watch.server, it) }
        require(host.resources().any { it.server == watch.server && it.uri == watch.uri }) {
            "${watch.server} 现在没有 ${watch.uri} 这个资源（服务没开，或者名字不对）：用 signals 查有哪些"
        }
    }

    /**
     * A tool a watch may call on its own, again and again: one [server] lists now that only reads (the server marks it
     * read-only, or its name says it queries), never an order or payment, and not one the automation policy denies.
     */
    private suspend fun requireWatchableTool(server: String, tool: String) {
        val descriptor = host.tools(server).firstOrNull { it.name == tool }
            ?: throw IllegalArgumentException("$server 现在没有 $tool 这个工具（服务没开，或者名字不对）")
        require(!isTransaction(tool) && host.policy().denied(tool) == null) { "$tool 会下单、付款或改东西，不能定时调用" }
        require(descriptor.effectKind == READ_ONLY_EFFECT || tool.readsOnly()) { "$tool 不是只读的查询工具，不能定时调用" }
    }

    private fun planJson(plan: Plan, message: String): JsonObject = buildJsonObject {
        put("ok", true)
        planFields(plan).forEach { (key, value) -> put(key, value) }
        put("message", message)
    }

    private fun planFields(plan: Plan): JsonObject = buildJsonObject {
        val now = host.now()
        put("id", plan.id)
        put("title", plan.title)
        put("when", plan.trigger.describe(now, host.zone()))
        put("status", planStatus(plan))
        plan.nextDueMillis(now, host.zone())?.let { put("next", describeMoment(it, now, host.zone())) }
        if (plan.message.isNotBlank()) put("say", plan.message)
        plan.instruction?.let { put("do", it) }
        if (plan.actions.isNotEmpty()) put("actions", plan.actions.joinToString("、") { it.label })
        plan.trigger.watch()?.let { put("watching", "${it.server} ${it.sourceLabel} ${it.field}") }
    }

    private fun failure(code: String, message: String, validation: Boolean): ToolCallResult = ToolCallResult(
        success = false,
        resultJson = buildJsonObject {
            put("ok", false)
            put("code", code)
            put("message", message)
        }.toString(),
        trace = ToolCallTrace(validationOk = validation, validationCode = code, validationMessage = message.takeIf { !validation }),
    )
}

/** 「进行中」「已暂停」「已完成」「已错过」. */
internal fun planStatus(plan: Plan): String = plan.end?.label ?: if (plan.enabled) "进行中" else "已暂停"

private fun JsonObject.text(name: String): String? = argText(name)

/**
 * Today's date and the plans still going, as facts before each round: the model resolves 「下周三」 from the date and
 * finds a plan's id for 「取消明早的提醒」. Registered before 记忆's, so these [MAX_PLAN_FACTS] facts always fit the
 * runtime's 32. The clock is the request's reference time, not the wall clock.
 */
@OptIn(ExperimentalAgentRuntimeApi::class)
internal class PlanContext(
    private val plans: () -> List<Plan>,
    private val zone: () -> ZoneId = PlanRunner.zone,
) : RuntimeContextProvider {
    constructor(context: Context) : this({ PlanRunner.book(context.applicationContext).plans() })

    override val id: String = PLAN_SERVER_ID

    override suspend fun provide(request: RuntimeContextRequest): RuntimeContextContribution {
        val now = request.referenceTimeEpochMillis
        val date = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate()
        val today = RuntimeContextDatum("today", RuntimeContextDatumKind.FACT, "日期", "today", RuntimeContextScalar.Text("$date ${PLAN_WEEKDAYS[date.dayOfWeek.value - 1]}"))
        val going = plans().filter { it.end == null }
            .sortedWith(compareBy<Plan>({ !it.enabled }, { it.nextDueMillis(now, zone()) ?: Long.MAX_VALUE }))
            .take(MAX_PLAN_FACTS - 1)
        val data = going.map { plan ->
            val parts = listOfNotNull(
                plan.title, plan.trigger.describe(now, zone()), planStatus(plan), plan.instruction?.let { "办：$it" },
                plan.actions.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "做：") { it.label },
            )
            RuntimeContextDatum(plan.id, RuntimeContextDatumKind.FACT, "计划", "plan", RuntimeContextScalar.Text(singleLine(parts.joinToString("｜")).take(PLAN_FACT_CHARS)))
        }
        val blocks = listOfNotNull(
            RuntimeContextBlock("plans.today", ContextStability.STABLE, listOf(today), digest(date.toString())),
            data.takeIf { it.isNotEmpty() }?.let { RuntimeContextBlock("plans.list", ContextStability.STABLE, it, digest(it.joinToString { d -> d.id + d.value })) },
        )
        return RuntimeContextContribution(id, blocks, fingerprint = digest(blocks.joinToString { it.payloadDigest }))
    }
}

private fun digest(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(DIGEST_CHARS)

/** Words that ask for a reminder, a timed task or a watch on the device. */
internal val PLAN_CUES = listOf(
    "提醒", "闹钟", "定时", "计划", "每天", "每周", "工作日", "周末", "分钟后", "小时后", "点叫", "叫醒", "明早", "明天早上",
    "到时候", "超过", "低于", "降价", "最低价", "便宜到", "时候自动", "时候帮我", "时候提醒", "时候把", "时候就", "每次",
    "自动化",
)
private val NON_NAME = Regex("[^A-Za-z0-9_-]")
internal const val PLAN_TOOL = "plan_manage"
/** Today plus up to six plans: what is left of the runtime's 32 context facts after 记忆's 24, with one to spare. */
internal const val MAX_PLAN_FACTS = 7
private const val PLAN_SERVER_ID = "plans"
/** Under the renderer's 512 bytes per value (3 bytes per Chinese character). */
private const val PLAN_FACT_CHARS = 150
private const val DIGEST_CHARS = 16
private val PLAN_DESCRIPTION = """
    安排、修改、暂停（pause）、恢复（resume）、删除、列出（list）计划：定时提醒、定时要办的事，以及某个服务的状态变化时提醒、
    去办或自动执行（自动化规则，比如「深圳飞成都 11 月 7 日的最低价低于 800 元时提醒我」）。用户在首页「计划」页能看到、开关和
    删除。时间用 when：「两小时后」写 in_minutes=120；具体时间写 time=HH:mm（可加 date=YYYY-MM-DD；不写 date 就是今天，已经
    过了就是明天）；重复加 repeat=daily/weekdays/weekends/weekly（weekly 再加 days，1 是周一）。不要自己换算日期，交给工具算。
    状态变化用 watch，能监听什么不是固定的，看当前已加载的服务：有资源的用 uri（先用 action=signals 查，可加 query 关键词）；
    只有工具的用 tool 加 arguments（服务的只读查询工具，比如机票搜索；先用 search_tools 找到并看清参数）。写之前用 inspect
    读一次看字段。watch 写 server、uri 或 tool、field（读数里的字段，嵌套用点，列表用序号：itineraries.0.price），
    op=above/below（数字）、equals/not_equals/contains（值）或 changes（任何变化），value；只看多个对象里的一个用 where；
    事件 mode=each，状态用默认的 enter（变成这个值时触发一次）；调用工具的 watch 用 poll_seconds 定多久查一次（默认 30 分钟，
    最快 5 分钟）；text 写给人看的说法（「最低价低于 800 元时」）。自动执行用 actions：每项是一个已加载服务的工具（先用
    search_tools 找到并看清参数），写 tool、arguments，server 不写就看 tool 名里的 mcp__服务__，label 写成「打开日历」这样的
    短语；带 actions 或 instruction 的计划先向用户复述并确认，再创建。watch 的计划用户没说清以后每次都要还是只这一次时，复述
    时一起问，按回答写 watch 的 repeat（true 每次、false 一次），不要自己定。title 是简短的名字；message 是触发时原样念给用户的
    完整一句话；instruction 是触发时让小博去办的事。今天的日期和进行中的计划在 runtime_context 里（predicate 为 today 和
    plan，id 用于 update、pause、resume、delete）。结果里的 when 和 next 是算好的说法，照着用一句话复述。不要答应提醒或
    自动执行却不创建计划。推荐的去处、演出或活动离出发还早时（改天，或几个小时以后），回复结尾问要不要到点提醒你出发（连了
    云端助手的也可以问要不要发给它），不要问现在导航过去；马上要去的才问导航。
""".trimIndent().replace("\n", "")
private const val PLAN_SCHEMA =
    """{"type":"object","properties":{""" +
        """"action":{"type":"string","enum":["create","update","pause","resume","delete","list","signals","inspect"]},""" +
        """"id":{"type":"string","description":"要改的计划 id"},""" +
        """"query":{"type":"string","description":"signals 的关键词，比如「天气 航班」"},""" +
        """"server":{"type":"string","description":"inspect 的服务"},""" +
        """"uri":{"type":"string","description":"inspect 的资源 uri"},""" +
        """"tool":{"type":"string","description":"inspect 的只读工具"},"arguments":{"type":"object","description":"inspect 时工具的参数"},""" +
        """"title":{"type":"string","maxLength":20,"description":"简短的名字，比如「拿快递」"},""" +
        """"message":{"type":"string","maxLength":120,"description":"触发时念给用户的话"},""" +
        """"instruction":{"type":"string","maxLength":200,"description":"触发时让小博去办的事"},""" +
        """"when":{"type":"object","properties":{"in_minutes":{"type":"integer","minimum":1,"maximum":10080},""" +
        """"date":{"type":"string","description":"YYYY-MM-DD"},"time":{"type":"string","description":"HH:mm"},""" +
        """"repeat":{"type":"string","enum":["none","daily","weekdays","weekends","weekly"]},""" +
        """"days":{"type":"array","items":{"type":"integer","minimum":1,"maximum":7}}},"additionalProperties":false},""" +
        """"watch":{"type":"object","properties":{"server":{"type":"string"},"uri":{"type":"string","description":"资源；和 tool 二选一"},""" +
        """"tool":{"type":"string","description":"只读查询工具；和 uri 二选一"},"arguments":{"type":"object","description":"tool 的参数"},""" +
        """"field":{"type":"string","description":"读数里的字段，嵌套用点，列表用序号"},""" +
        """"op":{"type":"string","enum":["above","below","equals","not_equals","contains","changes"]},"value":{},""" +
        """"where":{"type":"object","description":"只看这些字段等于这些值的读数"},"mode":{"type":"string","enum":["enter","each"]},""" +
        """"text":{"type":"string","maxLength":40,"description":"给人看的说法，比如「最低价低于 800 元时」"},""" +
        """"hold_seconds":{"type":"integer","minimum":0,"maximum":600},"repeat":{"type":"boolean"},""" +
        """"poll_seconds":{"type":"integer","minimum":1,"maximum":3600}},"required":["server","field","op"],"additionalProperties":false},""" +
        """"actions":{"type":"array","maxItems":5,"items":{"type":"object","properties":{"server":{"type":"string"},""" +
        """"tool":{"type":"string"},"arguments":{"type":"object"},"label":{"type":"string","maxLength":20}},"required":["tool"]}},""" +
        """"said":{"type":"string","maxLength":200,"description":"用户的原话"}},""" +
        """"required":["action"],"additionalProperties":false}"""

/** Whole words only ("TemperatureAdjust" holds "reAd" but no word "read"): split at underscores, dashes and capitals. */
private fun String.readsOnly(): Boolean =
    split('_', '-', '.').flatMap { it.split(CAMEL_BOUNDARY) }.any { it.lowercase() in QUERY_WORDS }

private val CAMEL_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")
private val QUERY_WORDS = setOf("query", "get", "read", "list", "search", "info", "status", "check", "find", "lookup", "weather")
/** The MCP server's own `readOnlyHint` (McpToolProvider), as the kernel's effect kind. */
private const val READ_ONLY_EFFECT = "READ_ONLY"
