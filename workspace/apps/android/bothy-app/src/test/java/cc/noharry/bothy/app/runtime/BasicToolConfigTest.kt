package cc.noharry.bothy.app.runtime
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.kernel.core.tools.NATIVE_TOOLS
import cc.noharry.bothy.app.skills.saveRuntimeSkill
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BasicToolConfigTest {
    private lateinit var context: Context

    @Before
    fun resetPreferences() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("debug_basic_tools", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("debug_runtime_skills", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun pageCoversActualNativeToolsWithExecOffByDefault() {
        val tools = loadBasicTools(context)
        assertEquals(NATIVE_TOOLS.map { it.name }.toSet(), tools.map { it.name }.toSet())
        assertEquals(setOf("exec_js"), disabledBasicTools(tools))
        assertEquals(listOf("finish"), tools.filter { it.required }.map { it.name })
    }

    @Test
    fun persistenceAndSkillsDoNotChangeAnExistingRequestSnapshot() {
        val snapshot = loadBasicTools(context)
        saveRuntimeSkill(context, "travel-planning", true)
        assertFalse(loadBasicTools(context).single { it.name == "exec_js" }.enabled)
        saveBasicTool(context, "exec_js", true)
        saveBasicTool(context, "search_tools", false)
        assertEquals(setOf("search_tools"), disabledBasicTools(loadBasicTools(context)))
        assertEquals(setOf("exec_js"), disabledBasicTools(snapshot))
        saveRuntimeSkill(context, "travel-planning", false)
        assertTrue(loadBasicTools(context).single { it.name == "exec_js" }.enabled)
        assertEquals("", basicToolPrompt(snapshot))
        val noDiscovery = basicToolPrompt(loadBasicTools(context))
        assertTrue(noDiscovery.contains("disabled native tools: search_tools"))
        assertFalse(noDiscovery.contains("exec_js"))
    }

    @Test
    fun finishCannotBeDisabledEvenWithStalePreferences() {
        assertThrows(IllegalArgumentException::class.java) { saveBasicTool(context, "finish", false) }
        context.getSharedPreferences("debug_basic_tools", Context.MODE_PRIVATE).edit().putBoolean("finish", false).commit()
        assertTrue(loadBasicTools(context).single { it.name == "finish" }.enabled)
        assertThrows(IllegalStateException::class.java) { saveBasicTool(context, "missing", false) }
    }
}
