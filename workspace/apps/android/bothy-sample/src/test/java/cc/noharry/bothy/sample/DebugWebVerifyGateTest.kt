@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import cc.noharry.bothy.kernel.api.HookContext
import cc.noharry.bothy.kernel.api.HookDecision
import cc.noharry.bothy.kernel.api.ToolSource
import cc.noharry.bothy.kernel.api.ToolUse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugWebVerifyGateTest {
    private val context = HookContext(sessionId = "s", runId = "r", requestId = "q")
    private val verify = ToolUse("spawn_agent", """{"agent":"web-verify","task":"核实"}""", ToolSource.NATIVE_READ)

    @Test
    fun whatTheAgentFoundBySearchingIsNotSentToTheVerifier() = runBlocking {
        val gate = DebugWebVerifyGate("这周六深圳有哪些演出适合带孩子看，帮我确认下时间和票价")
        gate.onPreToolUse(ToolUse("mcp__car_ui__show_card", "{}", ToolSource.BACKEND), context)

        val decision = gate.onPreToolUse(verify, context)

        assertTrue(decision is HookDecision.Deny && "只回答一次" in decision.reason)
    }

    @Test
    fun aPlaceSearchOrANamedFlightOrTrainIsVerified() = runBlocking {
        val food = DebugWebVerifyGate("欢乐海岸附近有什么好吃的")
        food.onPreToolUse(ToolUse("mcp__device__navigation_poi_search", "{}", ToolSource.BACKEND), context)

        assertTrue(food.onPreToolUse(verify, context) is HookDecision.Allow)
        assertTrue(DebugWebVerifyGate("朋友坐CA3636过来，现在出发早不早").onPreToolUse(verify, context) is HookDecision.Allow)
        assertTrue(DebugWebVerifyGate("G7311几点到深圳北").onPreToolUse(verify, context) is HookDecision.Allow)
    }

    @Test
    fun otherSpawnsAndToolsAreLeftAlone() = runBlocking {
        val gate = DebugWebVerifyGate("帮我规划下周去杭州三天")

        assertTrue(gate.onPreToolUse(ToolUse("spawn_agent", """{"agent":"trip-stay"}""", ToolSource.NATIVE_READ), context) is HookDecision.Allow)
        assertTrue(gate.onPreToolUse(ToolUse("mcp__car_ui__show_card", "{}", ToolSource.BACKEND), context) is HookDecision.Allow)
    }

    @Test
    fun flightAndTrainNumbersAreFoundNextToChineseText() {
        assertTrue(debugNamesTrip("朋友坐CA3636过来"))
        assertTrue(debugNamesTrip("3U8888晚点了吗"))
        assertTrue(debugNamesTrip("坐G7311去广州"))
        assertFalse(debugNamesTrip("这周末深圳有什么活动"))
        assertFalse(debugNamesTrip("空调调到24度"))
        assertFalse(debugNamesTrip("车牌粤B12345"))
    }
}
