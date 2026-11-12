package cc.noharry.bothy.server.runtime
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.a2a.A2aPlugin
import cc.noharry.agent.appserver.a2a.A2aProfiles
import cc.noharry.agent.appserver.a2a.DIRECT_MODULE_ID
import cc.noharry.agent.appserver.a2a.loadA2aProfiles
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.agent.appserver.server.PluginHost
import cc.noharry.bothy.server.console.markEmbeddedSeedsDone
import cc.noharry.bothy.server.server.bothyConfig
import cc.noharry.bothy.server.tools.bothySkills
import cc.noharry.bothy.server.voice.JevConfig
import cc.noharry.bothy.server.voice.JevMode
import cc.noharry.bothy.server.voice.loadJevConfig
import cc.noharry.bothy.server.voice.loadJevKey
import cc.noharry.bothy.server.voice.saveJevConfig
import cc.noharry.bothy.server.voice.tts.validationError
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bothy's own settings beside its skills: Jev's connection and key, and which of Bothy's skills are on at first and
 * when they come into a run. How skills are switched, kept and carried is the core's (`RuntimeSkillSwitchesTest` in
 * agent-app-server).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RuntimeFeatureConfigTest {
    @After
    fun bareHost() = AppServerTesting.reset()

    private lateinit var context: Context

    @Before
    fun resetFixturePreferences() {
        AppServerTesting.install(bothyConfig())
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("debug_jev_config", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("app_server_runtime_skills", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.noBackupFilesDir, "jev-api-key").delete()
        markEmbeddedSeedsDone(context)
    }

    @Test
    fun defaultsEnableVoiceAndWorkflowSkillsButKeepJevAndOrchestrationOff() {
        assertFalse(loadJevConfig(context).enabled)
        assertEquals(DEFAULT_ENABLED_SKILLS, bothySkills(context).filter { it.enabled }.map { it.id })
        assertTrue(AppServerTesting.prompt(context, "").skills.any { it.first == "debug_voice_progress" })
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
    fun delegationSkillWaitsForAnEnabledA2aProfile() {
        // The A2A plugin running, as Bothy lists it, says when the person's own agent is connected.
        val plugins = PluginHost(context as Application, listOf(A2aPlugin())).apply { startAll() }
        AppServerTesting.running(plugins.additions)
        try {
            context.getSharedPreferences("app_server_a2a_client", Context.MODE_PRIVATE).edit().clear().commit()
            assertFalse(AppServerTesting.prompt(context, "").skills.any { it.first == "personal-agent-delegation" })
            A2aProfiles.save(context, "Grok Bot", DIRECT_MODULE_ID, "https://relay.example.com/a2a", allowLoopbackHttpForDebug = false)
            A2aProfiles.setEnabled(context, loadA2aProfiles(context).single { it.label == "Grok Bot" }.id, true)
            assertTrue(AppServerTesting.prompt(context, "").text.contains("Active runtime skill: personal-agent-delegation"))
            context.getSharedPreferences("app_server_a2a_client", Context.MODE_PRIVATE).edit().clear().commit()
        } finally {
            plugins.setEnabled("a2a", false)
        }
    }

    @Test
    fun androidUseSkillWaitsForTheTools() {
        // No accessibility service is bound under Robolectric, so the tools are not mounted and the skill stays out.
        assertFalse(AppServerTesting.prompt(context, "").skills.any { it.first == "android-use" })
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
    fun jevAndTheOrchestrationSkillGoTheirOwnWaysInAllFourCombinations() {
        // The skill's switch as a snapshot of Bothy's skills; keeping a switch is the core's (RuntimeSkillSwitchesTest).
        for (jev in listOf(false, true)) for (skill in listOf(false, true)) {
            assertNull(saveJevConfig(context, JevConfig(enabled = jev), "fixture-key"))
            val skills = bothySkills(context).map { if (it.id == "mcp-task-orchestration") it.copy(enabled = skill) else it }
            assertEquals(jev, loadJevConfig(context).enabled)
            val prompt = AppServerTesting.prompt(context, "", skills).text
            assertEquals(skill, prompt.contains("Active runtime skill: mcp-task-orchestration"))
            assertTrue(prompt.contains("Active runtime skill: debug_voice_progress"))
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
