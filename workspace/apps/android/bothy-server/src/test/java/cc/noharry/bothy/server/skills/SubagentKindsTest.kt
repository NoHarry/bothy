@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.server.skills
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.mcp.McpSaveResult
import cc.noharry.agent.appserver.mcp.saveMcpServerDraft
import cc.noharry.agent.appserver.protocol.McpServerDraft
import cc.noharry.agent.appserver.protocol.McpServerKind
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.BuiltInFeatures
import cc.noharry.agent.appserver.runtime.Persona
import cc.noharry.agent.appserver.runtime.RunAddOns
import cc.noharry.agent.appserver.skills.AppSubagents
import cc.noharry.agent.appserver.skills.MAX_LOOKUPS
import cc.noharry.agent.appserver.skills.SubagentToolGroup
import cc.noharry.agent.appserver.skills.appSubagentKind
import cc.noharry.agent.appserver.skills.loadRuntimeSkills
import cc.noharry.agent.appserver.skills.loadSubagentKinds
import cc.noharry.agent.appserver.skills.preloadedSkillPrompt
import cc.noharry.agent.appserver.skills.resetSubagentKindTools
import cc.noharry.agent.appserver.skills.saveSubagentKind
import cc.noharry.agent.appserver.skills.saveSubagentKindTools
import cc.noharry.agent.appserver.skills.subagentInput
import cc.noharry.agent.appserver.skills.subagentKindText
import cc.noharry.agent.appserver.skills.withA2aSections
import cc.noharry.agent.appserver.skills.withSections
import cc.noharry.agent.runtime.SubagentSpec
import cc.noharry.bothy.server.server.BothyBuiltIns
import cc.noharry.bothy.server.server.BothyPersona
import cc.noharry.bothy.server.tools.BothyRuns
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SubagentKindsTest {
    @Before
    fun installBothyRuns() {
        AppHost.install(runs = BothyRuns, persona = BothyPersona, builtIns = BothyBuiltIns)
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun switchEveryTypeBackOn() {
        AppHost.install(runs = RunAddOns.NONE, persona = Persona.GENERIC, builtIns = BuiltInFeatures.NONE)
        loadSubagentKinds(context).forEach {
            saveSubagentKind(context, it.id, true)
            resetSubagentKindTools(context, it.id)
        }
    }

    @Test
    fun thePlaygroundsCarAndHomeItsOpenToolAndTheExampleCarAreDevices() {
        assertEquals(setOf("cabin", "home", "playground_screen", "tools_json"), BothyRuns.deviceServers(context))

        // Added by hand at the virtual car's address, under an id of its own.
        val draft = McpServerDraft(null, "我的座舱", McpServerKind.HTTP, url = "http://127.0.0.1:8790/cabin/mcp/")
        val id = (saveMcpServerDraft(context, draft) as McpSaveResult.Saved).id

        assertTrue(id in BothyRuns.deviceServers(context))
    }

    @Test
    fun aTypeKeepsItsToolsAndShareUntilReset() {
        val transport = appSubagentKind(context, "trip-transport")!!
        val stay = appSubagentKind(context, "trip-stay")!!
        assertEquals("联网搜索、打开网页、读文件、待办、查设备状态、其他服务", transport.toolLabels())
        assertFalse(SubagentToolGroup.DEVICE_QUERY in stay.tools)
        assertTrue(SubagentToolGroup.entries.none { it in stay.tools && it.changes && it != SubagentToolGroup.SERVICES })

        saveSubagentKindTools(context, "trip-stay", setOf(SubagentToolGroup.FETCH, SubagentToolGroup.WRITE), 99)

        val changed = appSubagentKind(context, "trip-stay")!!
        assertEquals(setOf(SubagentToolGroup.FETCH, SubagentToolGroup.WRITE), changed.tools)
        assertEquals(MAX_LOOKUPS, changed.maxLookups)
        assertFalse(changed.searches)
        resetSubagentKindTools(context, "trip-stay")
        assertEquals(stay, appSubagentKind(context, "trip-stay"))
    }

    @Test
    fun aTypedChildIsToldItsToolsAndAChildThatCannotSearchIsNotToldToSearch() {
        val spec = SubagentSpec("sub-1", "s-sub-1", "s", "r", "酒店", "杭州湖滨，1 晚", 1, null, true, agentType = "trip-stay")
        val stay = appSubagentKind(context, "trip-stay")!!

        val input = subagentInput(spec, stay)
        assertTrue(input, input.contains("能用的工具：联网搜索、打开网页、读文件、待办、其他服务；别的工具会被拒绝。") && input.contains("查资料先联网搜索"))
        // Device control is the type's to give too, whatever the subagent setting allows.
        assertTrue(input.contains("不能操作设备"))

        val offline = subagentInput(spec, stay.copy(tools = setOf(SubagentToolGroup.READ)))
        assertTrue(offline, !offline.contains("联网搜索") && !offline.contains("一共最多") && offline.contains("能用的工具：读文件"))
    }

    @Test
    fun theTripTypesAreOnByDefaultAndASwitchReachesTheNextSpawns() {
        AppSubagents.apply(context)
        AppSubagents.refreshTypes(context, webLookup = false)
        assertEquals(listOf("trip-transport", "trip-stay", "trip-days"), AppSubagents.instance.config.types.map { it.name })

        saveSubagentKind(context, "trip-stay", false)

        // The switch re-reads the real key, so web-verify joins in a build whose local.properties carries a DeepSeek key.
        val trip = AppSubagents.instance.config.types.map { it.name }.filter { it.startsWith("trip-") }
        assertEquals(listOf("trip-transport", "trip-days"), trip)
        assertNull(appSubagentKind(context, "trip-stay"))
    }

    @Test
    fun webVerifyIsOfferedOnlyWhileDeepSeekLookupsAreAtHand() {
        val verify = appSubagentKind(context, "web-verify")!!
        assertEquals(setOf(SubagentToolGroup.SEARCH, SubagentToolGroup.FETCH), verify.tools)
        assertEquals(6, verify.maxLookups)
        assertEquals(true, verify.searchApi)
        assertTrue(verify.description, verify.description.contains("finish without waiting"))

        AppSubagents.refreshTypes(context, webLookup = true)
        assertEquals(listOf("trip-transport", "trip-stay", "trip-days", "web-verify"), AppSubagents.instance.config.types.map { it.name })
        AppSubagents.refreshTypes(context, webLookup = false)
        assertEquals(listOf("trip-transport", "trip-stay", "trip-days"), AppSubagents.instance.config.types.map { it.name })

        // Its own fixed addresses (the flight page) are not the guessed URLs the general rule forbids.
        val spec = SubagentSpec("sub-1", "s-sub-1", "s", "r", "核实", "CA3636，2026-10-19", 1, null, false, agentType = "web-verify")
        val input = subagentInput(spec, verify, subagentKindText(context, verify))
        assertTrue(input, input.contains("或你的分工里写明的网址") && input.contains("flights.ctrip.com/actualtime/fno--"))
    }

    @Test
    fun aTypedChildGetsItsInstructionsAndItsShareOfLookupsInsteadOfThePictureRule() {
        loadSubagentKinds(context).forEach { kind ->
            val text = subagentKindText(context, kind)
            assertTrue(kind.id, text.startsWith("# ") && text.contains("交回的格式") && !text.contains("---"))
        }
        val kind = appSubagentKind(context, "trip-transport")!!
        val spec = SubagentSpec("sub-1", "s-sub-1", "s", "r", "高铁", "上海→杭州，10/24 去 10/25 回，2 人，高铁", 1, null, false, agentType = "trip-transport")

        val input = subagentInput(spec, kind.copy(maxLookups = 5), subagentKindText(context, kind))

        assertTrue(input, input.contains("你的分工（trip-transport）：\n# 行程交通") && input.contains("一共最多 5 次") && input.endsWith("高铁"))
        assertFalse(input.contains("图片：https"))
    }

    @Test
    fun theTripSkillNamesATypeWhileItIsOnAndSpellsTheTaskOutWhileItIsOff() {
        val skills = loadRuntimeSkills(context)
        val on = preloadedSkillPrompt(context, skills, "去成都玩三天")

        assertTrue(on.contains("agent 写 trip-transport") && !on.contains("时刻表页常列出") && !on.contains("<!--"))
        saveSubagentKind(context, "trip-transport", false)
        val off = preloadedSkillPrompt(context, skills, "去成都玩三天")
        assertTrue(off.contains("时刻表页常列出") && !off.contains("agent 写 trip-transport") && off.contains("agent 写 trip-days"))
    }

    @Test
    fun aSectionIsKeptByItsKeyAndItsOppositeOtherwise() {
        val text = "a\n<!-- agent:x -->\nX\n<!-- /agent:x -->\n<!-- no-agent:x -->\nnot X\n<!-- /no-agent:x -->\nb"

        assertEquals("a\nX\nb", text.withSections(setOf("agent:x")))
        assertEquals("a\nnot X\nb", text.withSections(emptySet()))
        assertEquals(text.withSections(setOf("a2a")), text.withA2aSections(connected = true))
    }
}
