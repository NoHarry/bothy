@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.server.tools

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.plans.PLAN_TOOL
import cc.noharry.agent.appserver.runtime.RunAddOns
import cc.noharry.agent.appserver.runtime.RunFacts
import cc.noharry.agent.appserver.server.PluginHost
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.bothy.server.R
import cc.noharry.bothy.server.cards.BothyCards
import cc.noharry.bothy.server.model.BothyFixtureModel
import cc.noharry.bothy.server.server.BothyBuiltIns
import cc.noharry.bothy.server.server.bothyConfig
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
        // The rule comes with the pages plugin Bothy lists, which gives it only to a run the page can show in.
        val host = PluginHost(context as Application, bothyConfig().plugins.filter { it.id == "pages" })
        host.startAll()
        val lines = host.additions.contextLines.single()
        assertTrue(lines(facts(onScreen = true)).contains(PAGES_RULE))
        assertFalse(lines(facts(onScreen = false)).contains(PAGES_RULE))
        assertFalse(BothyRuns.contextLines(facts(onScreen = true)).contains(PAGES_RULE))
        assertTrue(RunAddOns.NONE.contextLines(facts(onScreen = true)).isEmpty())
    }

    @Test
    fun theCarsRequestsMustActAndBothysCardsAndPlansCloseWithANextStep() {
        assertTrue(BothyReplayCheck.requiresAction("导航去广州塔"))
        assertFalse(cc.noharry.agent.appserver.runtime.ReplayCheck.NONE.requiresAction("导航去广州塔"))
        assertEquals("", cc.noharry.agent.appserver.a2ui.CardHooks.NONE.closing())
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

    @Test
    fun bothyOffersEveryFeatureOfTheServersOwn() {
        assertTrue(BothyBuiltIns.run { fileWriting && web && scripts && subagents })
        // Drawn pages, plans, memory and pictures, built into the server before, are plugins Bothy lists; starting after
        // boot is its settings plugin's wish.
        val listed = listOf("cards", "pages", "plans", "memory", "images")
        assertTrue(bothyConfig().plugins.map { it.id }.containsAll(listed))
        // The model services (DeepSeek's extras among them), operating the screen and the device's tools come from the
        // runtime's agent-app-models, agent-android-use and agent-device-capabilities, unlisted, under the ids their
        // switches are kept by.
        assertEquals(
            listOf("android-use", "models", "device-tools"),
            cc.noharry.agent.appserver.server.PluginDiscovery.find(context as Application, bothyConfig()).map { it.id },
        )
        // Its debug tools and its files on each run, as it always had them.
        assertTrue(bothyConfig().diagnostics)
        assertEquals(cc.noharry.bothy.server.BuildConfig.DEBUG, bothyConfig().debugSockets)
        assertTrue(bothyConfig().builtIns === BothyBuiltIns)
        // The roots some head units' trimmed stores lack.
        assertEquals(R.raw.deepseek_cas, bothyConfig().extraTrustRoots)
        // The scene its devices' lessons and its voice skill's scenes are under, and the demo car as its simulated model.
        assertEquals("debug-console", bothyConfig().scene)
        assertTrue(bothyConfig().simulatedModel?.invoke() is BothyFixtureModel)
        // Each start allows everything, as it always did, and its own tools and skills are plain fields.
        assertEquals(cc.noharry.agent.appserver.protocol.ConfirmationMode.ALLOW_ALL_SESSION, bothyConfig().approvals)
        assertEquals(bothySkills(context).map { it.id }, bothyConfig().skills(context).map { it.id })
        assertTrue(bothyConfig().replayCheck === BothyReplayCheck)
    }

    private fun facts(types: Set<String> = emptySet(), onScreen: Boolean = false) =
        RunFacts(context, userInput = "帮我找家咖啡店", child = false, webSearch = false, subagentTypes = types, appInFront = onScreen)
}
