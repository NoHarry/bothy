@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.skills
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.kernel.api.*
import cc.noharry.bothy.app.model.summary
import cc.noharry.bothy.app.runtime.outputContractSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkflowSkillsTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("debug_runtime_skills", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun aTripIsPlannedWithTheTravelSkillButADayPlanOrARouteIsNot() {
        val skills = loadRuntimeSkills(context)
        listOf("下周五从上海出发去成都玩三天，帮我规划一下行程，带上来回的机票和酒店推荐", "国庆想去云南旅游", "帮我排一个西安3日游",
            "下周五从上海飞乌鲁木齐玩四天，帮我规划行程，带上机票和酒店", "下周六从上海坐高铁去杭州玩两天，帮我安排一下行程",
            "下周六从上海自驾去莫干山玩两天，帮我排个行程", "周末想自驾游去千岛湖").forEach { input ->
            assertTrue(input, selectedWorkflowSkills(context, skills, input).any { it.skill.id == "travel-planning" })
        }
        // A single hotel or flight question needs no three-way plan.
        listOf("我明天的行程是什么", "导航去虹桥机场", "去公司", "推荐一下杭州西湖边的酒店").forEach { input ->
            assertFalse(input, selectedWorkflowSkills(context, skills, input).any { it.skill.id == "travel-planning" })
        }
        assertTrue(preloadedSkillPrompt(context, skills, "去成都玩三天").contains("多日旅行规划"))
        // Sending the plan to a personal assistant is offered only while one is connected, in the trip's own last step.
        assertFalse(preloadedSkillPrompt(context, skills, "去成都玩三天").contains("个人助手"))
        val raw = context.assets.open("runtime-workspace/skills/travel-planning/SKILL.md").bufferedReader().use { it.readText() }
        val connected = raw.withA2aSections(connected = true)
        val alone = raw.withA2aSections(connected = false)
        // Step 6 is written whole for each case: one closing question, with the backup only while an assistant is connected.
        assertEquals(1, Regex("(?m)^6\\. ").findAll(connected).count())
        assertEquals(1, Regex("(?m)^6\\. ").findAll(alone).count())
        assertTrue(connected.contains("或者把行程发给你的个人助手备份？」") && !connected.contains("<!--"))
        assertTrue(alone.contains("设成提醒？」") && !alone.contains("个人助手") && !alone.contains("<!--"))
    }

    @Test fun aManifestMatchesItsTriggersAndBindsOnlyToolsThatStillHaveItsFields() = runBlocking {
        val skill = RuntimeSkill("volume-workflow", "Volume", "", "", enabled = true, preload = true)
        assertNull(parseWorkflowSkill(skill, VOLUME_MANIFEST, "解释量子力学"))
        val workflow = parseWorkflowSkill(skill, VOLUME_MANIFEST, "查询音量")!!
        val valid = ToolDescriptor("system_volume_get", "read volume", """{"type":"object","properties":{"stream":{"type":"string"}}}""",
            outputSchemaJson = """{"type":"object","properties":{"data":{"type":"object","properties":{"volume":{"type":"integer"}}}}}""")
        val drifted = ToolDescriptor("system_volume_set", "changed", """{"type":"object","properties":{"value":{"type":"number"}}}""")
        val delegate = object : ToolProvider {
            override val providerId = "device"
            override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
            override suspend fun listTools() = listOf(valid, drifted)
            override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext) = error("prefetch must never execute")
        }
        val provider = SkillToolProvider(delegate, listOf(workflow))
        val tools = provider.listTools()
        val request = ToolPrefetchRequest("查询音量", tools, ToolCallContext("s", "r", "q"), 24)
        assertEquals(listOf("system_volume_get"), provider.localToolNames(request))
        assertFalse(provider.remotePrefetchEnabled)
        assertTrue(tools.first().description.contains("data.volume?"))
        assertEquals(drifted.description, tools.last().description)
        assertEquals(valid.outputSchemaJson, tools.first().outputSchemaJson)
    }

    @Test fun optionalNestedOutputFieldsAreHintsAndDoNotBecomeDefaults() {
        val schema = """{"type":"object","properties":{"data":{"anyOf":[{"type":"object","properties":{"songs":{"type":"array","items":{"type":"object","properties":{"id":{"type":"string"},"name":{"type":"string"}}}}}},{"type":"object"}]}}}"""
        val summary = outputContractSummary(schema)
        assertTrue(summary.contains("data.songs[].id?"))
        assertTrue(summary.contains("omission is unknown"))
        assertTrue(summary.contains("full outputSchema"))
        assertEquals("", outputContractSummary("not json"))
        assertEquals("", outputContractSummary(null))
    }
}

private const val VOLUME_MANIFEST =
    """{"schemaVersion":1,"triggers":["音量"],"bindings":{"system_volume_get":["stream"],"system_volume_set":["stream","volume"]}}"""
