package cc.noharry.bothy.server.skills

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.McpServerDraft
import cc.noharry.agent.appserver.protocol.McpServerKind
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.agent.appserver.skills.DEFAULT_SUBAGENT_TOOLS
import cc.noharry.agent.appserver.skills.SubagentToolGroup
import cc.noharry.agent.appserver.skills.withSections
import cc.noharry.bothy.server.server.bothyConfig
import cc.noharry.bothy.server.tools.BOTHY_SUBAGENT_KINDS
import cc.noharry.bothy.server.tools.BothyRuns
import cc.noharry.bothy.server.tools.WEB_VERIFY_KIND
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bothy's subagent types (the three trip planners and 网上核实), what their AGENT.md files and its trip skill say of
 * them, and the devices a subagent only queries. How types are offered, switched and told their tools is the core's
 * (`SubagentKindsTest` in agent-app-server).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SubagentKindsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun installBothy() = AppServerTesting.install(bothyConfig())

    @After
    fun bareHost() = AppServerTesting.reset()

    @Test
    fun thePlaygroundsCarAndHomeItsOpenToolAndTheExampleCarAreDevices() {
        assertEquals(setOf("cabin", "home", "playground_screen", "tools_json"), BothyRuns.queryOnlyInSubagents(context))

        // Added by hand at the virtual car's address, under an id of its own.
        val draft = McpServerDraft(null, "我的座舱", McpServerKind.HTTP, url = "http://127.0.0.1:8790/cabin/mcp/")
        val id = AppServerTesting.saveMcpServer(context, draft).getOrThrow()

        assertTrue(id in BothyRuns.queryOnlyInSubagents(context))
    }

    @Test
    fun theTripTypesShareTheWorkAndTheCheckUsesOnlyTheWeb() {
        val kinds = BOTHY_SUBAGENT_KINDS.associateBy { it.id }
        assertEquals(listOf("trip-transport", "trip-stay", "trip-days", WEB_VERIFY_KIND), BOTHY_SUBAGENT_KINDS.map { it.id })
        // A self-drive leg may check the range; the stay and the days need none of the devices.
        assertEquals(DEFAULT_SUBAGENT_TOOLS + SubagentToolGroup.DEVICE_QUERY, kinds.getValue("trip-transport").tools)
        assertEquals(DEFAULT_SUBAGENT_TOOLS, kinds.getValue("trip-stay").tools)
        assertEquals(6, kinds.getValue("trip-stay").maxLookups)
        val verify = kinds.getValue(WEB_VERIFY_KIND)
        assertEquals(setOf(SubagentToolGroup.SEARCH, SubagentToolGroup.FETCH), verify.tools)
        assertEquals(6, verify.maxLookups)
        assertEquals(true, verify.searchApi)
        assertTrue(verify.requiresWebLookup)
        assertTrue(verify.description, verify.description.contains("finish without waiting"))
    }

    @Test
    fun eachTypesInstructionsSayWhatToHandBackAndTheCheckNamesItsFlightPage() {
        BOTHY_SUBAGENT_KINDS.forEach { kind ->
            val text = asset(kind.assetPath)
            val body = text.substringAfter("\n---\n").trim()
            assertTrue(kind.id, text.startsWith("---\nname: ${kind.id}\n") && body.startsWith("# ") && body.contains("交回的格式"))
        }
        assertTrue(asset(BOTHY_SUBAGENT_KINDS.single { it.id == WEB_VERIFY_KIND }.assetPath).contains("flights.ctrip.com/actualtime/fno--"))
        assertTrue(asset(BOTHY_SUBAGENT_KINDS.single { it.id == "trip-transport" }.assetPath).contains("\n# 行程交通"))
    }

    @Test
    fun theTripSkillNamesATypeWhileItIsOnAndSpellsTheTaskOutWhileItIsOff() {
        val raw = asset("runtime-workspace/skills/travel-planning/SKILL.md")
        val types = BOTHY_SUBAGENT_KINDS.mapTo(hashSetOf()) { "agent:${it.id}" }
        val on = raw.withSections(types)
        assertTrue(on.contains("agent 写 trip-transport") && !on.contains("时刻表页常列出") && !on.contains("<!--"))
        val off = raw.withSections(types - "agent:trip-transport")
        assertTrue(off.contains("时刻表页常列出") && !off.contains("agent 写 trip-transport") && off.contains("agent 写 trip-days"))
        // As the server carries it for a trip while every type is on.
        assertTrue(AppServerTesting.prompt(context, "去成都玩三天").text.contains("agent 写 trip-transport"))
    }

    private fun asset(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }.replace("\r\n", "\n")
}
