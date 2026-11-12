package cc.noharry.bothy.server.server

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.androiduse.appserver.loadAndroidUseEnabled
import cc.noharry.agent.appserver.image.loadImageModelConfig
import cc.noharry.agent.appserver.mcp.McpSecrets
import cc.noharry.agent.appserver.mcp.loadMcpProviders
import cc.noharry.agent.appserver.mcp.loadMcpServers
import cc.noharry.agent.appserver.memory.MemoryPlugin
import cc.noharry.agent.appserver.memory.userMemoryEnabled
import cc.noharry.agent.appserver.model.FetchFallback
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.SearchBackend
import cc.noharry.agent.appserver.runtime.ProviderPresets
import cc.noharry.agent.appserver.server.AppServerConfig
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.agent.appserver.server.PluginHost
import cc.noharry.agent.appserver.server.PluginSwitches
import cc.noharry.agent.appserver.session.SessionSettings
import cc.noharry.agent.appserver.skills.loadSubagentSettings
import java.io.File
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
 * An install coming from v0.3.0, its data directory as that release left it (the names and keys of its code at the
 * v0.3.0 tag), started on this build: everything it kept is read through today's code, and nothing stays under an old name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class StoredNamesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val workspace get() = File(app.filesDir, "runtime-debug-workspace")

    @Before
    fun bothysPresets() {
        // The profiles file alone, as the server reads it: a key in a developer's local.properties would make any model ready.
        val presets = bothyPresets()
        AppServerTesting.install(AppServerConfig(name = "test", presets = ProviderPresets(configText = presets.configText, sections = presets.sections)))
    }

    @After
    fun bareHost() = AppServerTesting.reset()

    @Test
    fun theModelKeyMovesAndASetupThatWasDoneStaysDone() {
        seedV030ModelKey()
        prefs("debug_onboarding").edit().putBoolean("started", true).putBoolean("done", true).commit()

        StoredNames.migrate(app)

        val config = ProviderConfig.load(app)
        assertEquals("deepseek-anthropic", config.activeProfileId)
        assertEquals(V030_KEY, config.activeProfile.apiKey)
        assertTrue(config.isReady(config.activeProfile))
        assertFalse(Onboarding.needed(app))
        assertFalse(Onboarding.needed(app))
        assertNoOldNames()
    }

    @Test
    fun aSetupWhoseKeyDidNotComeOverShowsAgain() {
        // v0.3.0 kept the person's pick but no key (a file it could not read leaves the same): nothing runs without one.
        prefs("debug_provider_config").edit().putBoolean("manual_profile_enabled", true).putString("selected_profile_id", "deepseek-anthropic").commit()
        prefs("debug_onboarding").edit().putBoolean("started", true).putBoolean("done", true).commit()

        StoredNames.migrate(app)

        assertTrue(Onboarding.needed(app))
        assertNoOldNames()
    }

    @Test
    fun aSetupNeverFinishedIsNotCheckedAgainAndOneWithoutModelSettingsStaysDone() {
        prefs("debug_onboarding").edit().putBoolean("started", true).putBoolean("done", true).commit()
        prefs("debug_user_memory").edit().putBoolean("enabled", false).commit()

        StoredNames.migrate(app)

        assertFalse(Onboarding.needed(app))
    }

    @Test
    fun mcpServersTheirTokensAndTheConnectionSettingsAreReadWhereTheyMoved() {
        prefs("debug_mcp_servers").edit().putString("servers", """[{"id":"maps","label":"地图","kind":"HTTP","enabled":true,"url":"https://mcp.example.com/mcp"}]""").commit()
        File(app.noBackupFilesDir, "mcp-secrets/maps.json").apply { parentFile!!.mkdirs() }.writeText("""{"token":"tok-v030","headers":{},"query":{}}""")
        prefs("debug_mcp_provider_config").edit().putBoolean("mcp_provider_tools_json", true).commit()
        prefs("debug_search_backend").edit().putBoolean("deepseek-anthropic", true).commit()
        prefs("debug_fetch_fallback").edit().putBoolean("enabled", false).commit()
        prefs("debug_subagents").edit().putBoolean("allow_device_control", true).putBoolean("enabled", false).commit()
        prefs("debug_image_model").edit().putBoolean("enabled", true).putString("base_url", "https://images.example.com/v1").putString("model", "draw").commit()

        StoredNames.migrate(app)

        val server = loadMcpServers(app).single()
        assertEquals("maps" to "https://mcp.example.com/mcp", server.id to server.url)
        assertEquals(mapOf("Authorization" to "Bearer tok-v030"), McpSecrets.requestHeaders(app, "maps"))
        assertTrue(loadMcpProviders(app).first { it.id == "tools_json" }.enabled)
        assertTrue(SearchBackend.usesApi(app, "deepseek-anthropic"))
        assertFalse(FetchFallback.enabled(app))
        val subagents = loadSubagentSettings(app)
        assertTrue(subagents.allowDeviceControl && !subagents.enabled)
        assertEquals("draw", loadImageModelConfig(app).model)
        assertNoOldNames()
    }

    @Test
    fun switchesTurnedOffStayOff() {
        prefs("debug_user_memory").edit().putBoolean("enabled", false).commit()
        prefs("debug_android_use").edit().putBoolean("enabled", false).commit()
        prefs("debug_sessions").edit().putBoolean("new_session_every_wake", true).putInt("max_sessions", 50).commit()

        StoredNames.migrate(app)

        val plugins = PluginHost(app, listOf(MemoryPlugin()), AllOn())
        plugins.startAll()
        try {
            assertFalse(userMemoryEnabled(app))
        } finally {
            plugins.setEnabled("memory", false)
        }
        assertFalse(loadAndroidUseEnabled(app))
        SessionSettings.init(app)
        assertTrue(SessionSettings.policy.value.everyWake)
        assertEquals(50, SessionSettings.policy.value.maxSessions)
        assertNoOldNames()
    }

    @Test
    fun conversationsMoveIntoTheDirectoryTheServerListsUnchanged() {
        val meta = """{"schemaVersion":1,"sessionId":"debug-session-1","title":"导航回家","updatedAtEpochMillis":2,"turnCount":1,"compactedSummary":""}"""
        val turn = """{"schemaVersion":1,"turnId":"t1","sessionId":"debug-session-1","userText":"导航回家","finalResponse":"好的"}"""
        File(workspace, "debug-sessions/debug-session-1/meta.json").apply { parentFile!!.mkdirs() }.writeText(meta)
        File(workspace, "debug-sessions/debug-session-1/turns.jsonl").writeText(turn + "\n")
        File(workspace, "debug-sessions/index.json").writeText("{}")

        StoredNames.migrate(app)

        assertEquals(meta, File(workspace, "sessions/debug-session-1/meta.json").readText())
        assertEquals(turn + "\n", File(workspace, "sessions/debug-session-1/turns.jsonl").readText())
        assertNoOldNames()
    }

    @Test
    fun whatBothNamesHoldKeepsTheNewAndDropsTheOld() {
        prefs("app_server_user_memory").edit().putBoolean("enabled", true).commit()
        prefs("debug_user_memory").edit().putBoolean("enabled", false).putString("note", "old only").commit()
        File(workspace, "sessions/s1/meta.json").apply { parentFile!!.mkdirs() }.writeText("new")
        File(workspace, "debug-sessions/s1/meta.json").apply { parentFile!!.mkdirs() }.writeText("old")
        File(workspace, "debug-sessions/s2/meta.json").apply { parentFile!!.mkdirs() }.writeText("only old")
        File(workspace, "debug-sessions/index.json").writeText("{}")

        StoredNames.migrate(app)

        assertEquals(mapOf("enabled" to true, "note" to "old only"), prefs("app_server_user_memory").all)
        assertEquals("new", File(workspace, "sessions/s1/meta.json").readText())
        assertEquals("only old", File(workspace, "sessions/s2/meta.json").readText())
        assertFalse(File(workspace, "sessions/index.json").exists())
        assertNoOldNames()
    }

    @Test
    fun aSecondStartFindsNothingAndNothingOddStopsTheStart() {
        File(app.dataDir, "shared_prefs/debug_subagent_types.xml").apply { parentFile!!.mkdirs() }.writeText("<map><boolean name=")
        workspace.mkdirs()
        File(workspace, "debug-sessions").writeText("not a directory")
        seedV030ModelKey()

        StoredNames.migrate(app)
        val moved = prefs("app_server_provider_config").all
        StoredNames.migrate(app)

        assertEquals(moved, prefs("app_server_provider_config").all)
        assertNoOldNames()
    }

    /** What v0.3.0's onboarding saved for its default profile (ProviderConfig.saveProfileOverride at the tag). */
    private fun seedV030ModelKey() {
        prefs("debug_provider_config").edit()
            .putBoolean("manual_profile_enabled", true)
            .putString("selected_profile_id", "deepseek-anthropic")
            .putString("profile.deepseek_anthropic.label", "deepseek anthropic")
            .putString("profile.deepseek_anthropic.provider", "anthropic")
            .putString("profile.deepseek_anthropic.api_key", V030_KEY)
            .putString("profile.deepseek_anthropic.model", "deepseek-flash")
            .putString("profile.deepseek_anthropic.base_url", "https://api.deepseek.com/anthropic")
            .putString("profile.deepseek_anthropic.endpoint", "messages")
            .putString("ANTHROPIC_API_KEY", V030_KEY)
            .putString("ANTHROPIC_MODEL", "deepseek-flash")
            .putString("ANTHROPIC_BASE_URL", "https://api.deepseek.com/anthropic")
            .commit()
    }

    private fun assertNoOldNames() {
        val left = StoredNames.RENAMED_PREFS.map { it.first }.filter { File(app.dataDir, "shared_prefs/$it.xml").exists() }
        assertEquals(emptyList<String>(), left)
        assertFalse(File(workspace, "debug-sessions").exists())
    }

    private fun prefs(name: String) = app.getSharedPreferences(name, Context.MODE_PRIVATE)

    private class AllOn : PluginSwitches {
        override fun isOn(id: String): Boolean = true

        override fun set(id: String, on: Boolean) = Unit
    }

    private companion object {
        const val V030_KEY = "sk-v030-test"
    }
}
