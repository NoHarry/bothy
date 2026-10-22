package cc.noharry.bothy.sample

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.vfs.VfsReadResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DebugRuntimeFeatureConfigTest {
    private lateinit var context: Context

    @Before
    fun resetFixturePreferences() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("debug_jev_config", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("debug_runtime_skills", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.noBackupFilesDir, "jev-api-key").delete()
        markEmbeddedSeedsDone(context)
    }

    @Test
    fun defaultsEnableVoiceAndWorkflowSkillsButKeepJevAndOrchestrationOff() {
        assertFalse(loadDebugJevConfig(context).enabled)
        assertEquals(DEFAULT_ENABLED_SKILLS, loadDebugRuntimeSkills(context).filter { it.enabled }.map { it.id })
        saveDebugRuntimeSkill(context, "travel-planning", false)
        assertFalse("a saved choice overrides the default", loadDebugRuntimeSkills(context).single { it.id == "travel-planning" }.enabled)
        assertTrue(debugPreloadedSkillPrompt(context, loadDebugRuntimeSkills(context)).contains("Active runtime skill: debug_voice_progress"))
    }

    @Test
    fun keyIsSeparateFromPreferencesAndCanBeRetainedOrRemoved() {
        assertNull(saveDebugJevConfig(context, DebugJevConfig(enabled = true), "fixture-secret"))
        assertTrue(loadDebugJevConfig(context).hasApiKey)
        assertFalse(context.getSharedPreferences("debug_jev_config", Context.MODE_PRIVATE).all.toString().contains("fixture-secret"))
        assertNull(saveDebugJevConfig(context, loadDebugJevConfig(context).copy(model = "new-model")))
        assertEquals("fixture-secret", loadDebugJevKey(context))
        assertEquals("new-model", loadDebugJevConfig(context).model)
        assertNull(saveDebugJevConfig(context, loadDebugJevConfig(context).copy(enabled = false), clearKey = true))
        assertFalse(loadDebugJevConfig(context).hasApiKey)
        assertFalse(loadDebugJevConfig(context).enabled)
    }

    @Test
    fun invalidConfigurationCannotMutateStoredKeyOrEnablement() {
        assertNotNull(saveDebugJevConfig(context, DebugJevConfig(enabled = true)))
        assertNotNull(saveDebugJevConfig(context, DebugJevConfig(endpoint = "http://plain.example"), "fixture-key"))
        assertNotNull(DebugJevConfig(endpoint = "https://user:secret@example.com").validationError())
        assertNotNull(DebugJevConfig(minScore = Double.NaN).validationError())
        assertNotNull(DebugJevConfig(timeoutMillis = 50_000).validationError())
        assertNotNull(DebugJevConfig(maxTools = 25).validationError())
        assertEquals("", loadDebugJevKey(context))
        assertFalse(loadDebugJevConfig(context).enabled)
    }

    @Test
    fun disabledSkillsAreAbsentFromVfsAndExistingRuntimeSnapshotStaysFixed() = runBlocking {
        saveDebugRuntimeSkill(context, "mcp-task-orchestration", true)
        val snapshot = loadDebugRuntimeSkills(context)
        val active = debugWorkspaceVfs(context, File(context.cacheDir, "skills-active"), snapshot)
        saveDebugRuntimeSkill(context, "mcp-task-orchestration", false)
        saveDebugRuntimeSkill(context, "debug_voice_progress", false)
        val disabled = debugWorkspaceVfs(context, File(context.cacheDir, "skills-disabled"))
        val path = "/.agents/skills/mcp-task-orchestration/SKILL.md"
        assertTrue(active.read(path, 0, 20_000) is VfsReadResult.Found)
        assertFalse(disabled.read(path, 0, 20_000) is VfsReadResult.Found)
        assertFalse(disabled.read("/.agents/skills/debug_voice_progress/SKILL.md", 0, 20_000) is VfsReadResult.Found)
        assertTrue(debugPreloadedSkillPrompt(context, snapshot).contains("通用 MCP 任务编排"))
        assertEquals("", debugPreloadedSkillPrompt(context, loadDebugRuntimeSkills(context)))
    }

    @Test
    fun delegationSkillWaitsForAnEnabledA2aProfile() {
        context.getSharedPreferences("debug_a2a_client", Context.MODE_PRIVATE).edit().clear().commit()
        assertFalse(debugPreloadedSkillPrompt(context, loadDebugRuntimeSkills(context)).contains("personal-agent-delegation"))
        context.getSharedPreferences("debug_a2a_client", Context.MODE_PRIVATE).edit()
            .putStringSet("enabled_profile_ids", setOf("user-grok-bot")).commit()
        assertTrue(debugPreloadedSkillPrompt(context, loadDebugRuntimeSkills(context)).contains("Active runtime skill: personal-agent-delegation"))
        context.getSharedPreferences("debug_a2a_client", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun androidUseSkillWaitsForTheTools() {
        // No accessibility service is bound under Robolectric, so the tools are not mounted and the skill stays out.
        assertFalse(debugPreloadedSkillPrompt(context, loadDebugRuntimeSkills(context)).contains("Active runtime skill: android-use"))
    }

    @Test
    fun obsoleteScenarioSkillPreferenceCannotLoadOldInstructions() = runBlocking {
        context.getSharedPreferences("debug_runtime_skills", Context.MODE_PRIVATE).edit()
            .putBoolean("retired-trip-planning", true).commit()
        assertEquals(DEFAULT_ENABLED_SKILLS, loadDebugRuntimeSkills(context).filter { it.enabled }.map { it.id })
        val workspace = debugWorkspaceVfs(context, File(context.cacheDir, "skills-obsolete"))
        assertFalse(workspace.read("/.agents/skills/retired-trip-planning/SKILL.md", 0, 20_000) is VfsReadResult.Found)
    }

    @Test
    fun diagnosticTimeoutPersistsWithinBoundAndDefaultStaysFiveSeconds() {
        assertEquals(5_000L, loadDebugJevConfig(context).timeoutMillis)
        assertNull(saveDebugJevConfig(context, DebugJevConfig(timeoutMillis = 15_000)))
        assertEquals(15_000L, loadDebugJevConfig(context).timeoutMillis)
        assertNotNull(saveDebugJevConfig(context, DebugJevConfig(timeoutMillis = 15_001)))
        assertEquals(15_000L, loadDebugJevConfig(context).timeoutMillis)
    }

    @Test
    fun twoStageModeAndDomainThresholdPersistWithoutChangingExistingDefaults() {
        assertEquals(DebugJevMode.FLAT, loadDebugJevConfig(context).mode)
        assertNull(saveDebugJevConfig(context, DebugJevConfig(mode = DebugJevMode.TWO_STAGE, domainMinScore = 0.4)))
        assertEquals(DebugJevMode.TWO_STAGE, loadDebugJevConfig(context).mode)
        assertEquals(0.4, loadDebugJevConfig(context).domainMinScore, 0.0001)
        assertNotNull(saveDebugJevConfig(context, DebugJevConfig(domainMinScore = Double.NaN)))
        assertNotNull(saveDebugJevConfig(context, DebugJevConfig(domainMinScore = 1.1)))
        assertEquals(DebugJevMode.TWO_STAGE, loadDebugJevConfig(context).mode)
    }

    @Test
    fun subdomainModeRoundTripsWithoutChangingThresholds() {
        val config = DebugJevConfig(mode = DebugJevMode.TWO_STAGE_SUBDOMAINS)
        assertNull(saveDebugJevConfig(context, config))
        assertEquals(config, loadDebugJevConfig(context))
        assertEquals("two_stage_subdomains", loadDebugJevConfig(context).mode.wireName)
    }

    @Test
    fun allFourCombinationsPersistIndependently() {
        for (jev in listOf(false, true)) for (skill in listOf(false, true)) {
            assertNull(saveDebugJevConfig(context, DebugJevConfig(enabled = jev), "fixture-key"))
            saveDebugRuntimeSkill(context, "mcp-task-orchestration", skill)
            assertEquals(jev, loadDebugJevConfig(context).enabled)
            val skills = loadDebugRuntimeSkills(context)
            assertEquals(skill, skills.single { it.id == "mcp-task-orchestration" }.enabled)
            assertEquals(skill, debugPreloadedSkillPrompt(context, skills).contains("Active runtime skill: mcp-task-orchestration"))
            assertTrue(debugPreloadedSkillPrompt(context, skills).contains("Active runtime skill: debug_voice_progress"))
        }
    }
}

private val DEFAULT_ENABLED_SKILLS = listOf(
    "debug_voice_progress",
    // Loaded when the words plan a trip (「去成都玩三天」「订酒店」), see its runtime.json.
    "travel-planning",
    // On by default but preloaded only while an A2A profile is enabled (see delegationSkillWaitsForAnEnabledA2aProfile).
    "personal-agent-delegation",
    // On by default but preloaded only while the 操作屏幕 tools are mounted (see androidUseSkillWaitsForTheTools).
    "android-use",
)
