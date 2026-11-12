@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.server.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.runtime.RunFacts
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.agent.runtime.RuntimeInspection.validateHostSystemPrompt
import cc.noharry.bothy.server.server.bothyConfig
import cc.noharry.bothy.server.tools.BothyRuns
import cc.noharry.bothy.server.tools.WEB_SEARCH_RULE
import cc.noharry.bothy.server.tools.WEB_VERIFY_KIND
import cc.noharry.bothy.server.tools.WEB_VERIFY_RULE
import cc.noharry.bothy.server.tools.bothySkills
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bothy's skills and rules where its model reads them (`AppServerTesting.prompt`): a trip carries its skill in full,
 * every combination of its skills fits the runtime's cap, its voice skill stays in the system prompt, and its rules on
 * the web and on 网上核实. How the server composes a prompt is the core's (`RuntimePromptTest` in agent-app-server).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RuntimePromptTest {
    private lateinit var context: Context

    @Before
    fun installBothy() {
        AppServerTesting.install(bothyConfig())
        context = ApplicationProvider.getApplicationContext()
        listOf("app_server_runtime_skills", "app_server_basic_tools").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @After
    fun bareHost() = AppServerTesting.reset()

    @Test fun theSystemPromptOpensWithXiaobosIdentity() {
        assertTrue(AppServerTesting.prompt(context, "你好").system.startsWith("You are 小博 (Xiaobo), the assistant of the Bothy sample app"))
    }

    @Test fun aTripRequestCarriesItsSkillInFull() {
        val prompt = AppServerTesting.prompt(context, TRIP_TEXT)
        assertEquals(listOf("debug_voice_progress", "travel-planning"), prompt.skills.map { it.first })
        validateHostSystemPrompt(prompt.text)
        assertTrue(prompt.skills.all { (_, body) -> prompt.text.contains(body) })
        assertFalse(prompt.text.contains("Deferred runtime skill"))
    }

    @Test fun everyCombinationOfBothysSkillsAndLongMultibyteHistoryFitTheHostCap() {
        val all = bothySkills(context)
        val input = "$MIXED_BROADCAST_TEXT，然后把音量调低、亮度调高"
        val history = "lastSuccessfulAction=previous\n" + (1..30).joinToString("\n") { "turn=$it " + "广州😀".repeat(70) }
        for (mask in 0 until (1 shl all.size)) {
            val skills = all.mapIndexed { index, skill -> skill.copy(enabled = mask and (1 shl index) != 0) }
            for (session in listOf("", "最近一轮：去广州塔。", history)) {
                val prompt = AppServerTesting.prompt(context, input, skills, session)
                validateHostSystemPrompt(prompt.text)
                assertFalse(prompt.text.contains('�'))
                prompt.skills.forEach { (id, body) ->
                    assertTrue("mask=$mask id=$id", prompt.text.contains(body) || prompt.text.contains("Deferred runtime skill: $id."))
                }
            }
        }
    }

    @Test fun aTripStillCarriesItsSkillWithNormalContinuity() {
        val skills = bothySkills(context).map { it.copy(enabled = true) }.filter { it.id != "mcp-task-orchestration" }
        val prompt = AppServerTesting.prompt(context, TRIP_TEXT, skills, history = "lastSuccessfulAction=none")
        assertEquals(listOf("debug_voice_progress", "travel-planning"), prompt.skills.map { it.first })
        assertTrue(prompt.skills.all { (_, body) -> prompt.text.contains(body) })
        assertTrue(prompt.text.contains("lastSuccessfulAction=none"))
        assertFalse(prompt.text.contains("Deferred runtime skill"))
    }

    @Test fun theVoiceSkillStaysInTheSystemPromptUnderHistoryPressureAndGoesWhenSwitchedOff() {
        val pressed = AppServerTesting.prompt(context, "查目的地和附近停车场", history = "历史内容\n".repeat(4000))
        val voice = pressed.skills.single { it.first == "debug_voice_progress" }.second
        validateHostSystemPrompt(pressed.text)
        assertTrue(pressed.system.contains(voice))
        assertTrue(pressed.text.contains("channel=speech, phase=progress"))
        assertFalse(pressed.text.contains("Deferred runtime skill: debug_voice_progress"))
        val off = bothySkills(context).map { if (it.id == "debug_voice_progress") it.copy(enabled = false) else it }
        val quiet = AppServerTesting.prompt(context, "", off).text
        assertFalse(quiet.contains("进度播报"))
        assertFalse(quiet.contains("Active runtime skill: debug_voice_progress"))
    }

    @Test
    fun theVerifyRuleIsBothysOnlyWhileTheTypeIsOffered() {
        val with = BothyRuns.systemRules(facts(verify = true)).joinToString("\n")
        val without = BothyRuns.systemRules(facts()).joinToString("\n")

        assertTrue(with.contains("agent=web-verify") && with.contains("do not wait_agent"))
        assertTrue(with.contains("speak at most two short sentences") && with.contains("details stay on the card"))
        assertFalse(without.contains("web-verify"))
    }

    @Test
    fun webAnswersAreCheckedInOneParallelStepAndNotSentToTheVerifier() {
        val both = BothyRuns.systemRules(facts(verify = true, search = true)).joinToString("\n")
        val noSearch = BothyRuns.systemRules(facts(verify = true)).joinToString("\n")

        assertTrue(both.contains("several search calls in the same response") && both.contains("answer once"))
        assertTrue(both.contains("decide which fit today before you calculate, route today only those"))
        assertTrue(both.indexOf(WEB_SEARCH_RULE) in 0 until both.indexOf(WEB_VERIFY_RULE))
        assertTrue(WEB_VERIFY_RULE.contains("what you found by searching the web yourself"))
        // Navigation stays as fast as before: no verifier for a named destination, fuel or charging, or a route.
        assertTrue(WEB_VERIFY_RULE.contains("Never for navigating to a place the user named; fuel, charging, parking"))
        assertTrue(WEB_VERIFY_RULE.contains("in the same response as show_card"))
        assertFalse(noSearch.contains(WEB_SEARCH_RULE))
    }

    private fun facts(verify: Boolean = false, search: Boolean = false) = RunFacts(
        context = ApplicationProvider.getApplicationContext(),
        userInput = "",
        child = false,
        webSearch = search,
        subagentTypes = if (verify) setOf(WEB_VERIFY_KIND) else emptySet(),
    )
}

private const val TRIP_TEXT = "下周去成都玩三天，帮我规划一下行程"
