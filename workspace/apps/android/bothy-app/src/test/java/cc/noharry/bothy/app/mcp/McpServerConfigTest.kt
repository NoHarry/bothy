package cc.noharry.bothy.app.mcp
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 设置 → 连接 → MCP: standard servers by address, token and headers. */
@RunWith(RobolectricTestRunner::class)
class McpServerConfigTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun noServerComesConfiguredAndTheFixedProvidersSurroundTheConfiguredOnes() {
        assertTrue(loadMcpServers(context).isEmpty())
        assertEquals(
            listOf(DEBUG_MCP_PROVIDER_TOOLS_JSON, DEBUG_MCP_PROVIDER_DEVICE_CALENDAR, DEBUG_MCP_PROVIDER_DEVICE_NOTIFICATION, DEBUG_MCP_PROVIDER_DEVICE_MEDIA),
            loadMcpProviders(context).map { it.id },
        )
        val id = (saveMcpServerDraft(context, McpServerDraft(null, "Weather", McpServerKind.HTTP, url = "https://weather.test/mcp")) as McpSaveResult.Saved).id
        assertEquals(
            listOf(DEBUG_MCP_PROVIDER_TOOLS_JSON, id, DEBUG_MCP_PROVIDER_DEVICE_CALENDAR, DEBUG_MCP_PROVIDER_DEVICE_NOTIFICATION, DEBUG_MCP_PROVIDER_DEVICE_MEDIA),
            loadMcpProviders(context).map { it.id },
        )
        assertTrue(loadMcpProviders(context).single { it.id == id }.enabled)
    }

    @Test
    fun badFieldsAreRefusedWithAReason() {
        fun reason(draft: McpServerDraft) = (saveMcpServerDraft(context, draft) as McpSaveResult.Rejected).reason

        assertTrue(reason(McpServerDraft(null, " ", McpServerKind.HTTP, url = "https://a.test/mcp")).contains("名称"))
        assertTrue(reason(McpServerDraft(null, "x", McpServerKind.HTTP, url = "ftp://a.test")).contains("https://"))
        assertTrue(reason(McpServerDraft(null, "x", McpServerKind.HTTP, url = "http://a.test/mcp")).contains("只能连本机"))
        assertTrue(reason(McpServerDraft(null, "x", McpServerKind.HTTP, url = "https://a.test", headers = "no colon")).contains("名称: 值"))
        assertTrue(reason(McpServerDraft(null, "x", McpServerKind.HTTP, url = "https://a.test", headers = "Accept: x")).contains("Accept"))
        assertEquals(0, loadMcpServers(context).size)
    }

    @Test
    fun aStandardServerKeepsItsTokenAndHeadersOffSettingsAndAcrossEdits() {
        val id = (saveMcpServerDraft(
            context,
            McpServerDraft(null, "Weather", McpServerKind.HTTP, url = "https://weather.test/mcp", token = "secret-1", headers = "X-Api-Key: k1\nX-Team: car"),
        ) as McpSaveResult.Saved).id

        assertEquals(
            mapOf("X-Api-Key" to "k1", "X-Team" to "car", "Authorization" to "Bearer secret-1"),
            McpSecrets.requestHeaders(context, id),
        )
        val prefs = context.getSharedPreferences("debug_mcp_servers", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse("the token never lands in settings", prefs.contains("secret-1") || prefs.contains("k1"))

        saveMcpServerDraft(context, McpServerDraft(id, "Weather 2", McpServerKind.HTTP, url = "https://weather.test/v2"))
        assertEquals("Bearer secret-1", McpSecrets.requestHeaders(context, id)["Authorization"])
        assertEquals("https://weather.test/v2", loadMcpServers(context).single { it.id == id }.url)

        saveMcpServerDraft(context, McpServerDraft(id, "Weather 2", McpServerKind.HTTP, url = "https://weather.test/v2", clearSecrets = true))
        assertTrue(McpSecrets.requestHeaders(context, id).isEmpty())
    }

    @Test
    fun idsAreShortAsciiAndUniqueAndServersCanBeDeleted() {
        val weather = (saveMcpServerDraft(context, McpServerDraft(null, "Weather", McpServerKind.HTTP, url = "https://weather.test/mcp")) as McpSaveResult.Saved).id
        val servers = loadMcpServers(context)
        assertEquals("weather", weather)
        assertEquals("mcp", newMcpServerId("天气", servers))
        assertEquals("weather2", newMcpServerId("Weather", servers))
        assertEquals("toolsjson", newMcpServerId("tools_json", servers))

        assertTrue(deleteMcpServerConfig(context, weather))
        assertFalse(deleteMcpServerConfig(context, weather))
        assertTrue(loadMcpServers(context).isEmpty())
        assertNotNull(parseMcpServers("[{\"id\":\"a\",\"label\":\"A\",\"kind\":\"HTTP\",\"url\":\"https://a.test\"}]")?.single())
    }
}
