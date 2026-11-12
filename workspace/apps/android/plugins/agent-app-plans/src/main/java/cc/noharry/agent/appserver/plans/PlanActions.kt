package cc.noharry.agent.appserver.plans
import android.content.Context
import android.util.Log
import cc.noharry.agent.appserver.console.singleLine
import cc.noharry.agent.appserver.mcp.mcpResourceClient
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.Plan
import cc.noharry.agent.appserver.protocol.PlanAction
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.text
import cc.noharry.agent.appserver.runtime.modelText
import cc.noharry.agent.appserver.tools.SchemaValidation
import cc.noharry.agent.appserver.tools.validateInputSchema
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.mcp.McpRpcResponse
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** A rule of [AutomationPolicy] about one tool pattern, with the reason the user hears. */
data class PolicyRule(
    val tool: String,
    val why: String = "",
    /** [why] in English, for a persona that does not speak Chinese; empty falls back to [why]. */
    val whyEn: String = "",
)

/**
 * What an automation may run on its own: the rules follow whatever MCP servers and skills are loaded, nothing fixed in
 * code. Any tool of a loaded server may be an action unless [deny] names it. It is the APK's `automation-policy.json`,
 * tightened by one in the app's private files (outside the agent's workspace, so the model cannot write it), which can
 * only add rules. [blockAll] when the APK's cannot be read: then nothing runs on its own.
 */
internal data class AutomationPolicy(
    val deny: List<PolicyRule>,
    val blockAll: Boolean = false,
) {
    fun denied(tool: String): PolicyRule? =
        if (blockAll) PolicyRule("*", modelText("自动执行的策略读不到", "The automation policy cannot be read")) else deny.firstOrNull { globMatches(it.tool, tool) }

    /** This policy with [extra]'s rules added: an override can only tighten. */
    fun tightenedBy(extra: AutomationPolicy): AutomationPolicy = copy(deny = deny + extra.deny, blockAll = blockAll || extra.blockAll)

    companion object {
        fun load(context: Context): AutomationPolicy {
            val shipped = runCatching { parse(context.assets.open(POLICY_FILE).bufferedReader().use { it.readText() }) }
                .onFailure { Log.w(PLAN_LOG_TAG, "shipped policy unreadable: ${it.javaClass.simpleName}") }
                .getOrNull() ?: return AutomationPolicy(emptyList(), blockAll = true)
            val override = File(context.filesDir, POLICY_FILE).takeIf { it.isFile } ?: return shipped
            return runCatching { shipped.tightenedBy(parse(override.readText())) }
                .onFailure { Log.w(PLAN_LOG_TAG, "policy override ignored: ${it.javaClass.simpleName}") }
                .getOrDefault(shipped)
        }

        fun parse(text: String): AutomationPolicy {
            val root = Json.parseToJsonElement(text) as JsonObject
            val deny = (root["deny"] as? JsonArray).orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                PolicyRule(tool = item.text("tool") ?: return@mapNotNull null, why = item.text("why").orEmpty(), whyEn = item.text("why_en").orEmpty())
            }
            return AutomationPolicy(deny)
        }
    }
}

/** `*` stands for any run of characters: 「*message_send*」. */
internal fun globMatches(pattern: String, name: String): Boolean =
    Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) }).matches(name)

/** [raw] without the runtime's 「mcp__server__」: the name as the server's catalog may have it. */
internal fun planToolName(raw: String): String = raw.trim().substringAfterLast("__")

/** [raw]'s entry in [catalog]: its exact name, else the same name with dots as underscores (a capability id). */
internal fun planCatalogTool(raw: String, catalog: List<ToolDescriptor>): ToolDescriptor? {
    val name = planToolName(raw)
    return catalog.firstOrNull { it.name == name } ?: catalog.firstOrNull { it.name.replace('.', '_') == name.replace('.', '_') }
}

/**
 * [tool] with [arguments] as a plan action on [server], checked against that server's catalog as loaded now ([catalog]):
 * the tool must exist there, the policy must not deny it, and the arguments must fit its schema. [label] is the
 * model's words for it, else the tool's title.
 */
