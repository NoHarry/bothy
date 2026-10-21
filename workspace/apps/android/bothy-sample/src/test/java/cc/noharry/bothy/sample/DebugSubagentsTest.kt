@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import cc.noharry.bothy.kernel.api.HookContext
import cc.noharry.bothy.kernel.api.HookDecision
import cc.noharry.bothy.kernel.api.ToolSource
import cc.noharry.bothy.kernel.api.ToolUse
import cc.noharry.bothy.kernel.api.model.ModelRef
import cc.noharry.bothy.runtime.SubagentReport
import cc.noharry.bothy.runtime.SubagentSpec
import cc.noharry.bothy.runtime.SubagentState
import cc.noharry.bothy.runtime.SubagentStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugSubagentsTest {
    private val context = HookContext(sessionId = "s", runId = "r", requestId = "q")

    @Test
    fun byDefaultAChildQueriesTheDeviceButNeitherOperatesItNorTalksToTheUser() = runBlocking {
        val guard = DebugSubagentGuard(allowDeviceControl = false)

        assertTrue(guard.decide("mcp__tools_json__command_cmd_QueryActiveAudioTrack") is HookDecision.Allow)
        assertTrue(guard.decide("web_fetch", ToolSource.NETWORK) is HookDecision.Allow)
        assertTrue(guard.decide("mcp__tools_json__command_ac_TemperatureAdjust") is HookDecision.Deny)
        assertTrue(guard.decide("mcp__tools_json__window_open") is HookDecision.Deny)
        assertTrue(guard.decide("mcp__car_ui__show_card") is HookDecision.Deny)
        assertTrue(guard.decide("ask", ToolSource.INTERACTION) is HookDecision.Deny)
    }

    @Test
    fun aChildStopsLookingThingsUpAfterItsShare() = runBlocking {
        val guard = DebugSubagentGuard(allowDeviceControl = false)

        repeat(MAX_CHILD_LOOKUPS) { assertTrue(guard.decide(if (it % 2 == 0) "web_fetch" else "web_search", ToolSource.NETWORK) is HookDecision.Allow) }
        val spent = guard.decide("web_fetch", ToolSource.NETWORK)

        assertTrue(spent is HookDecision.Deny && (spent as HookDecision.Deny).reason.contains("finish"))
        // Its other tools, and another child's lookups, are not counted.
        assertTrue(guard.decide("mcp__tools_json__command_cmd_QueryActiveAudioTrack") is HookDecision.Allow)
        assertTrue(DebugSubagentGuard(allowDeviceControl = false).decide("web_fetch", ToolSource.NETWORK) is HookDecision.Allow)
        // A typed child has its own share.
        val typed = DebugSubagentGuard(allowDeviceControl = false, maxLookups = 2)
        repeat(2) { assertTrue(typed.decide("web_search", ToolSource.NETWORK) is HookDecision.Allow) }
        assertTrue((typed.decide("web_search", ToolSource.NETWORK) as HookDecision.Deny).reason.startsWith("已经查了 2 次"))
    }

    @Test
    fun aTypedChildCallsOnlyItsTypesTools() = runBlocking {
        val tools = setOf(DebugSubagentToolGroup.SEARCH, DebugSubagentToolGroup.FETCH, DebugSubagentToolGroup.DEVICE_QUERY)
        val guard = DebugSubagentGuard(allowDeviceControl = true, maxLookups = 1, tools = tools, kindId = "trip-transport")

        val write = guard.decide("write_file", ToolSource.NATIVE_WRITE)
        assertTrue(write is HookDecision.Deny && (write as HookDecision.Deny).reason.startsWith("这个子 agent（trip-transport）不能用「写文件」"))
        assertTrue(guard.decide("mcp__amap__route_plan") is HookDecision.Deny)
        // Allowed by the settings, but not this type's to do.
        assertTrue(guard.decide("mcp__tools_json__command_ac_TemperatureAdjust") is HookDecision.Deny)
        assertTrue(guard.decide("mcp__tools_json__command_cmd_QueryActiveAudioTrack") is HookDecision.Allow)
        // What every child has.
        assertTrue(guard.decide("search_tools", ToolSource.NATIVE_READ) is HookDecision.Allow)
        assertTrue(guard.decide("finish", ToolSource.TERMINAL) is HookDecision.Allow)
        // A refused tool spends none of the share.
        assertTrue(guard.decide("web_fetch", ToolSource.NETWORK) is HookDecision.Allow)
        assertTrue(guard.decide("web_search", ToolSource.NETWORK) is HookDecision.Deny)
    }

    @Test
    fun toolsAreGroupedByWhatTheyDo() {
        assertEquals(DebugSubagentToolGroup.READ, debugSubagentToolGroup("grep"))
        assertEquals(DebugSubagentToolGroup.TODO, debugSubagentToolGroup("update_todo"))
        assertEquals(DebugSubagentToolGroup.DEVICE_CONTROL, debugSubagentToolGroup("mcp__tools_json__window_open"))
        assertEquals(DebugSubagentToolGroup.DEVICE_QUERY, debugSubagentToolGroup("mcp__tools_json__get_seat_status"))
        assertEquals(DebugSubagentToolGroup.SERVICES, debugSubagentToolGroup("mcp__memory__user_memory"))
        assertNull(debugSubagentToolGroup("mcp__car_ui__show_card"))
        assertNull(debugSubagentToolGroup("mcp__experimental_a2a_agent__a2a_message_send"))
        assertNull(debugSubagentToolGroup("spawn_agent"))
    }

    @Test
    fun deviceControlCanBeAllowedButCardsAndQuestionsStayWithTheMainAgent() = runBlocking {
        val guard = DebugSubagentGuard(allowDeviceControl = true)

        assertTrue(guard.decide("mcp__tools_json__command_ac_TemperatureAdjust") is HookDecision.Allow)
        assertTrue(guard.decide("mcp__plans__plan_create") is HookDecision.Deny)
        assertTrue(guard.decide("ask_async", ToolSource.INTERACTION) is HookDecision.Deny)
    }

    @Test
    fun aConversationWithBackgroundWorkIsKeptOnWake() {
        val idle = DebugSessionSummary("s", "t", updatedAtEpochMillis = 0L, turnCount = 3, compactedSummary = "")
        val later = DEBUG_WAKE_NEW_SESSION_IDLE_MILLIS + 1

        assertTrue(debugWakeStartsNewSession(idle, later))
        assertFalse(debugWakeStartsNewSession(idle, later, backgroundWork = true))
    }

    @Test
    fun theModelSettingIsProviderModelAndOptionalVariantAndBlankInherits() {
        assertEquals(ModelRef("anthropic", "deepseek-flash", "none"), DebugSubagentSettings(model = "anthropic:deepseek-flash:none").toConfig().modelRef)
        assertEquals(ModelRef("openai", "gpt-5", null), DebugSubagentSettings(model = "openai:gpt-5").toConfig().modelRef)
        assertNull(DebugSubagentSettings(model = "").toConfig().modelRef)
        assertFalse(DebugSubagentSettings().toConfig().allowDeviceControl)
    }

    @Test
    fun aLateResultBecomesATurnThatHandsTheAnswerToTheMainAgent() {
        val status = SubagentStatus(
            id = "sub-1", label = "苏州天气", state = SubagentState.COMPLETED, startedAtEpochMillis = 0L,
            lastActivityEpochMillis = 40_000L, finishedAtEpochMillis = 42_000L, toolCalls = 3, latest = "",
            result = "明天晴，22度", error = "",
        )
        val report = SubagentReport("s", listOf(status)).toDebugReport(now = 50_000L)

        assertEquals("后台任务「苏州天气」完成了", report.display)
        assertTrue(report.model, report.model.contains("用时 42 秒") && report.model.contains("明天晴，22度"))
        assertTrue(report.model, report.model.contains("不用再调用 wait_agent") && report.model.contains("不要用「好的」"))

        // A batch is one turn naming every child; a sibling still at work is named, not waited for.
        val other = status.copy(id = "sub-2", label = "杭州天气", result = "明天多云")
        val batch = SubagentReport("s", listOf(status, other)).toDebugReport(stillWorking = listOf(other.copy(id = "sub-3", label = "南京天气", state = SubagentState.RUNNING)))
        assertEquals("后台任务「苏州天气」「杭州天气」都完成了", batch.display)
        assertTrue(batch.model, batch.model.contains("明天多云") && batch.model.contains("还在进行：「南京天气」（sub-3）"))
        // 图文: pictures a child found go into the summary card.
        assertFalse(batch.model.contains("图文卡片"))
        val pictured = SubagentReport("s", listOf(status.copy(result = "草莓音乐节\n图片：https://img.example.com/a.jpg"))).toDebugReport()
        assertTrue(pictured.model, pictured.model.contains("图文卡片"))
        // 网上核实 is relayed in two sentences at most, the details on the card.
        val verified = SubagentReport("s", listOf(status.copy(agentType = WEB_VERIFY_KIND))).toDebugReport()
        assertTrue(verified.model, verified.model.contains("最多说两句短话") && verified.model.contains("show_card") && !verified.model.contains("用一段话"))
        val input = debugSubagentInput(SubagentSpec("sub-1", "s-sub-1", "s", "r", "苏州天气", "查苏州明天的天气", 1, null, false))
        assertTrue(input.contains("不能操作设备") && input.endsWith("查苏州明天的天气"))
        assertTrue(input.contains("web_fetch") && input.contains("图片："))
    }

    @Test
    fun switchingSubagentsOffHidesTheirToolsAndPromptsTheRest() {
        val tools = listOf(DebugBasicTool("spawn_agent", ""), DebugBasicTool("wait_agent", ""), DebugBasicTool("read_file", ""))

        assertEquals(tools, tools.withSubagentSwitch(enabled = true))
        val off = tools.withSubagentSwitch(enabled = false)
        assertEquals(setOf("spawn_agent", "wait_agent"), disabledDebugBasicTools(off))
        assertTrue(debugBasicToolPrompt(off).contains("spawn_agent"))
        assertTrue(DebugSubagentSettings().enabled)
    }

    @Test
    fun onlyStateChangesBecomeTranscriptAndLogLines() {
        assertEquals(DebugSubagentChange.STARTED, debugSubagentChange(null, SubagentState.RUNNING))
        assertNull(debugSubagentChange(SubagentState.RUNNING, SubagentState.RUNNING))
        assertEquals(DebugSubagentChange.STALLED, debugSubagentChange(SubagentState.RUNNING, SubagentState.STALLED))
        assertEquals(DebugSubagentChange.RESUMED, debugSubagentChange(SubagentState.STALLED, SubagentState.RUNNING))
        assertEquals(DebugSubagentChange.FINISHED, debugSubagentChange(SubagentState.STALLED, SubagentState.TIMED_OUT))

        val done = SubagentStatus(
            id = "sub-2", label = "上海油价", state = SubagentState.COMPLETED, startedAtEpochMillis = 0L,
            lastActivityEpochMillis = 5_000L, finishedAtEpochMillis = 5_500L, toolCalls = 1, latest = "", result = "8.57",
            error = "", modelCalls = 1, inputTokens = 27_376, cachedTokens = 20_608, outputTokens = 639,
        )
        assertEquals("5s · 工具 1 次 · 模型 1 次 · 输入 27376 缓存 75% · 输出 639", done.logLine())
        assertEquals("后台任务「上海油价」完成：5s · 工具 1 次 · 模型 1 次 · 输入 27376 缓存 75% · 输出 639", done.transcriptLine(DebugSubagentChange.FINISHED))
    }

    private suspend fun DebugSubagentGuard.decide(name: String, source: ToolSource = ToolSource.BACKEND) =
        onPreToolUse(ToolUse(name, "{}", source), context)
}
