package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.core.DEFERRED_TOOLS_HEADER
import cc.noharry.agent.kernel.core.RUN_CONTEXT_HEADER
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class DebugFixtureModelClient : ModelClient {
    @OptIn(ExperimentalAgentRuntimeApi::class)
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()

    private var scenario: FixtureScenario? = null
    private var roundIndex = 0
    private var callIndex = 0

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        val active = scenario ?: scenarioFor(latestUserText(request.messages)).also { scenario = it }
        val events = if (active.ready(request)) {
            active.businessEvents(::nextId)
        } else {
            active.discoveryEvents(::nextId)
        }
        roundIndex += 1
        emit(fixtureUsageDelta(request, events.size))
        events.forEach { emit(it) }
        emit(ModelEvent.Completed)
    }

    private fun nextId(prefix: String): String {
        callIndex += 1
        return "fixture-$roundIndex-$prefix-$callIndex"
    }
}

private interface FixtureScenario {
    fun ready(request: ModelRequest): Boolean
    fun discoveryEvents(nextId: (String) -> String): List<ModelEvent.ToolCall>
    fun businessEvents(nextId: (String) -> String): List<ModelEvent.ToolCall>
}

/** A day out: the weather there, the route, then music, through the demo's example tools (assets/mcp/tools.json). */
private class DayOutScenario(private val avoidHighways: Boolean) : FixtureScenario {
    override fun ready(request: ModelRequest): Boolean =
        DAY_OUT_TOOLS.all { tool -> request.tools.any { it.name == tool } }

    override fun discoveryEvents(nextId: (String) -> String): List<ModelEvent.ToolCall> =
        listOf(
            tool(nextId, UPDATE_TODO, DAY_OUT_PENDING_TODO),
            read(nextId, MCP_SERVERS_INDEX),
            read(nextId, MCP_TOOLS_INDEX),
        ) + DAY_OUT_TOOLS.map { read(nextId, schemaPath(it)) }

    override fun businessEvents(nextId: (String) -> String): List<ModelEvent.ToolCall> =
        listOf(
            tool(nextId, UPDATE_TODO, DAY_OUT_PENDING_TODO.replace("pending", "in_progress")),
            tool(nextId, WEATHER_TOOL, """{"city":"广州"}"""),
            tool(nextId, NAVIGATE_TOOL, """{"destination":"广州塔","avoidHighways":$avoidHighways}"""),
            tool(nextId, MEDIA_TOOL, """{"keyword":"周杰伦"}"""),
            tool(nextId, UPDATE_TODO, DAY_OUT_PENDING_TODO.replace("in_progress", "completed").replace("pending", "completed")),
            finish(nextId, "已查好广州的天气，开始导航去广州塔${if (avoidHighways) "（不走高速）" else ""}，并播放周杰伦的歌。"),
        )
}

private class TemperatureMinimumScenario : FixtureScenario {
    override fun ready(request: ModelRequest): Boolean =
        request.tools.any { it.name == TEMPERATURE_TOOL }

    override fun discoveryEvents(nextId: (String) -> String): List<ModelEvent.ToolCall> =
        listOf(
            tool(nextId, UPDATE_TODO, TEMPERATURE_PENDING_TODO),
            read(nextId, MCP_SERVERS_INDEX),
            read(nextId, MCP_TOOLS_INDEX),
            read(nextId, schemaPath(TEMPERATURE_TOOL)),
        )

    override fun businessEvents(nextId: (String) -> String): List<ModelEvent.ToolCall> =
        listOf(
            tool(nextId, TEMPERATURE_TOOL, """{"action":"set","value":16}"""),
            tool(nextId, UPDATE_TODO, TEMPERATURE_DONE_TODO),
            finish(nextId, "已把温度设到最低的 16 度。"),
        )
}

private class HotFollowUpScenario : FixtureScenario {
    override fun ready(request: ModelRequest): Boolean =
        request.tools.any { it.name == TEMPERATURE_TOOL }

    override fun discoveryEvents(nextId: (String) -> String): List<ModelEvent.ToolCall> =
        listOf(
            read(nextId, SESSION_QUERY_INDEX_PATH),
            read(nextId, MCP_SERVERS_INDEX),
            read(nextId, MCP_TOOLS_INDEX),
            read(nextId, schemaPath(TEMPERATURE_TOOL)),
        )

    override fun businessEvents(nextId: (String) -> String): List<ModelEvent.ToolCall> =
        listOf(
            tool(nextId, TEMPERATURE_TOOL, """{"action":"decrease","value":2}"""),
            finish(nextId, "已参考之前的温度设置，再调低 2 度。"),
        )
}

private class IntroScenario : FixtureScenario {
    override fun ready(request: ModelRequest): Boolean = true

    override fun discoveryEvents(nextId: (String) -> String): List<ModelEvent.ToolCall> = businessEvents(nextId)

