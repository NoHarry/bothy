package cc.noharry.bothy.sample

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.bothy.kernel.core.NATIVE_TOOLS
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DebugBasicToolConfigTest {
    private lateinit var context: Context

    @Before
    fun resetPreferences() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("debug_basic_tools", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("debug_runtime_skills", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun pageCoversActualNativeToolsWithExecOffByDefault() {
        val tools = loadDebugBasicTools(context)
        assertEquals(NATIVE_TOOLS.map { it.name }.toSet(), tools.map { it.name }.toSet())
        assertEquals(setOf("exec_js"), disabledDebugBasicTools(tools))
        assertEquals(listOf("finish"), tools.filter { it.required }.map { it.name })
    }

    @Test
    fun persistenceAndSkillsDoNotChangeAnExistingRequestSnapshot() {
        val snapshot = loadDebugBasicTools(context)
        saveDebugRuntimeSkill(context, "travel-planning", true)
        assertFalse(loadDebugBasicTools(context).single { it.name == "exec_js" }.enabled)
        saveDebugBasicTool(context, "exec_js", true)
        saveDebugBasicTool(context, "search_tools", false)
        assertEquals(setOf("search_tools"), disabledDebugBasicTools(loadDebugBasicTools(context)))
        assertEquals(setOf("exec_js"), disabledDebugBasicTools(snapshot))
        saveDebugRuntimeSkill(context, "travel-planning", false)
        assertTrue(loadDebugBasicTools(context).single { it.name == "exec_js" }.enabled)
        assertEquals("", debugBasicToolPrompt(snapshot))
        val noDiscovery = debugBasicToolPrompt(loadDebugBasicTools(context))
        assertTrue(noDiscovery.contains("disabled native tools: search_tools"))
        assertFalse(noDiscovery.contains("exec_js"))
    }

    @Test
    fun finishCannotBeDisabledEvenWithStalePreferences() {
        assertThrows(IllegalArgumentException::class.java) { saveDebugBasicTool(context, "finish", false) }
        context.getSharedPreferences("debug_basic_tools", Context.MODE_PRIVATE).edit().putBoolean("finish", false).commit()
        assertTrue(loadDebugBasicTools(context).single { it.name == "finish" }.enabled)
        assertThrows(IllegalStateException::class.java) { saveDebugBasicTool(context, "missing", false) }
    }
}
