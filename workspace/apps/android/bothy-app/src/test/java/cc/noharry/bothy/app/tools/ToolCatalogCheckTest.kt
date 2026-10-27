package cc.noharry.bothy.app.tools
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.bothy.app.a2a.describe
import cc.noharry.bothy.app.mcp.DEBUG_MCP_PROVIDER_DEVICE_MEDIA
import cc.noharry.bothy.app.mcp.DEBUG_MCP_PROVIDER_TOOLS_JSON
import cc.noharry.bothy.app.mcp.saveMcpProviderEnabled
import cc.noharry.bothy.app.model.describe
import cc.noharry.bothy.app.plans.describe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 检查 → 工具目录. */
@RunWith(RobolectricTestRunner::class)
class ToolCatalogCheckTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun eachSourceIsCountedAndGroupedTheWayThePromptListsIt() = runBlocking {
        saveMcpProviderEnabled(context, DEBUG_MCP_PROVIDER_TOOLS_JSON, true)
        // Device Media is on from the start; off here, the example tools are counted alone.
        saveMcpProviderEnabled(context, DEBUG_MCP_PROVIDER_DEVICE_MEDIA, false)

        val result = checkToolCatalog(context)

        assertTrue(result.detail, result.detail.contains("Tools JSON 5 个"))
        // The example tools have no command_<domain>_ prefix, and the domain index reads no server id with an underscore
        // (tools_json), so the prompt lists them as one group, as it does here.
        assertTrue(result.detail, result.detail.contains("分 1 组：other 5"))
        assertTrue(result.detail.length < 600)
    }

    @Test
    fun theLastPromptSentIsMeasuredFromTheRequestBody() {
        val system = "Model-driven harness protocol v1.\n- rule\n\n" +
            "Tool domains (use search_tools to discover specific tools by keyword):\n- device (320 tools): 打开地图\n- other (7 tools): 看屏幕\n\n" +
            "Context paths (read_file to access):\n- /AGENTS.md — rules\n\nHost instructions:\nYou are 小博."
        val anthropic = """{"model":"m","system":${kotlinx.serialization.json.JsonPrimitive(system)},"messages":[]}"""

        LastPrompt.record(anthropic)
        val stats = LastPrompt.stats!!

        assertEquals(system.substringBefore("\n\nHost instructions:").toByteArray().size, stats.staticBytes)
        assertEquals(2, stats.toolGroups)
        assertFalse(stats.shortened)
        assertTrue(stats.describe().startsWith("上次对话固定提示 "))
    }

    @Test
    fun openAiSystemMessagesAndShortenedIndexesAreRead() {
        val system = "Model-driven harness protocol v1.\n\nTool domains (use search_tools to discover specific tools by keyword):\n" +
            "- carcontrol (169 tools)\n- cmd (49 tools)\n- and 6 more domains (60 tools)"
        val body = """{"messages":[{"role":"system","content":${kotlinx.serialization.json.JsonPrimitive(system)}},{"role":"user","content":"hi"}]}"""

        val stats = PromptStats.of(systemPromptOf(body)!!)!!

        assertEquals(2, stats.toolGroups)
        assertTrue(stats.shortened)
        assertTrue(stats.describe().contains("已自动缩短"))
        assertNull("a request that is not a run (a summary, a model list) is not a run's prompt", PromptStats.of("You read logs."))
        assertNull(systemPromptOf("not json"))
    }
}