internal fun resolvePlanAction(
    server: String,
    rawTool: String?,
    arguments: JsonObject?,
    label: String?,
    catalog: List<ToolDescriptor>,
    policy: AutomationPolicy,
): PlanAction {
    val raw = rawTool ?: throw IllegalArgumentException(modelText("actions 里每一项都要有 tool", "Each item of actions needs tool"))
    fun refuse(tool: String) = policy.denied(tool)?.let { rule ->
        throw IllegalArgumentException(
            modelText("计划里不能自动执行 $tool", "A plan cannot run $tool") + modelText(rule.why, rule.whyEn.ifEmpty { rule.why }).takeIf(String::isNotEmpty)?.let { modelText("（$it）", " ($it)") }.orEmpty(),
        )
    }
    refuse(planToolName(raw).replace('.', '_'))
    val descriptor = planCatalogTool(raw, catalog)
        ?: throw IllegalArgumentException(
            modelText(
                "$server 现在没有 ${planToolName(raw)} 这个工具，先用 search_tools 找到正确的名字和所在的服务",
                "$server has no tool ${planToolName(raw)} now: find the right name and its service with search_tools first",
            ),
        )
    val tool = descriptor.name
    refuse(tool)
    val args = arguments ?: JsonObject(emptyMap())
    (validateInputSchema(descriptor.inputSchemaJson, args.toString()) as? SchemaValidation.Invalid)?.let {
        throw IllegalArgumentException(modelText("$tool 的参数不对：${it.message}", "$tool's arguments are wrong: ${it.message}"))
    }
    val words = label?.let(::singleLine)?.takeIf(String::isNotEmpty)?.take(MAX_ACTION_LABEL_CHARS)
    return PlanAction(server, tool, args, words ?: descriptor.title?.takeIf(String::isNotBlank) ?: tool)
}

/**
 * Runs a plan's [actions] in order through [call] (an MCP `tools/call`), each once. The policy is asked again (one
 * tightened after the rule was made still holds), and in [mode], the person's approval mode of the moment, "deny all"
 * runs none: it refuses whatever would ask, and actions act (their yes was given when the plan was made). Returns what
 * came of each action, for 最近触发.
 */
internal suspend fun runPlanActions(
    actions: List<PlanAction>,
    policy: AutomationPolicy,
    mode: ConfirmationMode = ConfirmationMode.ASK_EVERY_TIME,
    call: suspend (PlanAction) -> Finding?,
): List<Finding> = actions.map { action ->
    if (mode == ConfirmationMode.DENY_ALL || policy.denied(action.tool) != null) return@map finding(FindingCode.PLANS_ACTION_DENIED, action.label)
    call(action)?.let { problem -> finding(FindingCode.PLANS_ACTION_FAILED, action.label, more = listOf(problem)) }
        ?: finding(FindingCode.PLANS_ACTION_DONE, action.label)
}

/**
 * The failure in a `tools/call` answer, or null when it went through (no error, no isError, status code 0 if any); the
 * server's own message when it gives one.
 */
internal fun planActionProblem(response: McpRpcResponse): Finding? {
    if (!response.ok) return response.errorMessage?.let(::text) ?: finding(FindingCode.PLANS_PROBLEM_NO_ANSWER)
    val data = response.dataJson ?: return finding(FindingCode.PLANS_PROBLEM_NO_RESULT)
    (data["error"] as? JsonObject)?.let { return it.text("message")?.let(::text) ?: finding(FindingCode.PLANS_PROBLEM_ERROR) }
    val result = data["result"] as? JsonObject ?: return finding(FindingCode.PLANS_PROBLEM_NO_RESULT)
    val status = (result["structuredContent"] as? JsonObject)?.get("status") as? JsonObject
    val code = (status?.get("code") as? JsonPrimitive)?.intOrNull
    val failed = (result["isError"] as? JsonPrimitive)?.booleanOrNull == true || (code != null && code != 0)
    return if (failed) status?.text("message")?.let(::text) ?: finding(FindingCode.PLANS_PROBLEM_REFUSED) else null
}

/** Runs plans' actions on their servers, off the alarm's and the watcher's threads. */
internal object PlanActionRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** [plan]'s actions in the approval [mode] of the moment, then [done] with what came of each, for 最近触发. */
    fun run(context: Context, plan: Plan, mode: ConfirmationMode, done: (List<Finding>) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            val policy = AutomationPolicy.load(app)
            val lines = runPlanActions(
                plan.actions,
                policy,
                mode,
                call = call@{ action ->
                    val client = mcpResourceClient(app, action.server)
                        ?: return@call finding(FindingCode.PLANS_PROBLEM_OFFLINE, action.server)
                    planActionProblem(client.sendRequest(TOOLS_CALL, buildJsonObject { put("name", action.tool); put("arguments", action.arguments) }))
                },
            )
            Log.i(PLAN_LOG_TAG, "ran ${plan.actions.size} actions for ${plan.id}")
            done(lines)
        }
    }
}

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private const val TOOLS_CALL = "tools/call"
private const val POLICY_FILE = "automation-policy.json"
private const val MAX_ACTION_LABEL_CHARS = 20
internal const val MAX_PLAN_ACTIONS = 5
