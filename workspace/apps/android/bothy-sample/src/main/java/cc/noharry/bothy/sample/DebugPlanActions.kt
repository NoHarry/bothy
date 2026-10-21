package cc.noharry.bothy.sample

import android.content.Context
import android.util.Log
import cc.noharry.bothy.kernel.api.ToolDescriptor
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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** A control a plan runs itself when it comes due: a tool of a loaded MCP server, its arguments, and how it reads. */
internal data class DebugPlanAction(val server: String, val tool: String, val arguments: JsonObject, val label: String)

/** A rule of [DebugAutomationPolicy] about one tool pattern, with the reason the user hears. */
internal data class DebugPolicyRule(
    val tool: String,
    val why: String = "",
)

/**
 * What an automation may run on its own: the rules follow whatever MCP servers and skills are loaded, nothing fixed in
 * code. Any tool of a loaded server may be an action unless [deny] names it. It is the APK's `automation-policy.json`,
 * tightened by one in the app's private files (outside the agent's workspace, so the model cannot write it), which can
 * only add rules. [blockAll] when the APK's cannot be read: then nothing runs on its own.
 */
internal data class DebugAutomationPolicy(
    val deny: List<DebugPolicyRule>,
    val blockAll: Boolean = false,
) {
    fun denied(tool: String): DebugPolicyRule? =
        if (blockAll) DebugPolicyRule("*", "自动执行的策略读不到") else deny.firstOrNull { globMatches(it.tool, tool) }

    /** This policy with [extra]'s rules added: an override can only tighten. */
    fun tightenedBy(extra: DebugAutomationPolicy): DebugAutomationPolicy = copy(deny = deny + extra.deny, blockAll = blockAll || extra.blockAll)

    companion object {
        fun load(context: Context): DebugAutomationPolicy {
            val shipped = runCatching { parse(context.assets.open(POLICY_FILE).bufferedReader().use { it.readText() }) }
                .onFailure { Log.w(PLAN_LOG_TAG, "shipped policy unreadable: ${it.javaClass.simpleName}") }
                .getOrNull() ?: return DebugAutomationPolicy(emptyList(), blockAll = true)
            val override = File(context.filesDir, POLICY_FILE).takeIf { it.isFile } ?: return shipped
            return runCatching { shipped.tightenedBy(parse(override.readText())) }
                .onFailure { Log.w(PLAN_LOG_TAG, "policy override ignored: ${it.javaClass.simpleName}") }
                .getOrDefault(shipped)
        }

        fun parse(text: String): DebugAutomationPolicy {
            val root = Json.parseToJsonElement(text) as JsonObject
            val deny = (root["deny"] as? JsonArray).orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                DebugPolicyRule(tool = item.text("tool") ?: return@mapNotNull null, why = item.text("why").orEmpty())
            }
            return DebugAutomationPolicy(deny)
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
    policy: DebugAutomationPolicy,
): DebugPlanAction {
    val raw = rawTool ?: throw IllegalArgumentException("actions 里每一项都要有 tool")
    fun refuse(tool: String) = policy.denied(tool)?.let { rule ->
        throw IllegalArgumentException("计划里不能自动执行 $tool${rule.why.takeIf(String::isNotEmpty)?.let { "（$it）" }.orEmpty()}")
    }
    refuse(planToolName(raw).replace('.', '_'))
    val descriptor = planCatalogTool(raw, catalog)
        ?: throw IllegalArgumentException("$server 现在没有 ${planToolName(raw)} 这个工具，先用 search_tools 找到正确的名字和所在的服务")
    val tool = descriptor.name
    refuse(tool)
    val args = arguments ?: JsonObject(emptyMap())
    (validateDebugInputSchema(descriptor.inputSchemaJson, args.toString()) as? DebugSchemaValidation.Invalid)?.let {
        throw IllegalArgumentException("$tool 的参数不对：${it.message}")
    }
    val words = label?.let(::singleLine)?.takeIf(String::isNotEmpty)?.take(MAX_ACTION_LABEL_CHARS)
    return DebugPlanAction(server, tool, args, words ?: descriptor.title?.takeIf(String::isNotBlank) ?: tool)
}

/**
 * Runs a plan's [actions] in order through [call] (an MCP `tools/call`), each once. The policy is asked again (one
 * tightened after the rule was made still holds). Returns a line per action for 最近触发.
 */
internal suspend fun runPlanActions(
    actions: List<DebugPlanAction>,
    policy: DebugAutomationPolicy,
    call: suspend (DebugPlanAction) -> String?,
): List<String> = actions.map { action ->
    if (policy.denied(action.tool) != null) return@map "${action.label}没执行（现在的策略不允许自动执行）"
    call(action)?.let { problem -> "${action.label}没成功（$problem）" } ?: "已${action.label}"
}

/** The failure in a `tools/call` answer, or null when it went through (no error, no isError, status code 0 if any). */
internal fun planActionProblem(response: McpRpcResponse): String? {
    if (!response.ok) return response.errorMessage ?: "服务没回应"
    val data = response.dataJson ?: return "没有结果"
    (data["error"] as? JsonObject)?.let { return it.text("message") ?: "出错" }
    val result = data["result"] as? JsonObject ?: return "没有结果"
    val status = (result["structuredContent"] as? JsonObject)?.get("status") as? JsonObject
    val code = (status?.get("code") as? JsonPrimitive)?.intOrNull
    val failed = (result["isError"] as? JsonPrimitive)?.booleanOrNull == true || (code != null && code != 0)
    return if (failed) status?.text("message") ?: "被拒绝了" else null
}

/** Runs plans' actions on their servers, off the alarm's and the watcher's threads. */
internal object DebugPlanActionRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** [plan]'s actions, then [done] with the lines for 最近触发. */
    fun run(context: Context, plan: DebugPlan, done: (List<String>) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            val policy = DebugAutomationPolicy.load(app)
            val lines = runPlanActions(
                plan.actions,
                policy,
                call = call@{ action ->
                    val client = mcpResourceClient(app, action.server) ?: return@call "${action.server} 没有开或连不上"
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
