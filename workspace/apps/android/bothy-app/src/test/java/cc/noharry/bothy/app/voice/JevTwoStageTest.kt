@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.voice
import cc.noharry.agent.kernel.api.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class JevTwoStageTest {
    private val request = ToolPrefetchRequest(
        "完整原始请求：查资料之后再操作，禁止其他改动", listOf(
            ToolDescriptor("smarthome_status_query", "家居状态", "{}"),
            ToolDescriptor("navigation_poi_search", "地点信息", "{}"),
            ToolDescriptor("media_music_search", "音乐信息", "{}"),
        ), ToolCallContext("s", "r", "q"), 24,
    )
    private val config = JevConfig(enabled = true, hasApiKey = true, mode = JevMode.TWO_STAGE)

    @Test
    fun multiDomainSelectionMakesOneUnionRequestAndPreservesOriginalInput() = runBlocking {
        val trace = StageTrace()
        val provider = JevPrefetchProvider(TwoStageDelegate, config, "fixture-secret", "session facts") {
            val body = Json.parseToJsonElement(it).jsonObject
            assertEquals(request.input, body["state"]!!.jsonObject["latest_user_request"]!!.jsonPrimitive.content)
            assertEquals("session facts", body["state"]!!.jsonObject["recent_context"]!!.jsonPrimitive.content)
            if (trace.requests.size == 1) {
                assertEquals(setOf("domain_0", "domain_1", "domain_2"), body["questions"]!!.jsonObject.keys)
                """{"answers":{"domain_0":{"noul":0.8},"domain_1":{"noul":0.9},"domain_2":{"noul":0.1}}}"""
            } else {
                val questions = body["questions"]!!.jsonObject
                assertEquals(2, questions.size)
                assertTrue(it.contains("media_music_search"))
                assertTrue(it.contains("navigation_poi_search"))
                assertFalse(it.contains("smarthome_status_query"))
                """{"answers":{"tool_0":{"noul":0.8},"tool_1":{"noul":0.9}}}"""
            }
        }
        assertEquals(listOf("navigation_poi_search", "media_music_search"), provider.prefetchTools(request, trace))
        assertEquals(2, trace.requests.size)
        assertEquals(2, trace.responses.size)
        assertFalse(trace.requests.any { it.contains("fixture-secret") })
    }

    @Test
    fun noRelevantDomainSkipsToolRequest() = runBlocking {
        val trace = StageTrace()
        val provider = JevPrefetchProvider(TwoStageDelegate, config, "fixture", "") {
            """{"answers":{"domain_0":{"noul":0.1},"domain_1":{"noul":0.1},"domain_2":{"noul":0.1}}}"""
        }
        assertTrue(provider.prefetchTools(request, trace).isEmpty())
        assertEquals(1, trace.requests.size)
    }

    @Test
    fun subdomainGroupingUsesLivePurposesAndKeepsUnknownTools() {
        val tools = listOf(
            ToolDescriptor("navigation_route_plan", "Plan routes。使用说明：long arguments", "{}"),
            ToolDescriptor("navigation_route_via_operate", "Edit waypoints", "{}"),
            ToolDescriptor("navigation_poi_search", "Find places", "{}"),
            ToolDescriptor("custom_lookup", "Custom records", "{}"),
            ToolDescriptor("status", "Current status", "{}"),
        )
        val domains = jevDomains(tools, secondLevel = true)
        assertEquals(setOf("navigation_route", "navigation_poi", "custom_lookup", "general"), domains.map { it.id }.toSet())
        assertEquals(tools.toSet(), domains.flatMap { it.tools }.toSet())
        val route = domains.single { it.id == "navigation_route" }
        assertEquals(2, route.tools.size)
        assertEquals("Plan routes。\nEdit waypoints", route.description)
    }

    @Test
    fun subdomainModeOnlySendsSelectedSubdomainUnionToSecondRequest() = runBlocking {
        val trace = StageTrace()
        val subRequest = request.copy(tools = listOf(
            ToolDescriptor("navigation_route_plan", "Plan route", "{}"),
            ToolDescriptor("navigation_route_via_operate", "Edit waypoints", "{}"),
            ToolDescriptor("navigation_poi_search", "Find places", "{}"),
        ))
        val provider = JevPrefetchProvider(TwoStageDelegate, config.copy(mode = JevMode.TWO_STAGE_SUBDOMAINS), "fixture", "") {
            val body = Json.parseToJsonElement(it).jsonObject
            assertEquals(subRequest.input, body["state"]!!.jsonObject["latest_user_request"]!!.jsonPrimitive.content)
            if (trace.requests.size == 1) {
                val ids = body["questions"]!!.jsonObject.values.map { q ->
                    q.jsonObject["instructions"]!!.jsonObject["domain_id"]!!.jsonPrimitive.content
                }
                assertEquals(listOf("navigation_poi", "navigation_route"), ids)
                """{"answers":{"domain_0":{"noul":0.1},"domain_1":{"noul":0.9}}}"""
            } else {
                assertEquals(2, body["questions"]!!.jsonObject.size)
                assertTrue(it.contains("navigation_route_via_operate"))
                assertFalse(it.contains("navigation_poi_search"))
                """{"answers":{"tool_0":{"noul":0.8},"tool_1":{"noul":0.9}}}"""
            }
        }
        assertEquals(listOf("navigation_route_via_operate", "navigation_route_plan"), provider.prefetchTools(subRequest, trace))
        assertEquals(2, trace.requests.size)
    }

    @Test
    fun unknownNamespacesAndNamesWithoutDelimiterRemainDiscoverable() {
        val tools = listOf(ToolDescriptor("custom_lookup", "Find custom records", "{}"), ToolDescriptor("status", "Report status", "{}"))
        val domains = jevDomains(tools)
        assertEquals(tools.toSet(), domains.flatMap { it.tools }.toSet())
        assertEquals(setOf("custom", "general"), domains.map { it.id }.toSet())
        assertTrue(domains.single { it.id == "custom" }.description.contains("Find custom records"))
    }

    @Test
    fun badDomainAnswerCannotProceedWithAnIncompleteDomainSet() = runBlocking {
        var calls = 0
        val provider = JevPrefetchProvider(TwoStageDelegate, config, "fixture", "") {
            calls++; """{"answers":{"domain_0":{"noul":0.9}}}"""
        }
        try {
            provider.prefetchTools(request, StageTrace())
            fail("Incomplete domain scores must fail closed to Kernel fallback")
        } catch (_: IllegalArgumentException) {
            assertEquals(1, calls)
        }
    }

    @Test
    fun emptyCatalogMakesNoHttpRequest() = runBlocking {
        val provider = JevPrefetchProvider(TwoStageDelegate, config, "fixture", "") { error("No catalog") }
        assertTrue(provider.prefetchTools(request.copy(tools = emptyList()), StageTrace()).isEmpty())
    }
}

private class StageTrace : ToolPrefetchTrace {
    val requests = mutableListOf<String>()
    val responses = mutableListOf<String>()
    override fun request(json: String) { requests += json }
    override fun response(json: String) { responses += json }
}

private object TwoStageDelegate : ToolProvider {
    override val providerId = "fixture"
    override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
    override suspend fun listTools() = emptyList<ToolDescriptor>()
    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext) = error("Must not execute")
}
