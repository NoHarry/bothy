@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.HookContext
import cc.noharry.agent.kernel.api.HookDecision
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolSource
import cc.noharry.agent.kernel.api.ToolUse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugActionGateTest {
    private val context = HookContext(sessionId = "s", runId = "r", requestId = "q")
    private val order = "mcp__luckin__createOrder"
    private val window = "mcp__device__home_window_position_set"

    private fun DebugActionGate.decide(tool: String) = runBlocking { onPreToolUse(ToolUse(tool, "{}", ToolSource.BACKEND), context) }

    @Test
    fun theRuleIsReadInTheUsersOwnWords() {
        val quoted = "$FOLLOW_UP_FRAME_HEADER\n… 已帮你建一条规则：客厅窗户打开的时候点一杯瑞幸。要现在先开窗吗？\n" +
            "Bind the reply below to this message only.\nUser reply: 好"
        assertEquals("好", debugUserWords(quoted))
        assertEquals("", debugUserWords("用户在屏幕上点了第1项（原话：1）。\n绑定的操作已由宿主按工具定义核对：调用 x，参数 {}。"))
        assertEquals("窗户打开的时候点咖啡", debugUserWords("窗户打开的时候点咖啡"))
    }

    @Test
    fun aSubagentNeverOrdersWhateverItsTaskSays() {
        val child = DebugActionGate("确认下单：机场 GTC 店两杯生椰拿铁", child = true)
        assertTrue(child.decide(order) is HookDecision.Deny)
        assertTrue(child.decide("mcp__luckin__previewOrder") is HookDecision.Allow)
        assertTrue(DebugActionGate("【计划到点】点咖啡", child = true).decide(order) is HookDecision.Deny)
    }

    @Test
    fun aQuestionOrAHoldIsNoYesAndOneYesIsOneOrder() {
        val question = "$FOLLOW_UP_FRAME_HEADER\n… 一共 46 元，要下单吗？\nBind the reply below to this message only.\nUser reply: "
        listOf("大杯是多少钱", "要几分钟", "对了换燕麦奶", "先留着就行", "不要").forEach { reply ->
            assertTrue(reply, DebugActionGate(question + reply).decide(order) is HookDecision.Deny)
        }
        assertTrue(DebugActionGate(question + "好的，下单吧").decide(order) is HookDecision.Allow)
        assertTrue(DebugActionGate("不要直接下单，先给我看看").decide(order) is HookDecision.Deny)
        assertTrue(DebugActionGate("不用现在付").decide("mcp__shop__payOrder") is HookDecision.Deny)
        val once = DebugActionGate(question + "确认")
        assertTrue(once.decide(order) is HookDecision.Allow)
        assertTrue(once.decide(order) is HookDecision.Deny)
    }

    @Test
    fun anOrderWaitsForTheUsersYes() {
        val ask = DebugActionGate("顺便帮我点两杯生椰拿铁，到机场的瑞幸取")
        assertTrue(ask.decide(order) is HookDecision.Deny)
        assertTrue(ask.decide("mcp__luckin__previewOrder") is HookDecision.Allow)
        assertTrue(ask.decide("mcp__luckin__queryShopList") is HookDecision.Allow)
        assertTrue(ask.decide(window) is HookDecision.Allow)

        val question = "$FOLLOW_UP_FRAME_HEADER\n… 两杯生椰拿铁一共 46 元。要现在付款，还是先按这个留着？\n" +
            "Bind the reply below to this message only.\nUser reply: "
        assertTrue(DebugActionGate(question + "付吧").decide(order) is HookDecision.Allow)
        assertTrue(DebugActionGate(question + "先别付").decide(order) is HookDecision.Deny)
        val howOften = "$FOLLOW_UP_FRAME_HEADER\n… 客厅窗户打开时帮你下单一杯生椰拿铁。以后每次都这样，还是只这一次？\n" +
            "Bind the reply below to this message only.\nUser reply: "
        assertTrue(DebugActionGate(howOften + "只要一次").decide(order) is HookDecision.Deny)
        assertTrue(DebugActionGate(howOften + "这次直接下单吧").decide(order) is HookDecision.Allow)
        assertTrue(DebugActionGate("帮我点杯生椰拿铁，直接下单").decide(order) is HookDecision.Allow)
        val pick = "用户在屏幕上点了第1项（原话：1）。\n绑定的操作已由宿主按工具定义核对：调用 $order，参数 {}。"
        assertTrue(DebugActionGate(pick).decide(order) is HookDecision.Allow)
    }

    @Test
    fun aFiredPlanDoesWhatTheUserApprovedWhenMakingIt() {
        val fired = DebugActionGate("【计划到点】用户之前安排的计划「点咖啡」（到达公司时）到了，现在去办：点一杯咖啡。")

        assertTrue(fired.decide(order) is HookDecision.Allow)
        assertTrue(fired.decide(window) is HookDecision.Allow)
    }

    @Test
    fun ordersAndPaymentsAreToldFromPreviewsAndReadings() {
        assertTrue(isTransaction("mcp__luckin__createOrder"))
        assertTrue(isTransaction("mcp__shop__submit_order"))
        assertTrue(isTransaction("mcp__shop__payOrder"))
        assertFalse(isTransaction("mcp__luckin__previewOrder"))
        assertFalse(isTransaction("mcp__luckin__queryOrderList"))
        assertFalse(isTransaction("mcp__device__home_window_position_set"))
    }

    @Test
    fun anOrderToolSaysItWaitsForTheUsersPick() {
        val other = ToolDescriptor("weather_query", "查询天气。", "{}")
        assertEquals(other, other.withSpokenHint())
        val order = ToolDescriptor("createOrder", "瑞幸咖啡创建订单", "{}")
        assertTrue(order.withSpokenHint().description.contains("调用成功之前不要说已经下单"))
        assertEquals(ToolDescriptor("previewOrder", "瑞幸咖啡订单预览", "{}"), ToolDescriptor("previewOrder", "瑞幸咖啡订单预览", "{}").withSpokenHint())
    }

    @Test
    fun theWatchTrailHoldsBackRepeatsButNotFirings() {
        var now = 0L
        val lines = mutableListOf<String>()
        val trail = DebugWatchTrail(nowMillis = { now }, log = { lines += it })

        trail.note("signal speed", "push 60")
        now += 1_000
        trail.note("signal speed", "push 61")
        trail.always("「拿蛋糕」 type=2 met: FIRE")
        now += 10_000
        trail.note("signal speed", "push 62")

        assertEquals(listOf("push 60", "「拿蛋糕」 type=2 met: FIRE", "push 62 (+1 since)"), lines)
        assertNull(lines.firstOrNull { it.contains("61") })
        // What reaches the device's log: numbers and switches, never a destination's name.
        val reading = Json.parseToJsonElement("""{"type":2,"poi":{"name":"深圳北站"},"name":"公司","on":true}""").jsonObject
        assertEquals("{type=2 poi=‹…› name=‹text› on=true}", reading.loggable())
        assertEquals("‹text›", "深圳北站".loggable())
        assertEquals("20.5", "20.5".loggable())
    }
}

class DebugUniqueKeysTest {
    @Test
    fun aRepeatedRowKeyGetsANumberInsteadOfCrashingTheList() {
        val repeats = mutableListOf<List<String>>()

        val keys = listOf("item-1", "item-2", "item-1", "item-1").uniqueKeys { repeats += it }

        assertEquals(listOf("item-1", "item-2", "item-1#2", "item-1#3"), keys)
        assertEquals(listOf(listOf("item-1")), repeats)
        assertEquals(listOf("a", "b"), listOf("a", "b").uniqueKeys { error("no repeats") })
    }
}
