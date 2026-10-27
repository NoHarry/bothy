@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.voice
import cc.noharry.agent.kernel.api.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class JevPrefetchProviderTest {
    private val request = ToolPrefetchRequest(
        "先去餐厅，再导航并播放歌曲", listOf(ToolDescriptor("route", "真实算路描述", "{}"), ToolDescriptor("music", "歌曲搜索", "{}")),
        ToolCallContext("s", "r", "q"), 24,
    )
    private val config = JevConfig(enabled = true, hasApiKey = true)

    @Test
    fun requestUsesIndependentQuestionsAndRealDescriptions() {
        val json = Json.parseToJsonElement(jevQuestionBody(request, "configured-model", "prior question")).jsonObject
        assertEquals("configured-model", json["model"]?.jsonPrimitive?.content)
        assertEquals(request.input, json["state"]?.jsonObject?.get("latest_user_request")?.jsonPrimitive?.content)
        val questions = json["questions"]!!.jsonObject
        assertEquals(2, questions.size)
        assertTrue(questions["tool_0"].toString().contains("真实算路描述"))
        assertEquals("noul", questions["tool_1"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun selectorReturnsComplementaryNamesInScoreOrderAndHonorsLimit() {
        val response = """{"answers":{"tool_0":{"noul":0.8},"tool_1":{"noul":0.9}}}"""
        assertEquals(listOf("music", "route"), selectJevTools(response, request, config))
        assertEquals(listOf("music"), selectJevTools(response, request, config.copy(maxTools = 1)))
        assertEquals(emptyList<String>(), selectJevTools(response, request, config.copy(minScore = 0.99)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingAnswerCannotSilentlyProducePartialSelection() {
        selectJevTools("""{"answers":{"tool_0":{"noul":0.9}}}""", request, config)
    }

    @Test(expected = IllegalArgumentException::class)
    fun outOfRangeScoreIsRejected() {
        selectJevTools("""{"answers":{"tool_0":{"noul":2},"tool_1":{"noul":0.9}}}""", request, config)
    }

    @Test
    fun disabledSelectorDoesNotUseTransport() = runBlocking {
        var calls = 0
        val provider = JevPrefetchProvider(EmptyProvider, config.copy(enabled = false), "fixture-key", "") {
            calls++; error("disabled")
        }
        assertTrue(provider.prefetchTools(request, RecordingTrace()).isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun enabledSelectorRecordsExactBodiesWithoutKeyAndPreservesProviderIdentity() = runBlocking {
        val response = """{"answers":{"tool_0":{"noul":0.8},"tool_1":{"noul":0.9}}}"""
        val trace = RecordingTrace()
        val provider = JevPrefetchProvider(EmptyProvider, config, "fixture-private-key", "context") { response }
        assertEquals(listOf("music", "route"), provider.prefetchTools(request, trace))
        assertEquals(EmptyProvider.providerId, provider.providerId)
        assertEquals(response, trace.result)
        assertTrue(trace.body.contains(request.input))
        assertFalse(trace.body.contains("fixture-private-key"))
    }
}

private class RecordingTrace : ToolPrefetchTrace {
    var body = ""
    var result = ""
    override fun request(json: String) { body = json }
    override fun response(json: String) { result = json }
}

private object EmptyProvider : ToolProvider {
    override val providerId = "fixture"
    override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
    override suspend fun listTools() = emptyList<ToolDescriptor>()
    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext) = error("not executed")
}
