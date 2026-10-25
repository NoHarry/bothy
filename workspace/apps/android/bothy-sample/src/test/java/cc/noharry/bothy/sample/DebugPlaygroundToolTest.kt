package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.ToolCallContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugPlaygroundToolTest {
    private val context = ToolCallContext("session", "run", "request")

    @Test fun eachSceneIsHandedToThePlaygroundAndAnUnknownOneMeansTheHeadUnit() = runBlocking {
        val opened = mutableListOf<String>()
        val tool = DebugPlaygroundToolProvider(open = { scene -> opened += scene; true })

        assertEquals(listOf(PLAYGROUND_OPEN_TOOL), tool.listTools().map { it.name })
        for (args in listOf("""{"scene":"home"}""", """{"scene":"nav"}""", "{}", """{"scene":"garage"}""")) {
            assertTrue(args, tool.callTool(PLAYGROUND_OPEN_TOOL, args, context).success)
        }
        assertEquals(listOf("home", "nav", "cabin", "cabin"), opened)
    }

    @Test fun withoutThePlaygroundItSaysSo() = runBlocking {
        val result = DebugPlaygroundToolProvider(open = { false }).callTool(PLAYGROUND_OPEN_TOOL, "{}", context)

        assertFalse(result.success)
        assertTrue(result.resultJson, "PLAYGROUND_NOT_INSTALLED" in result.resultJson)
    }

    @Test fun aLaunchLeftBehindADialogSendsTheAgentToConfirmItOnScreen() = runBlocking {
        val asked = mutableListOf<Set<String>>()
        val blocked = DebugPlaygroundToolProvider(open = { true }, cameToFront = { packages -> asked += packages; false })

        val result = blocked.callTool(PLAYGROUND_OPEN_TOOL, """{"scene":"nav"}""", context)

        assertTrue(result.success)
        assertTrue(result.resultJson, "\"inFront\":false" in result.resultJson && "android_observe" in result.resultJson)
        assertEquals("高德 counts for 回到导航", setOf(PLAYGROUND_PACKAGE, "com.autonavi.minimap"), asked.single())
        val shown = DebugPlaygroundToolProvider(open = { true }, cameToFront = { true }).callTool(PLAYGROUND_OPEN_TOOL, "{}", context)
        assertFalse(shown.resultJson, "android_observe" in shown.resultJson)
    }
}
