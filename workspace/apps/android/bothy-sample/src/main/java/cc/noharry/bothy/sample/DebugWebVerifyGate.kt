package cc.noharry.bothy.sample

import cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.bothy.kernel.api.HookContext
import cc.noharry.bothy.kernel.api.HookDecision
import cc.noharry.bothy.kernel.api.RuntimeHook
import cc.noharry.bothy.kernel.api.ToolUse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 网上核实 checks what a map or travel service returned, not the agent's own web search (owner 2026-10-19: 「有什么活动」
 * 本来就该走 search; 需要印证的同时发出去，最后综合判断一起发出来). The rule alone did not hold on the bench: asked to
 * 「帮我确认下时间和票价」, the agent searched, answered, then sent web-verify, whose report came back half a minute
 * later as 「两处更正」. So a web-verify spawn goes out only after a service call in this run (a place search, say), or
 * when the request names a flight or train, whose status page only the verifier opens. Hosted search is not a tool
 * call, so the gate looks for the service call rather than for the search.
 */
@OptIn(ExperimentalAgentRuntimeApi::class)
internal class DebugWebVerifyGate(private val userInput: String) : RuntimeHook {
    @Volatile private var serviceCalled = false

    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
        if (call.toolName.isServiceTool()) serviceCalled = true
        if (call.toolName != SPAWN_TOOL || call.argumentsJson.spawnedType() != WEB_VERIFY_KIND) return HookDecision.Allow()
        if (serviceCalled || debugNamesTrip(userInput)) return HookDecision.Allow()
        return HookDecision.Deny(
            reason = "没有派：网上核实只核对地图、导航这类服务查到的结果，或用户报的航班、车次。这一轮的信息来自你自己的联网搜索：" +
                "需要印证的几项在这一步一起搜（一次回复里多次搜索），然后只回答一次，不要先答再补。",
        )
    }
}

/** Whether [text] names a flight (CA3636, 3U8888) or a train (G7311, C3636). */
internal fun debugNamesTrip(text: String): Boolean = FLIGHT_NUMBER.containsMatchIn(text) || TRAIN_NUMBER.containsMatchIn(text)

/** A backend service the agent looked something up in: not the device's own screen, plans, phone screen or A2A agents. */
private fun String.isServiceTool(): Boolean {
    if (!startsWith(MCP_PREFIX)) return false
    val server = removePrefix(MCP_PREFIX).substringBefore("__")
    return server !in NON_SERVICE_SERVERS && !server.startsWith(A2A_SERVER_PREFIX)
}

private fun String.spawnedType(): String = runCatching {
    // Trimmed as the runtime trims it, so 「 web-verify」 is the same type.
    Json.parseToJsonElement(this).jsonObject["agent"]?.jsonPrimitive?.content.orEmpty().trim()
}.getOrDefault("")

private const val SPAWN_TOOL = "spawn_agent"
private const val MCP_PREFIX = "mcp__"
private const val A2A_SERVER_PREFIX = "experimental_a2a"
private val NON_SERVICE_SERVERS = setOf("car_ui", "plans", "android_use")
// Lookarounds, not \b: on Android (ICU) 坐 is a word character, so \b would miss the CA3636 in 「坐CA3636过来」, while
// the JVM's ASCII \b finds it and the unit tests would pass.
// Letter-digit airline codes are left out: they read like a plate (粤B12345).
private val FLIGHT_NUMBER = Regex("""(?<![A-Za-z0-9])(?:[A-Za-z]{2}|\d[A-Za-z])\d{3,4}(?![A-Za-z0-9])""")
// Not a road or a terminal: 「G4高速」「T3航站楼」 name no train (「坐G7311」 still does).
private val TRAIN_NUMBER = Regex("""(?<![A-Za-z0-9])[GDCKZTgdckzt]\d{1,4}(?![A-Za-z0-9]|高速|国道|航站)""")
