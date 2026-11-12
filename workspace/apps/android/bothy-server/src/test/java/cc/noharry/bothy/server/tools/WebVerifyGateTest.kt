@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.server.tools
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.agent.appserver.server.PluginHost
import cc.noharry.agent.appserver.server.PluginSwitches
import cc.noharry.bothy.server.server.bothyConfig
import cc.noharry.agent.spi.HookContext
import cc.noharry.agent.spi.HookDecision
import cc.noharry.agent.spi.ToolSource
import cc.noharry.agent.spi.ToolUse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WebVerifyGateTest {
    private val context = HookContext(sessionId = "s", runId = "r", requestId = "q")
    private val verify = ToolUse("spawn_agent", """{"agent":"web-verify","task":"核实"}""", ToolSource.NATIVE_READ)

    @Test
    fun whatTheAgentFoundBySearchingIsNotSentToTheVerifier() = runBlocking {
        val gate = WebVerifyGate("这周六深圳有哪些演出适合带孩子看，帮我确认下时间和票价")
        // A card is no service: Bothy's cards plugin, running, names its tool server as the main agent's own.
        val cards = PluginHost(ApplicationProvider.getApplicationContext(), bothyConfig().plugins.filter { it.id == "cards" }, AllOn())
        cards.startAll()
        AppServerTesting.running(cards.additions)
        try {
            gate.onPreToolUse(ToolUse("mcp__app_ui__show_card", "{}", ToolSource.BACKEND), context)
        } finally {
            cards.setEnabled("cards", false)
            AppServerTesting.reset()
        }

        val decision = gate.onPreToolUse(verify, context)

        assertTrue(decision is HookDecision.Deny && "只回答一次" in decision.reason)
    }

    @Test
    fun aPlaceSearchOrANamedFlightOrTrainIsVerified() = runBlocking {
        val food = WebVerifyGate("欢乐海岸附近有什么好吃的")
        food.onPreToolUse(ToolUse("mcp__device__navigation_poi_search", "{}", ToolSource.BACKEND), context)

        assertTrue(food.onPreToolUse(verify, context) is HookDecision.Allow)
        assertTrue(WebVerifyGate("朋友坐CA3636过来，现在出发早不早").onPreToolUse(verify, context) is HookDecision.Allow)
        assertTrue(WebVerifyGate("G7311几点到深圳北").onPreToolUse(verify, context) is HookDecision.Allow)
    }

    @Test
    fun otherSpawnsAndToolsAreLeftAlone() = runBlocking {
        val gate = WebVerifyGate("帮我规划下周去杭州三天")

        assertTrue(gate.onPreToolUse(ToolUse("spawn_agent", """{"agent":"trip-stay"}""", ToolSource.NATIVE_READ), context) is HookDecision.Allow)
        assertTrue(gate.onPreToolUse(ToolUse("mcp__app_ui__show_card", "{}", ToolSource.BACKEND), context) is HookDecision.Allow)
    }

    @Test
    fun flightAndTrainNumbersAreFoundNextToChineseText() {
        assertTrue(namesTrip("朋友坐CA3636过来"))
        assertTrue(namesTrip("3U8888晚点了吗"))
        assertTrue(namesTrip("坐G7311去广州"))
        assertFalse(namesTrip("这周末深圳有什么活动"))
        assertFalse(namesTrip("空调调到24度"))
        assertFalse(namesTrip("车牌粤B12345"))
    }

    private class AllOn : PluginSwitches {
        private val off = mutableSetOf<String>()

        override fun isOn(id: String): Boolean = id !in off

        override fun set(id: String, on: Boolean) {
            if (on) off -= id else off += id
        }
    }
}
