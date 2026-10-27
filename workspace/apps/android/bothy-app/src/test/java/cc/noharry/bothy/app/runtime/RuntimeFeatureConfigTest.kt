package cc.noharry.bothy.app.runtime
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.vfs.VfsReadResult
import cc.noharry.bothy.app.console.markEmbeddedSeedsDone
import cc.noharry.bothy.app.skills.loadRuntimeSkills
import cc.noharry.bothy.app.skills.preloadedSkillPrompt
import cc.noharry.bothy.app.skills.saveRuntimeSkill
import cc.noharry.bothy.app.voice.JevConfig
import cc.noharry.bothy.app.voice.JevMode
import cc.noharry.bothy.app.voice.loadJevConfig
import cc.noharry.bothy.app.voice.loadJevKey
import cc.noharry.bothy.app.voice.saveJevConfig
import cc.noharry.bothy.app.voice.tts.validationError
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
class RuntimeFeatureConfigTest {
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
        assertFalse(loadJevConfig(context).enabled)
        assertEquals(DEFAULT_ENABLED_SKILLS, loadRuntimeSkills(context).filter { it.enabled }.map { it.id })
        saveRuntimeSkill(context, "travel-planning", false)
        assertFalse("a saved choice overrides the default", loadRuntimeSkills(context).single { it.id == "travel-planning" }.enabled)
        assertTrue(preloadedSkillPrompt(context, loadRuntimeSkills(context)).contains("Active runtime skill: debug_voice_progress"))
    }

    @Test
    fun keyIsSeparateFromPreferencesAndCanBeRetainedOrRemoved() {
        assertNull(saveJevConfig(context, JevConfig(enabled = true), "fixture-secret"))
        assertTrue(loadJevConfig(context).hasApiKey)
        assertFalse(context.getSharedPreferences("debug_jev_config", Context.MODE_PRIVATE).all.toString().contains("fixture-secret"))
        assertNull(saveJevConfig(context, loadJevConfig(context).copy(model = "new-model")))
        assertEquals("fixture-secret", loadJevKey(context))
        assertEquals("new-model", loadJevConfig(context).model)
        assertNull(saveJevConfig(context, loadJevConfig(context).copy(enabled = false), clearKey = true))
        assertFalse(loadJevConfig(context).hasApiKey)
        assertFalse(loadJevConfig(context).enabled)
    }

    @Test
    fun invalidConfigurationCannotMutateStoredKeyOrEnablement() {
        assertNotNull(saveJevConfig(context, JevConfig(enabled = true)))
        assertNotNull(saveJevConfig(context, JevConfig(endpoint = "http://plain.example"), "fixture-key"))
        assertNotNull(JevConfig(endpoint = "https://user:secret@example.com").validationError())
        assertNotNull(JevConfig(minScore = Double.NaN).validationError())
        assertNotNull(JevConfig(timeoutMillis = 50_000).validationError())
        assertNotNull(JevConfig(maxTools = 25).validationError())
        assertEquals("", loadJevKey(context))
        assertFalse(loadJevConfig(context).enabled)
    }

    @Test
    fun disabledSkillsAreAbsentFromVfsAndExistingRuntimeSnapshotStaysFixed() = runBlocking {
        saveRuntimeSkill(context, "mcp-task-orchestration", true)
        val snapshot = loadRuntimeSkills(context)
        val active = workspaceVfs(context, File(context.cacheDir, "skills-active"), snapshot)
        saveRuntimeSkill(context, "mcp-task-orchestration", false)
        saveRuntimeSkill(context, "debug_voice_progress", false)
        val disabled = workspaceVfs(context, File(context.cacheDir, "skills-disabled"))
        val path = "/.agents/skills/mcp-task-orchestration/SKILL.md"
        assertTrue(active.read(path, 0, 20_000) is VfsReadResult.Found)
        assertFalse(disabled.read(path, 0, 20_000) is VfsReadResult.Found)
        assertFalse(disabled.read("/.agents/skills/debug_voice_progress/SKILL.md", 0, 20_000) is VfsReadResult.Found)
        assertTrue(preloadedSkillPrompt(context, snapshot).contains("通用 MCP 任务编排"))
        assertEquals("", preloadedSkillPrompt(context, loadRuntimeSkills(context)))
    }

    @Test
    fun delegationSkillWaitsForAnEnabledA2aProfile() {
        context.getSharedPreferences("debug_a2a_client", Context.MODE_PRIVATE).edit().clear().commit()
        assertFalse(preloadedSkillPrompt(context, loadRuntimeSkills(context)).contains("personal-agent-delegation"))
        context.getSharedPreferences("debug_a2a_client", Context.MODE_PRIVATE).edit()
            .putStringSet("enabled_profile_ids", setOf("user-grok-bot")).commit()
        assertTrue(preloadedSkillPrompt(context, loadRuntimeSkills(context)).contains("Active runtime skill: personal-agent-delegation"))
        context.getSharedPreferences("debug_a2a_client", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun androidUseSkillWaitsForTheTools() {
        // No accessibility service is bound under Robolectric, so the tools are not mounted and the skill stays out.
        assertFalse(preloadedSkillPrompt(context, loadRuntimeSkills(context)).contains("Active runtime skill: android-use"))
    }

    @Test
    fun obsoleteScenarioSkillPreferenceCannotLoadOldInstructions() = runBlocking {
        context.getSharedPreferences("debug_runtime_skills", Context.MODE_PRIVATE).edit()
            .putBoolean("retired-trip-planning", true).commit()
        assertEquals(DEFAULT_ENABLED_SKILLS, loadRuntimeSkills(context).filter { it.enabled }.map { it.id })
        val workspace = workspaceVfs(context, File(context.cacheDir, "skills-obsolete"))
        assertFalse(workspace.read("/.agents/skills/retired-trip-planning/SKILL.md", 0, 20_000) is VfsReadResult.Found)
    }

    @Test
    fun diagnosticTimeoutPersistsWithinBoundAndDefaultStaysFiveSeconds() {
        assertEquals(5_000L, loadJevConfig(context).timeoutMillis)
        assertNull(saveJevConfig(context, JevConfig(timeoutMillis = 15_000)))
        assertEquals(15_000L, loadJevConfig(context).timeoutMillis)
        assertNotNull(saveJevConfig(context, JevConfig(timeoutMillis = 15_001)))
        assertEquals(15_000L, loadJevConfig(context).timeoutMillis)
    }

    @Test
    fun twoStageModeAndDomainThresholdPersistWithoutChangingExistingDefaults() {
        assertEquals(JevMode.FLAT, loadJevConfig(context).mode)
        assertNull(saveJevConfig(context, JevConfig(mode = JevMode.TWO_STAGE, domainMinScore = 0.4)))
        assertEquals(JevMode.TWO_STAGE, loadJevConfig(context).mode)
        assertEquals(0.4, loadJevConfig(context).domainMinScore, 0.0001)
        assertNotNull(saveJevConfig(context, JevConfig(domainMinScore = Double.NaN)))
        assertNotNull(saveJevConfig(context, JevConfig(domainMinScore = 1.1)))
        assertEquals(JevMode.TWO_STAGE, loadJevConfig(context).mode)
    }

    @Test
    fun subdomainModeRoundTripsWithoutChangingThresholds() {
        val config = JevConfig(mode = JevMode.TWO_STAGE_SUBDOMAINS)
        assertNull(saveJevConfig(context, config))
        assertEquals(config, loadJevConfig(context))
        assertEquals("two_stage_subdomains", loadJevConfig(context).mode.wireName)
    }

    @Test
    fun allFourCombinationsPersistIndependently() {
        for (jev in listOf(false, true)) for (skill in listOf(false, true)) {
            assertNull(saveJevConfig(context, JevConfig(enabled = jev), "fixture-key"))
            saveRuntimeSkill(context, "mcp-task-orchestration", skill)
            assertEquals(jev, loadJevConfig(context).enabled)
            val skills = loadRuntimeSkills(context)
            assertEquals(skill, skills.single { it.id == "mcp-task-orchestration" }.enabled)
            assertEquals(skill, preloadedSkillPrompt(context, skills).contains("Active runtime skill: mcp-task-orchestration"))
            assertTrue(preloadedSkillPrompt(context, skills).contains("Active runtime skill: debug_voice_progress"))
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
    // In the catalog only; read when 「回到车机」 needs more than playground_open's description.
    "playground",
)
