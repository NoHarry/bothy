@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.server.skills
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.a2a.A2A_SKILL_SECTION
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.agent.appserver.skills.withSections
import cc.noharry.bothy.server.server.bothyConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bothy's trip skill: which words plan a trip (its manifest) and its last step with and without the person's own agent.
 * How a workflow is picked and carried is the core's (`WorkflowSkillsTest` in agent-app-server).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkflowSkillsTest {
    private lateinit var context: Context

    @Before
    fun installBothy() {
        AppServerTesting.install(bothyConfig())
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app_server_runtime_skills", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun bareHost() = AppServerTesting.reset()

    private fun carriesTrip(input: String) = AppServerTesting.prompt(context, input).skills.any { it.first == "travel-planning" }

    @Test fun aTripIsPlannedWithTheTravelSkillButADayPlanOrARouteIsNot() {
        listOf("下周五从上海出发去成都玩三天，帮我规划一下行程，带上来回的机票和酒店推荐", "国庆想去云南旅游", "帮我排一个西安3日游",
            "下周五从上海飞乌鲁木齐玩四天，帮我规划行程，带上机票和酒店", "下周六从上海坐高铁去杭州玩两天，帮我安排一下行程",
            "下周六从上海自驾去莫干山玩两天，帮我排个行程", "周末想自驾游去千岛湖").forEach { input ->
            assertTrue(input, carriesTrip(input))
        }
        // A single hotel or flight question needs no three-way plan.
        listOf("我明天的行程是什么", "导航去虹桥机场", "去公司", "推荐一下杭州西湖边的酒店").forEach { input ->
            assertFalse(input, carriesTrip(input))
        }
        assertTrue(AppServerTesting.prompt(context, "去成都玩三天").text.contains("多日旅行规划"))
        // Sending the plan to a personal assistant is offered only while one is connected, in the trip's own last step.
        assertFalse(AppServerTesting.prompt(context, "去成都玩三天").text.contains("个人助手"))
        val raw = context.assets.open("runtime-workspace/skills/travel-planning/SKILL.md").bufferedReader().use { it.readText() }
        val connected = raw.withSections(setOf(A2A_SKILL_SECTION))
        val alone = raw.withSections(emptySet())
        // Step 6 is written whole for each case: one closing question, with the backup only while an assistant is connected.
        assertEquals(1, Regex("(?m)^6\\. ").findAll(connected).count())
        assertEquals(1, Regex("(?m)^6\\. ").findAll(alone).count())
        assertTrue(connected.contains("或者把行程发给你的个人助手备份？」") && !connected.contains("<!--"))
        assertTrue(alone.contains("设成提醒？」") && !alone.contains("个人助手") && !alone.contains("<!--"))
    }
}
