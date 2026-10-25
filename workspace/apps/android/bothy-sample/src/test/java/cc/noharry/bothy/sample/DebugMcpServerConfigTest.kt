package cc.noharry.bothy.sample

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
class DebugMcpServerConfigTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun noServerComesConfiguredAndTheFixedProvidersSurroundTheConfiguredOnes() {
        assertTrue(loadDebugMcpServers(context).isEmpty())
        assertEquals(
            listOf(DEBUG_MCP_PROVIDER_TOOLS_JSON, DEBUG_MCP_PROVIDER_DEVICE_CALENDAR, DEBUG_MCP_PROVIDER_DEVICE_NOTIFICATION, DEBUG_MCP_PROVIDER_DEVICE_MEDIA),
            loadDebugMcpProviders(context).map { it.id },
        )
        val id = (saveDebugMcpServer(context, DebugMcpServerDraft(null, "Weather", DebugMcpServerKind.HTTP, url = "https://weather.test/mcp")) as DebugMcpSaveResult.Saved).id
        assertEquals(
            listOf(DEBUG_MCP_PROVIDER_TOOLS_JSON, id, DEBUG_MCP_PROVIDER_DEVICE_CALENDAR, DEBUG_MCP_PROVIDER_DEVICE_NOTIFICATION, DEBUG_MCP_PROVIDER_DEVICE_MEDIA),
            loadDebugMcpProviders(context).map { it.id },
        )
        assertTrue(loadDebugMcpProviders(context).single { it.id == id }.enabled)
    }

    @Test
    fun badFieldsAreRefusedWithAReason() {
        fun reason(draft: DebugMcpServerDraft) = (saveDebugMcpServer(context, draft) as DebugMcpSaveResult.Rejected).reason

        assertTrue(reason(DebugMcpServerDraft(null, " ", DebugMcpServerKind.HTTP, url = "https://a.test/mcp")).contains("名称"))
        assertTrue(reason(DebugMcpServerDraft(null, "x", DebugMcpServerKind.HTTP, url = "ftp://a.test")).contains("https://"))
        assertTrue(reason(DebugMcpServerDraft(null, "x", DebugMcpServerKind.HTTP, url = "http://a.test/mcp")).contains("只能连本机"))
        assertTrue(reason(DebugMcpServerDraft(null, "x", DebugMcpServerKind.HTTP, url = "https://a.test", headers = "no colon")).contains("名称: 值"))
        assertTrue(reason(DebugMcpServerDraft(null, "x", DebugMcpServerKind.HTTP, url = "https://a.test", headers = "Accept: x")).contains("Accept"))
        assertEquals(0, loadDebugMcpServers(context).size)
    }

    @Test
    fun aStandardServerKeepsItsTokenAndHeadersOffSettingsAndAcrossEdits() {
        val id = (saveDebugMcpServer(
            context,
            DebugMcpServerDraft(null, "Weather", DebugMcpServerKind.HTTP, url = "https://weather.test/mcp", token = "secret-1", headers = "X-Api-Key: k1\nX-Team: car"),
        ) as DebugMcpSaveResult.Saved).id

        assertEquals(
            mapOf("X-Api-Key" to "k1", "X-Team" to "car", "Authorization" to "Bearer secret-1"),
            DebugMcpSecrets.requestHeaders(context, id),
        )
        val prefs = context.getSharedPreferences("debug_mcp_servers", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse("the token never lands in settings", prefs.contains("secret-1") || prefs.contains("k1"))

        saveDebugMcpServer(context, DebugMcpServerDraft(id, "Weather 2", DebugMcpServerKind.HTTP, url = "https://weather.test/v2"))
        assertEquals("Bearer secret-1", DebugMcpSecrets.requestHeaders(context, id)["Authorization"])
        assertEquals("https://weather.test/v2", loadDebugMcpServers(context).single { it.id == id }.url)

        saveDebugMcpServer(context, DebugMcpServerDraft(id, "Weather 2", DebugMcpServerKind.HTTP, url = "https://weather.test/v2", clearSecrets = true))
        assertTrue(DebugMcpSecrets.requestHeaders(context, id).isEmpty())
    }

    @Test
    fun idsAreShortAsciiAndUniqueAndServersCanBeDeleted() {
        val weather = (saveDebugMcpServer(context, DebugMcpServerDraft(null, "Weather", DebugMcpServerKind.HTTP, url = "https://weather.test/mcp")) as DebugMcpSaveResult.Saved).id
        val servers = loadDebugMcpServers(context)
        assertEquals("weather", weather)
        assertEquals("mcp", newDebugMcpServerId("天气", servers))
        assertEquals("weather2", newDebugMcpServerId("Weather", servers))
        assertEquals("toolsjson", newDebugMcpServerId("tools_json", servers))

        assertTrue(deleteDebugMcpServer(context, weather))
        assertFalse(deleteDebugMcpServer(context, weather))
        assertTrue(loadDebugMcpServers(context).isEmpty())
        assertNotNull(parseDebugMcpServers("[{\"id\":\"a\",\"label\":\"A\",\"kind\":\"HTTP\",\"url\":\"https://a.test\"}]")?.single())
    }
}
