package cc.noharry.bothy.server.tools
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.device.appserver.DEVICE_MEDIA_TOOL_SERVER
import cc.noharry.agent.appserver.mcp.DEBUG_MCP_PROVIDER_TOOLS_JSON
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.bothy.protocol.BothyFindingCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 检查 → 工具目录, Bothy's check of the tool catalog. How a run's prompt is measured is the server's (`PromptStatsTest`). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh")
class ToolCatalogCheckTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun eachSourceIsCountedAndGroupedTheWayThePromptListsIt() = runBlocking {
        AppServerTesting.switchMcpProvider(context, DEBUG_MCP_PROVIDER_TOOLS_JSON, true)
        // Device Media is on from the start; off here, the example tools are counted alone.
        AppServerTesting.switchMcpProvider(context, DEVICE_MEDIA_TOOL_SERVER, false)

        val result = checkToolCatalog(context)

        val parts = result.finding!!.more
        val toolsJson = parts[0].more.single { it.args == listOf("Tools JSON") }.more.single()
        assertEquals(BothyFindingCode.TOOLS_COUNT.name, toolsJson.code)
        assertEquals(5, toolsJson.count)
        // The example tools have no command_<domain>_ prefix, and the domain index reads no server id with an underscore
        // (tools_json), so the prompt lists them as one group, as it does here.
        val groups = parts[1]
        assertEquals(BothyFindingCode.TOOLS_GROUPS.name, groups.code)
        assertEquals(1, groups.count)
        assertEquals(listOf("tools_json", "5"), groups.more.single().more.single().args)
    }
}
