package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.model.ModelTool
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The offline simulated model drives the demo's example tools (assets/mcp/tools.json) without any network. */
class DebugFixtureModelClientTest {
    @OptIn(ExperimentalAgentRuntimeApi::class)
    @Test
    fun declaresSerializedConcurrency() {
        assertEquals(ProviderConcurrencyDeclaration.serialized(), DebugFixtureModelClient().providerConcurrency)
    }

    @Test
    fun aDayOutDiscoversSchemasThenCallsTheExampleTools() = runBlocking {
        val client = DebugFixtureModelClient()
        val firstEvents = client.complete(modelRequest(DAY_OUT_TEXT)).toList()
        val first = firstEvents.toolNames()
        assertTrue("read_file" in first)
        assertTrue(first.any { it == "update_todo" })
        assertTrue(firstEvents.toolCalls().none { it.name in DAY_OUT_TOOLS })

        val second = client.complete(modelRequest(DAY_OUT_TEXT, DAY_OUT_TOOLS.map { ModelTool(it, "{}") })).toList().toolCalls()
        assertEquals(DAY_OUT_TOOLS, second.map { it.name }.filter { it in DAY_OUT_TOOLS })
        assertTrue(second.single { it.name == toolsJsonTool("navigate_to") }.argumentsJson.contains(""""avoidHighways":true"""))
        assertTrue(second.single { it.name == toolsJsonTool("play_media") }.argumentsJson.contains("周杰伦"))
        val finish = second.last()
        assertEquals("finish", finish.name)
        assertTrue(finish.argumentsJson.contains("不走高速"))
    }

    @Test
    fun repeatedHotUtterancesStayOnTheTemperatureFollowUpFlow() = runBlocking {
        val client = DebugFixtureModelClient()
        val first = client.complete(modelRequest("有点热")).toList().toolCalls()
        val second = client.complete(modelRequest("还是热", listOf(ModelTool(TEMPERATURE_TOOL, "{}")))).toList().toolNames()

        assertTrue(first.any { it.name == "read_file" && it.argumentsJson.contains(SESSION_QUERY_INDEX_PATH) })
        assertTrue(first.any { it.name == "read_file" && it.argumentsJson.contains("/mcp/tools.index.json") })
        assertTrue(first.any { it.name == "read_file" && it.argumentsJson.contains("/mcp/tools/$TEMPERATURE_TOOL/schema.json") })
        assertTrue(second.contains(TEMPERATURE_TOOL))
        assertTrue(second.last() == "finish")
    }

    @Test
    fun theLowestTemperatureIsSetOnce() = runBlocking {
        val client = DebugFixtureModelClient()
        client.complete(modelRequest("温度调到最低")).toList()
        val second = client.complete(modelRequest("温度调到最低", listOf(ModelTool(TEMPERATURE_TOOL, "{}")))).toList().toolCalls()

        assertEquals("""{"action":"set","value":16}""", second.single { it.name == TEMPERATURE_TOOL }.argumentsJson)
        assertEquals("finish", second.last().name)
    }

    @Test
    fun unsupportedFixtureInputEchoesCurrentUserText() = runBlocking {
        val events = DebugFixtureModelClient()
            .complete(modelRequest("unique-ui-1782679001"))
            .toList()
            .toolCalls()

        val finish = events.single { it.name == "finish" }
        assertTrue(finish.argumentsJson.contains("unique-ui-1782679001"))
        assertTrue(finish.argumentsJson.contains("未覆盖"))
    }

    @Test
    fun introFixtureOnlyHandlesIntroRequests() = runBlocking {
        val events = DebugFixtureModelClient()
            .complete(modelRequest("介绍一下你自己和名字"))
            .toList()
            .toolCalls()

        val finish = events.single { it.name == "finish" }
        assertTrue(finish.argumentsJson.contains("小博"))
        assertTrue(finish.argumentsJson.contains("Bothy"))
        assertFalse(finish.argumentsJson.contains("车"))
    }

    private fun modelRequest(text: String, tools: List<ModelTool> = emptyList()): ModelRequest =
        ModelRequest(messages = listOf(ModelMessage("user", text)), tools = tools)

    private fun List<ModelEvent>.toolNames(): List<String> =
        toolCalls().map { it.name }

    private fun List<ModelEvent>.toolCalls(): List<ModelEvent.ToolCall> =
        filterIsInstance<ModelEvent.ToolCall>()

    private companion object {
        const val DAY_OUT_TEXT = "我想去广州塔，先看看那边的天气，不走高速，开始导航后放首周杰伦的歌"
        val DAY_OUT_TOOLS = listOf(toolsJsonTool("get_weather"), toolsJsonTool("navigate_to"), toolsJsonTool("play_media"))
        val TEMPERATURE_TOOL = toolsJsonTool("set_temperature")

        fun toolsJsonTool(name: String): String = "mcp__tools_json__$name"
    }
}
