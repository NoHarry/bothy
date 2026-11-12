@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.server.tools

import cc.noharry.agent.spi.HookContext
import cc.noharry.agent.spi.HookDecision
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolSource
import cc.noharry.agent.spi.ToolUse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionGateTest {
    private val context = HookContext(sessionId = "s", runId = "r", requestId = "q")
    private val order = "mcp__luckin__createOrder"
    private val window = "mcp__device__home_window_position_set"

    private fun ActionGate.decide(tool: String) = runBlocking { onPreToolUse(ToolUse(tool, "{}", ToolSource.BACKEND), context) }

    @Test
    fun aSubagentNeverOrdersWhateverItsTaskSays() {
        val child = ActionGate("确认下单：机场 GTC 店两杯生椰拿铁", child = true)
        assertTrue(child.decide(order) is HookDecision.Deny)
        assertTrue(child.decide("mcp__luckin__previewOrder") is HookDecision.Allow)
        assertTrue(ActionGate("【计划到点】点咖啡", child = true).decide(order) is HookDecision.Deny)
    }

    @Test
    fun aQuestionOrAHoldIsNoYesAndOneYesIsOneOrder() {
        val question = "一共 46 元，要下单吗？"
        listOf("大杯是多少钱", "要几分钟", "对了换燕麦奶", "先留着就行", "不要").forEach { reply ->
            assertTrue(reply, ActionGate(reply, previousQuestion = question).decide(order) is HookDecision.Deny)
        }
        assertTrue(ActionGate("好的，下单吧", previousQuestion = question).decide(order) is HookDecision.Allow)
        assertTrue(ActionGate("不要直接下单，先给我看看").decide(order) is HookDecision.Deny)
        assertTrue(ActionGate("不用现在付").decide("mcp__shop__payOrder") is HookDecision.Deny)
        val once = ActionGate("确认", previousQuestion = question)
        assertTrue(once.decide(order) is HookDecision.Allow)
        assertTrue(once.decide(order) is HookDecision.Deny)
    }

    @Test
    fun anOrderWaitsForTheUsersYes() {
        val ask = ActionGate("顺便帮我点两杯生椰拿铁，到机场的瑞幸取")
        assertTrue(ask.decide(order) is HookDecision.Deny)
        assertTrue(ask.decide("mcp__luckin__previewOrder") is HookDecision.Allow)
        assertTrue(ask.decide("mcp__luckin__queryShopList") is HookDecision.Allow)
        assertTrue(ask.decide(window) is HookDecision.Allow)

        val question = "两杯生椰拿铁一共 46 元。要现在付款，还是先按这个留着？"
        assertTrue(ActionGate("付吧", previousQuestion = question).decide(order) is HookDecision.Allow)
        assertTrue(ActionGate("先别付", previousQuestion = question).decide(order) is HookDecision.Deny)
        val howOften = "客厅窗户打开时帮你下单一杯生椰拿铁。以后每次都这样，还是只这一次？"
        assertTrue(ActionGate("只要一次", previousQuestion = howOften).decide(order) is HookDecision.Deny)
        assertTrue(ActionGate("这次直接下单吧", previousQuestion = howOften).decide(order) is HookDecision.Allow)
        assertTrue(ActionGate("帮我点杯生椰拿铁，直接下单").decide(order) is HookDecision.Allow)
        val pick = "用户在屏幕上点了第1项（原话：1）。\n绑定的操作已由宿主按工具定义核对：调用 $order，参数 {}。"
        assertTrue(ActionGate(pick).decide(order) is HookDecision.Allow)
        // The same pick as an English persona's frame words it.
        val englishPick = "The user picked option 1, \"Two lattes\", from your last reply's options (their words: Option 1).\n" +
            "The host checked the bound actions against the tools' definitions: call $order with {}.\n"
        assertTrue(ActionGate(englishPick).decide(order) is HookDecision.Allow)
    }

    @Test
    fun aFiredPlanDoesWhatTheUserApprovedWhenMakingIt() {
        val fired = ActionGate("【计划到点】用户之前安排的计划「点咖啡」（到达公司时）到了，现在去办：点一杯咖啡。")

        assertTrue(fired.decide(order) is HookDecision.Allow)
        assertTrue(fired.decide(window) is HookDecision.Allow)
    }

    @Test
    fun anOrderToolSaysItWaitsForTheUsersPick() {
        val other = ToolDescriptor("weather_query", "查询天气。", "{}")
        assertEquals(other, BothyRuns.describeTool(other))
        val order = ToolDescriptor("createOrder", "瑞幸咖啡创建订单", "{}")
        assertTrue(BothyRuns.describeTool(order).description.contains("调用成功之前不要说已经下单"))
        val preview = ToolDescriptor("previewOrder", "瑞幸咖啡订单预览", "{}")
        assertEquals(preview, BothyRuns.describeTool(preview))
    }
}
