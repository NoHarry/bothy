@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.server.tools

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.plans.PLAN_TOOL
import cc.noharry.agent.appserver.runtime.RunAddOns
import cc.noharry.agent.appserver.runtime.RunFacts
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.bothy.server.cards.BothyCards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What Bothy adds to a run that the generic server leaves out ([RunAddOns.NONE]). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BothyRunsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun ordersWaitAndWebVerifyIsGatedOnlyWhileTheTypeIsOffered() {
        assertEquals(listOf(ActionGate::class), BothyRuns.runHooks(facts()).map { it::class })
        assertEquals(listOf(WebVerifyGate::class, ActionGate::class), BothyRuns.runHooks(facts(types = setOf(WEB_VERIFY_KIND))).map { it::class })
        assertTrue(RunAddOns.NONE.runHooks(facts(types = setOf(WEB_VERIFY_KIND))).isEmpty())
    }

    @Test
    fun aLongAnswerIsDrawnOnlyWhileThePageCanShow() {
        assertTrue(BothyRuns.contextLines(facts(pages = true)).contains(PAGES_RULE))
        assertFalse(BothyRuns.contextLines(facts()).contains(PAGES_RULE))
        assertTrue(RunAddOns.NONE.contextLines(facts(pages = true)).isEmpty())
    }

    @Test
    fun theCarsRequestsMustActAndBothysCardsAndPlansCloseWithANextStep() {
        assertTrue(BothyRuns.requiresAction("导航去广州塔"))
        assertFalse(RunAddOns.NONE.requiresAction("导航去广州塔"))
        assertEquals("", RunAddOns.NONE.cards(context).closing())
        assertTrue(BothyCards(context, cloudAssistant = false).closing().let { it.contains("提醒出发") && "云端助手" !in it })
        assertTrue(BothyCards(context, cloudAssistant = true).closing().contains("发给云端助手"))
        // The plan tool ends with when to offer a reminder rather than directions, as its description always did.
        val plans = BothyRuns.describeTool(ToolDescriptor(PLAN_TOOL, "不要答应提醒或自动执行却不创建计划。", "{}"))
        assertTrue(plans.description.startsWith("不要答应提醒或自动执行却不创建计划。推荐的去处、演出或活动离出发还早时"))
        assertEquals("不要答应提醒或自动执行却不创建计划。", RunAddOns.NONE.describeTool(ToolDescriptor(PLAN_TOOL, "不要答应提醒或自动执行却不创建计划。", "{}")).description)
        // 网上核实's report goes onto the card, two short sentences at most.
        val verify = BOTHY_SUBAGENT_KINDS.single { it.id == WEB_VERIFY_KIND }
        assertTrue(verify.reportClosing!!.contains("最多说两句短话") && verify.reportClosing!!.contains("show_card"))
    }

    private fun facts(types: Set<String> = emptySet(), pages: Boolean = false) =
        RunFacts(context, userInput = "帮我找家咖啡店", child = false, webSearch = false, subagentTypes = types, drawsPages = pages)
}
