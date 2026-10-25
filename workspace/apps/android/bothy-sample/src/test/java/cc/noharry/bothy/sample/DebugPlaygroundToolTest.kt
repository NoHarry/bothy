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
        val tool = DebugPlaygroundToolProvider { scene -> opened += scene; true }

        assertEquals(listOf(PLAYGROUND_OPEN_TOOL), tool.listTools().map { it.name })
        for (args in listOf("""{"scene":"home"}""", """{"scene":"nav"}""", "{}", """{"scene":"garage"}""")) {
            assertTrue(args, tool.callTool(PLAYGROUND_OPEN_TOOL, args, context).success)
        }
        assertEquals(listOf("home", "nav", "cabin", "cabin"), opened)
    }

    @Test fun withoutThePlaygroundItSaysSo() = runBlocking {
        val result = DebugPlaygroundToolProvider { false }.callTool(PLAYGROUND_OPEN_TOOL, "{}", context)

        assertFalse(result.success)
        assertTrue(result.resultJson, "PLAYGROUND_NOT_INSTALLED" in result.resultJson)
    }
}
