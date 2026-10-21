package cc.noharry.bothy.sample

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.bothy.kernel.core.MAX_HOST_SYSTEM_PROMPT_BYTES
import cc.noharry.bothy.kernel.core.validateHostSystemPrompt
import cc.noharry.bothy.vfs.VfsReadResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal const val MIXED_BROADCAST_TEXT = "介绍下你自己，为什么叫这个名字，我想去深圳北站，在那找一个停车场，中间去一下广州塔附近的肯德基吃个饭，不走高速，出了肯德基之后找一下沿途的加油站我去加个油然后继续去深圳北站，开始导航后放首周杰伦的歌"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DebugRuntimePromptTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        listOf("debug_runtime_skills", "debug_basic_tools").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun aTripRequestPreloadsItsSkillWithCompactHostInstructions() = runBlocking {
        val skills = loadDebugRuntimeSkills(context).map { it.copy(enabled = it.id != "mcp-task-orchestration") }
        val bodies = debugPreloadedSkillBodies(context, skills, TRIP_TEXT)
        assertEquals(listOf("debug_voice_progress", "travel-planning"), bodies.map { it.first })
        val tools = loadDebugBasicTools(context)
        // Even a new session has nonempty continuity metadata in the production runner.
        val session = DebugSessionStore(File(context.cacheDir, "prompt-context")).modelContext("broadcast-test")
        assertTrue("Keep shared instructions under 4.5 KiB", debugSystemPrompt("").toByteArray().size <= 4608)
        val prompt = debugRuntimePrompt(session, tools, bodies).text
        validateHostSystemPrompt(prompt)
        assertTrue(prompt.contains(bodies.first().second))
        assertTrue(prompt.contains(bodies.last().second))
        assertFalse(prompt.contains("Deferred runtime skill"))
        val vfs = debugWorkspaceVfs(context, File(context.cacheDir, "prompt-skills"), skills)
        for ((id, body) in bodies) {
            val read = vfs.read("/.agents/skills/$id/SKILL.md", 0, 65536) as VfsReadResult.Found
            assertEquals(body, read.text)
            assertTrue(prompt.contains(body) || prompt.contains("Deferred runtime skill: $id."))
        }
        println("broadcast prompt bytes: ${prompt.toByteArray().size}")
        println("base=${debugSystemPrompt("").toByteArray().size}, context=${session.toByteArray().size}, tools=${debugBasicToolPrompt(tools).toByteArray().size}, skills=${bodies.map { it.first to it.second.toByteArray().size }}")
    }

    @Test fun everySkillCombinationAndLongMultibyteHistoryFitTheSameHostCap() {
        val all = loadDebugRuntimeSkills(context)
        val tools = loadDebugBasicTools(context)
        val input = "$MIXED_BROADCAST_TEXT，然后把音量调低、亮度调高"
        val history = "lastSuccessfulAction=previous\n" + (1..30).joinToString("\n") { "turn=$it " + "广州😀".repeat(70) }
        for (mask in 0 until (1 shl all.size)) {
            val skills = all.mapIndexed { index, skill -> skill.copy(enabled = mask and (1 shl index) != 0) }
            val bodies = debugPreloadedSkillBodies(context, skills, input)
            for (session in listOf("", "最近一轮：去广州塔。", history)) {
                val prompt = debugRuntimePrompt(session, tools, bodies).text
                validateHostSystemPrompt(prompt)
                assertFalse(prompt.contains('\uFFFD'))
                bodies.forEach { (id, body) ->
                    assertTrue("mask=$mask id=$id", prompt.contains(body) || prompt.contains("Deferred runtime skill: $id."))
                }
                if (session == history) {
                    assertTrue(prompt.contains("lastSuccessfulAction=previous"))
                    assertTrue(prompt.contains("Read /context/session/current.json"))
                }
            }
        }
    }

    @Test fun aTripStillPreloadsWithNormalContinuity() {
        val skills = loadDebugRuntimeSkills(context).map { it.copy(enabled = true) }.filter { it.id != "mcp-task-orchestration" }
        val bodies = debugPreloadedSkillBodies(context, skills, TRIP_TEXT)
        assertEquals(listOf("debug_voice_progress", "travel-planning"), bodies.map { it.first })
        val prompt = debugRuntimePrompt("lastSuccessfulAction=none", loadDebugBasicTools(context), bodies).text
        assertTrue(bodies.all { prompt.contains(it.second) })
        assertTrue(prompt.contains("lastSuccessfulAction=none"))
        assertFalse(prompt.contains("Deferred runtime skill"))
    }

    @Test fun voiceSnapshotIsReservedUnderHistoryPressureAndRemovedWhenDisabled() {
        val enabled = loadDebugRuntimeSkills(context)
        val bodies = debugPreloadedSkillBodies(context, enabled, "查目的地和附近停车场")
        val voice = bodies.single { it.first == "debug_voice_progress" }.second
        val tools = loadDebugBasicTools(context)
        val prompt = debugRuntimePrompt("历史内容\n".repeat(4000), tools, bodies).text
        validateHostSystemPrompt(prompt)
        assertTrue(prompt.contains(voice))
        assertTrue(prompt.contains("channel=speech, phase=progress"))
        assertFalse(prompt.contains("Deferred runtime skill: debug_voice_progress"))
        saveDebugRuntimeSkill(context, "debug_voice_progress", false)
        val disabled = debugRuntimePrompt("", tools, debugPreloadedSkillBodies(context, loadDebugRuntimeSkills(context), "")).text
        assertFalse(disabled.contains("TTS:"))
        assertFalse(disabled.contains("Active runtime skill: debug_voice_progress"))
        assertEquals(bodies, debugPreloadedSkillBodies(context, enabled, "查目的地和附近停车场"))
    }

    @Test fun oversizedUnicodeSkillIsDeferredWholeRatherThanTruncated() {
        val body = "完整约束😀".repeat(5000)
        val prompt = debugRuntimePrompt("", loadDebugBasicTools(context), listOf("large" to body)).text
        validateHostSystemPrompt(prompt)
        assertTrue(prompt.contains("/.agents/skills/large/SKILL.md"))
        assertFalse(prompt.contains("完整约束"))
    }

    @Test fun exactUtf8BoundaryFitsAndOneExtraByteDefersTheWholeSkill() {
        val tools = loadDebugBasicTools(context)
        val fixed = debugRuntimePrompt("", tools, emptyList()).text
        val remaining = MAX_HOST_SYSTEM_PROMPT_BYTES - (fixed + "\n\nActive runtime skill: boundary\n").toByteArray().size
        val body = "中".repeat(remaining / 3) + "a".repeat(remaining % 3)
        val exact = debugRuntimePrompt("", tools, listOf("boundary" to body)).text
        assertEquals(MAX_HOST_SYSTEM_PROMPT_BYTES, exact.toByteArray().size)
        assertTrue(exact.beforeThinkingLine().endsWith(body))
        val overflow = debugRuntimePrompt("", tools, listOf("boundary" to body + "a")).text
        assertTrue(overflow.contains("Deferred runtime skill: boundary"))
        assertFalse(overflow.contains(body))
    }

    @Test fun systemPartStaysTheSameAcrossRunsWhilePerRunPartsGoToRunContext() {
        val tools = loadDebugBasicTools(context)
        val skills = loadDebugRuntimeSkills(context).map { it.copy(enabled = true) }.filter { it.id != "mcp-task-orchestration" }
        val first = debugRuntimePrompt("", tools, debugPreloadedSkillBodies(context, skills, TRIP_TEXT))
        val previous = DebugPreviousExchange("s", "t", "导航去广州塔", "已开始导航", "COMPLETED", "SUCCEEDED")
        val later = debugRuntimePrompt("最近一轮：去广州塔。", tools, debugPreloadedSkillBodies(context, skills, "查天气"), previous)
        assertEquals(first.system, later.system)
        assertTrue(first.runContext.contains("Active runtime skill: travel-planning"))
        assertFalse(first.system.contains("travel-planning"))
        assertTrue(later.runContext.contains("最近一轮：去广州塔。"))
        assertTrue(later.runContext.beforeThinkingLine().endsWith(previous.prompt(true)))
        assertFalse(later.system.contains("Current debug session context"))
    }

    @Test fun aSkillCountsAsCarriedOnlyWhenAnEarlierRunSentItInFull() {
        val tools = loadDebugBasicTools(context)
        val skills = loadDebugRuntimeSkills(context).map { it.copy(enabled = true) }.filter { it.id != "mcp-task-orchestration" }
        val sent = debugRuntimePrompt("", tools, debugPreloadedSkillBodies(context, skills, TRIP_TEXT)).runContext
        val carried = listOf(cc.noharry.bothy.kernel.api.model.ModelMessage(role = "user", content = sent))
        assertTrue(carried.carriesSkill("travel-planning"))
        assertFalse(carried.carriesSkill("android-use"))
        val deferredOnly = listOf(cc.noharry.bothy.kernel.api.model.ModelMessage(role = "user", content = "Deferred runtime skill: android-use. Before using its workflow, read it."))
        assertFalse(deferredOnly.carriesSkill("android-use"))
    }

    @Test fun unavailableReadToolCannotSilentlyDiscardSkillInstructions() {
        val tools = loadDebugBasicTools(context).map { if (it.name == "read_file") it.copy(enabled = false) else it }
        val error = assertThrows(IllegalArgumentException::class.java) {
            debugRuntimePrompt("", tools, listOf("large" to "中文".repeat(10000))).text
        }
        assertTrue(error.message!!.contains("Enable read_file"))
        assertTrue(debugRuntimePrompt("", tools, listOf("small" to "完整内容")).text.contains("完整内容"))
    }

    @Test
    fun theClockGoesWithTheRunContextNotTheSystemPrompt() {
        val noon = java.time.ZonedDateTime.of(2026, 10, 19, 11, 35, 0, 0, java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val clock = debugClockLine(noon, java.time.ZoneId.of("Asia/Shanghai"))

        val prompt = debugRuntimePrompt("", emptyList(), emptyList(), clock = clock)

        assertEquals("现在：2026-10-19 周一 11:35", clock)
        assertTrue(prompt.runContext.startsWith(clock))
        assertFalse(prompt.system.contains("现在："))
        assertEquals(debugRuntimePrompt("", emptyList(), emptyList()).system, prompt.system)
        assertTrue("the thinking is asked for in Chinese with the run, not in the cached system prompt", prompt.runContext.contains(THINK_IN_CHINESE))
        assertFalse(prompt.system.contains(THINK_IN_CHINESE))
    }

    @Test
    fun theVerifyRuleIsPartOfTheCachedSystemPromptOnlyWhileTheTypeIsOffered() {
        val with = debugRuntimePrompt("", emptyList(), emptyList(), verifyAgent = true)
        val without = debugRuntimePrompt("", emptyList(), emptyList())

        assertTrue(with.system.contains("agent=web-verify") && with.system.contains("do not wait_agent"))
        assertTrue(with.system.contains("speak at most two short sentences") && with.system.contains("details stay on the card"))
        assertFalse(without.system.contains("web-verify"))
        assertEquals(with.runContext, without.runContext)
    }

    @Test
    fun webAnswersAreCheckedInOneParallelStepAndNotSentToTheVerifier() {
        val both = debugRuntimePrompt("", emptyList(), emptyList(), verifyAgent = true, webSearch = true)
        val noSearch = debugRuntimePrompt("", emptyList(), emptyList(), verifyAgent = true)

        assertTrue(both.system.contains("several search calls in the same response") && both.system.contains("answer once"))
        assertTrue(both.system.contains("decide which fit today before you calculate, route today only those"))
        assertTrue(both.system.indexOf(WEB_SEARCH_RULE) < both.system.indexOf(WEB_VERIFY_RULE))
        assertTrue(WEB_VERIFY_RULE.contains("what you found by searching the web yourself"))
        // Navigation stays as fast as before: no verifier for a named destination, fuel or charging, or a route.
        assertTrue(WEB_VERIFY_RULE.contains("Never for navigating to a place the user named; fuel, charging, parking"))
        assertTrue(WEB_VERIFY_RULE.contains("in the same response as show_card"))
        assertFalse(noSearch.system.contains(WEB_SEARCH_RULE))
    }
}

private const val TRIP_TEXT = "下周去成都玩三天，帮我规划一下行程"

/** A run context without its closing line on the thinking's language ([THINK_IN_CHINESE]), which comes after everything. */
internal fun String.beforeThinkingLine(): String = removeSuffix(THINK_IN_CHINESE).trimEnd()
