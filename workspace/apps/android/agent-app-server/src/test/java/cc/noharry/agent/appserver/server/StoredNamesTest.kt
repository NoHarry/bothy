package cc.noharry.agent.appserver.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.mcp.MCP_PREFS_NAME
import cc.noharry.agent.appserver.model.CONTEXT_WINDOW_PREFS
import cc.noharry.agent.appserver.model.MODEL_IMAGE_INPUT_PREFS
import cc.noharry.agent.appserver.model.PREFS_NAME
import cc.noharry.agent.appserver.runtime.appWorkspaceRoot
import cc.noharry.agent.appserver.session.SESSIONS_DIR
import cc.noharry.agent.appserver.session.TURNS_FILE
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What an earlier version kept under its debug console's names is where the server reads it now, once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StoredNamesTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everyValueMovesToTheNewNameAndTheOldFileGoes() {
        app.getSharedPreferences("debug_provider_config", Context.MODE_PRIVATE).edit()
            .putString("key", "sk-1").putBoolean("on", true).putInt("n", 3).putLong("at", 9L).putFloat("t", 0.5f)
            .putStringSet("ids", setOf("a", "b")).commit()

        StoredNames.migrate(app)

        val moved = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all
        assertEquals(mapOf("key" to "sk-1", "on" to true, "n" to 3, "at" to 9L, "t" to 0.5f, "ids" to setOf("a", "b")), moved)
        assertTrue(app.getSharedPreferences("debug_provider_config", Context.MODE_PRIVATE).all.isEmpty())
        assertFalse(File(app.dataDir, "shared_prefs/debug_provider_config.xml").exists())
        // A second start finds nothing to move and keeps what is there.
        StoredNames.migrate(app)
        assertEquals(moved, app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all)
    }

    @Test
    fun whatAnOlderBuildWroteAgainIsMergedWithoutReplacingTheServersOwn() {
        app.getSharedPreferences(CONTEXT_WINDOW_PREFS, Context.MODE_PRIVATE).edit().putInt("w", 2).commit()
        app.getSharedPreferences("debug_context_windows", Context.MODE_PRIVATE).edit().putInt("w", 1).putInt("v", 7).commit()

        StoredNames.migrate(app)

        assertEquals(mapOf("w" to 2, "v" to 7), app.getSharedPreferences(CONTEXT_WINDOW_PREFS, Context.MODE_PRIVATE).all)
        assertFalse(File(app.dataDir, "shared_prefs/debug_context_windows.xml").exists())
    }

    @Test
    fun theSessionsMoveWhereTheModelReadsThem() {
        val workspace = appWorkspaceRoot(app)
        File(workspace, "debug-sessions/s1/meta.json").apply { parentFile!!.mkdirs(); writeText("{}") }

        StoredNames.migrate(app)

        assertEquals("{}", File(workspace, "$SESSIONS_DIR/s1/meta.json").readText())
        assertFalse(File(workspace, "debug-sessions").exists())
    }

    @Test
    fun sessionsAnOlderBuildStartedAgainJoinTheOthersAndOnesBothKeptAreSetAside() {
        val workspace = appWorkspaceRoot(app)
        listOf("s1", "s3").forEach { File(workspace, "$SESSIONS_DIR/$it/meta.json").apply { parentFile!!.mkdirs(); writeText("new") } }
        File(workspace, "debug-sessions/s1/$TURNS_FILE").apply { parentFile!!.mkdirs(); writeText("old turns") }
        File(workspace, "debug-sessions/s2/meta.json").apply { parentFile!!.mkdirs(); writeText("{}") }
        File(workspace, "debug-sessions/s3/meta.json").apply { parentFile!!.mkdirs(); writeText("opened only") }
        File(workspace, "debug-sessions/index.json").writeText("[]")

        StoredNames.migrate(app)

        assertEquals("new", File(workspace, "$SESSIONS_DIR/s1/meta.json").readText())
        assertEquals("{}", File(workspace, "$SESSIONS_DIR/s2/meta.json").readText())
        assertEquals("new", File(workspace, "$SESSIONS_DIR/s3/meta.json").readText())
        assertFalse(File(workspace, "debug-sessions").exists())
        assertEquals("old turns", File(workspace, "debug-sessions-unmerged/s1/$TURNS_FILE").readText())
        assertEquals(listOf("s1"), File(workspace, "debug-sessions-unmerged").list()!!.toList())
    }

    @Test
    fun theNamesTheServerReadsAreTheOnesItMovesTo() {
        val moved = StoredNames.RENAMED_PREFS.map { it.second }.toSet()
        assertTrue(listOf(PREFS_NAME, CONTEXT_WINDOW_PREFS, MCP_PREFS_NAME, MODEL_IMAGE_INPUT_PREFS).all { it in moved })
    }
}