    override fun businessEvents(nextId: (String) -> String): List<ModelEvent.ToolCall> =
        listOf(finish(nextId, "我是小博，Bothy 的示例助手：在这台设备上帮你查信息、调用工具，把事办完。"))
}

private class UnsupportedFixtureScenario(private val userText: String) : FixtureScenario {
    override fun ready(request: ModelRequest): Boolean = true

    override fun discoveryEvents(nextId: (String) -> String): List<ModelEvent.ToolCall> = businessEvents(nextId)

    override fun businessEvents(nextId: (String) -> String): List<ModelEvent.ToolCall> =
        listOf(
            finish(
                nextId,
                "Simulated MCP 收到了本轮输入：${userText.oneLineForFixture()}。当前模拟场景未覆盖这个输入，请切换 Live 或补充模拟用例。",
            ),
        )
}

private fun scenarioFor(text: String): FixtureScenario {
    val normalized = text.lowercase()
    return when {
        normalized.contains("广州塔") -> DayOutScenario(avoidHighways = normalized.contains("高速"))
        normalized.contains("最低") || normalized.contains("minimum") -> TemperatureMinimumScenario()
        normalized.contains("热") || normalized.contains("hot") -> HotFollowUpScenario()
        normalized.isIntroRequest() -> IntroScenario()
        else -> UnsupportedFixtureScenario(text)
    }
}

private fun String.isIntroRequest(): Boolean =
    contains("介绍") ||
        contains("你自己") ||
        contains("名字") ||
        contains("who are you") ||
        contains("your name")

private fun String.oneLineForFixture(): String =
    replace("\n", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(FIXTURE_ECHO_CHARS)

private fun latestUserText(messages: List<ModelMessage>): String =
    messages.lastOrNull { message ->
        message.role == "user" &&
            !message.content.startsWith(RUN_CONTEXT_HEADER) &&
            !message.content.startsWith(DEFERRED_TOOLS_HEADER) &&
            !message.content.startsWith("You are 小博")
    }?.content.orEmpty()

private fun read(nextId: (String) -> String, path: String): ModelEvent.ToolCall =
    tool(nextId, "read_file", """{"path":"$path"}""")

private fun finish(nextId: (String) -> String, response: String): ModelEvent.ToolCall =
    tool(nextId, "finish", """{"response":"$response"}""")

private fun tool(nextId: (String) -> String, name: String, argumentsJson: String): ModelEvent.ToolCall =
    ModelEvent.ToolCall(id = nextId(name), name = name, argumentsJson = argumentsJson)

private fun fixtureUsageDelta(request: ModelRequest, eventCount: Int): ModelEvent.UsageDelta =
    ModelEvent.UsageDelta(
        inputTokens = request.messages.sumOf { it.content.length }.coerceAtLeast(MIN_FIXTURE_INPUT_CHARS) / USAGE_CHAR_DIVISOR,
        outputTokens = eventCount * FIXTURE_OUTPUT_TOKENS_PER_EVENT,
        reasoningTokens = eventCount,
        cacheReadTokens = request.tools.size * FIXTURE_CACHE_READ_PER_TOOL,
        cacheWriteTokens = eventCount,
    )

// The demo's offline example tools (assets/mcp/tools.json).
private val WEATHER_TOOL = toolsJsonTool("get_weather")
private val NAVIGATE_TOOL = toolsJsonTool("navigate_to")
private val MEDIA_TOOL = toolsJsonTool("play_media")
private val TEMPERATURE_TOOL = toolsJsonTool("set_temperature")
private val DAY_OUT_TOOLS = listOf(WEATHER_TOOL, NAVIGATE_TOOL, MEDIA_TOOL)
private const val UPDATE_TODO = "update_todo"
private const val MCP_SERVERS_INDEX = "/mcp/servers.json"
private const val MCP_TOOLS_INDEX = "/mcp/tools.index.json"
private const val MIN_FIXTURE_INPUT_CHARS = 80
private const val USAGE_CHAR_DIVISOR = 4
private const val FIXTURE_OUTPUT_TOKENS_PER_EVENT = 8
private const val FIXTURE_CACHE_READ_PER_TOOL = 2
private const val FIXTURE_ECHO_CHARS = 80

private fun toolsJsonTool(name: String): String =
    "mcp__tools_json__$name"

private fun schemaPath(toolName: String): String =
    "/mcp/tools/$toolName/schema.json"

private val DAY_OUT_PENDING_TODO = """
    {"todos":[
      {"content":"查广州的天气","status":"in_progress"},
      {"content":"规划去广州塔的路线","status":"pending"},
      {"content":"播放周杰伦的歌","status":"pending"}
    ]}
""".trimIndent()
private val TEMPERATURE_PENDING_TODO = """{"todos":[{"content":"读取温度工具并设到最低","status":"in_progress"}]}"""
private val TEMPERATURE_DONE_TODO = """{"todos":[{"content":"读取温度工具并设到最低","status":"completed"}]}"""
