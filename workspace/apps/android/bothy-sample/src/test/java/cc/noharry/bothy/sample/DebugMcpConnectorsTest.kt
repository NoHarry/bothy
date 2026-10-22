package cc.noharry.bothy.sample

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.transport.HttpEvent
import cc.noharry.agent.transport.HttpRequest
import cc.noharry.agent.transport.HttpResponse
import cc.noharry.agent.transport.HttpTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 连接应用 as an app market (owner 2026-10-14): an MCP app is tried with the typed key, saved as a configured server
 * only when it lists its tools, and its key never sits in the stored address.
 */
@RunWith(RobolectricTestRunner::class)
class DebugMcpConnectorsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val probed = mutableListOf<DebugMcpEndpoint>()
    private var answer = McpToolsListing(listOf(tool("search_poi", "找地点"), tool("route_plan", null)))
    private val amap = DEBUG_CONNECTORS.single { it.id == "amap" }
    private val luckin = DEBUG_CONNECTORS.single { it.id == "luckin" }
    private val didi = DEBUG_CONNECTORS.single { it.id == "didi" }

    @After
    fun tearDown() {
        DEBUG_MCP_CONNECTORS.forEach { connector -> connector.mcp?.let { deleteDebugMcpServer(context, it.serverId) } }
        context.getSharedPreferences("debug_connectors", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun tool(name: String, title: String?) = ToolDescriptor(name = name, inputSchemaJson = "{}", title = title)

    private fun controller() = DebugConnectorController(
        context,
        note = {},
        servers = PrefsServers,
        probe = { endpoint -> probed += endpoint; answer },
    )

    /** The stored server list, without the live session. */
    private object PrefsServers : DebugConnectorServers {
        override fun find(context: Context, id: String) = loadDebugMcpServers(context).firstOrNull { it.id == id }

        override suspend fun save(context: Context, draft: DebugMcpServerDraft): String? =
            (saveDebugMcpServer(context, draft) as? DebugMcpSaveResult.Rejected)?.reason

        override suspend fun delete(context: Context, id: String) {
            deleteDebugMcpServer(context, id)
        }

        override suspend fun setEnabled(context: Context, id: String, enabled: Boolean) {
            setDebugMcpServerEnabled(context, id, enabled)
        }
    }

    @Test
    fun aKeyInTheAddressIsTriedThenKeptOutOfTheStoredAddress() = runBlocking {
        val controller = controller()
        assertEquals(DebugConnectorStatus.Disconnected, controller.status(amap))

        val tools = controller.connectApp(amap, "  amap-secret-key ", sandbox = false)

        assertEquals(listOf("找地点", "route_plan"), tools)
        assertEquals(DebugMcpEndpoint("https://mcp.amap.com/mcp", query = mapOf("key" to "amap-secret-key")), probed.single())
        val server = loadDebugMcpServers(context).single { it.id == "amap" }
        assertEquals("高德地图", server.label)
        assertEquals("https://mcp.amap.com/mcp", server.url)
        assertEquals(mapOf("key" to "amap-secret-key"), DebugMcpSecrets.requestQuery(context, "amap"))
        val prefs = context.getSharedPreferences("debug_mcp_servers", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse("the key never lands in settings", prefs.contains("amap-secret-key"))
        assertEquals(DebugConnectorStatus.Connected(verified = true), controller.status(amap))
        assertTrue(controller.events(amap).single().endsWith("已连接，可以用 2 个功能。"))
    }

    @Test
    fun anOpenServerConnectsWithOneTapAndStoresNoSecret() = runBlocking {
        val kiwi = DEBUG_CONNECTORS.single { it.id == "kiwi" }
        assertFalse(kiwi.mcp!!.needsCredential)
        val controller = controller()

        controller.connectApp(kiwi, "", sandbox = false)

        assertEquals(DebugMcpEndpoint("https://mcp.kiwi.com"), probed.single())
        assertEquals("https://mcp.kiwi.com", loadDebugMcpServers(context).single { it.id == "kiwi" }.url)
        assertTrue(DebugMcpSecrets.read(context, "kiwi").isEmpty)
        assertEquals(DebugConnectorStatus.Connected(verified = true), controller.status(kiwi))
        answer = McpToolsListing(emptyList(), "服务出错（HTTP 503）")
        val down = runCatching { controller.connectApp(kiwi, "", sandbox = false) }.exceptionOrNull()!!
        assertTrue(down.message!!, down.message!!.contains("现在用不了"))
    }

    @Test
    fun aTokenGoesAsBearerAndAKeyThatIsRefusedSavesNothing() = runBlocking {
        val controller = controller()
        answer = McpToolsListing(emptyList(), "服务拒绝了（HTTP 401），检查令牌或请求头")
        try {
            controller.connectApp(luckin, "expired", sandbox = false)
            fail("a refused token must not connect")
        } catch (failure: DebugConnectorException) {
            assertTrue(failure.message!!, failure.message!!.contains("瑞幸咖啡 没有接受这个访问令牌"))
        }
        assertEquals(mapOf("Authorization" to "Bearer expired"), probed.single().headers)
        assertNull(loadDebugMcpServers(context).firstOrNull { it.id == "luckin" })

        answer = McpToolsListing(emptyList(), "连不上：Unable to resolve host")
        val offline = runCatching { controller.connectApp(luckin, "fresh", sandbox = false) }.exceptionOrNull()!!
        assertTrue(offline.message!!, offline.message!!.contains("请检查设备网络"))

        val blank = runCatching { controller.connectApp(luckin, "  ", sandbox = false) }.exceptionOrNull()!!
        assertEquals("请先填访问令牌。", blank.message)
        assertEquals("a blank token is never tried", 2, probed.size)
    }

    @Test
    fun didiStartsOnItsSandboxAndAPausedAppCanBeResumedOrDisconnected() = runBlocking {
        val controller = controller()
        controller.connectApp(didi, "didi-key", sandbox = true)
        assertEquals("https://mcp.didichuxing.com/mcp-servers-sandbox", probed.single().url)
        assertTrue(controller.usesSandbox(didi))

        setDebugMcpServerEnabled(context, "didi", false)
        assertEquals(DebugConnectorStatus.Paused, controller.status(didi))
        assertEquals(DebugConnectorStatus.Connected(verified = true), controller.resumeApp(didi))

        controller.connectApp(didi, "didi-key-2", sandbox = false)
        assertFalse(controller.usesSandbox(didi))
        assertEquals("the same server is reused", 1, loadDebugMcpServers(context).count { it.label == "滴滴出行" })
        assertEquals(mapOf("key" to "didi-key-2"), DebugMcpSecrets.requestQuery(context, "didi"))

        controller.checkApp(didi)
        assertEquals(mapOf("key" to "didi-key-2"), probed.last().query)

        controller.disconnect(didi)
        assertEquals(DebugConnectorStatus.Disconnected, controller.status(didi))
        assertTrue(DebugMcpSecrets.read(context, "didi").isEmpty)
    }

    @Test
    fun theCatalogHasOneServerPerAppAndNoKeyInAnyAddress() {
        val specs = DEBUG_MCP_CONNECTORS.map { it.mcp!! }
        assertEquals(specs.size, specs.map { it.serverId }.toSet().size)
        assertEquals(DEBUG_CONNECTORS.size, DEBUG_CONNECTORS.map { it.id }.toSet().size)
        specs.forEach { spec ->
            (listOf(spec.url, spec.portal) + listOfNotNull(spec.sandboxUrl)).forEach { url ->
                // Only Bothy Playground, on this device, is reached without TLS.
                assertTrue(url, (url.startsWith("https://") || url.startsWith("http://127.0.0.1:")) && !url.contains("key="))
            }
            assertTrue(spec.serverId, spec.serverId.all { it in 'a'..'z' || it in '0'..'9' })
            assertTrue(spec.serverId, spec.needsCredential == spec.steps.isNotEmpty() && spec.abilities.isNotEmpty() && spec.example.isNotEmpty())
            assertEquals(spec.serverId, spec.needsCredential, spec.credentialLabel.isNotEmpty())
        }
        assertTrue(GROK_BOT_CONNECTOR.mcp == null && GROK_BOT_CONNECTOR in DEBUG_CONNECTORS)
    }

    @Test
    fun theKeyIsAddedToTheAddressOnlyWhenARequestIsSent() = runBlocking {
        val sent = mutableListOf<String>()
        val http = object : HttpTransport {
            override fun stream(request: HttpRequest): Flow<HttpEvent> = emptyFlow()

            override suspend fun execute(request: HttpRequest): HttpResponse {
                sent += request.url
                return HttpResponse(200, mapOf("Content-Type" to listOf("application/json")), """{"status":"0","info":"INVALID_USER_KEY"}""".toByteArray())
            }
        }
        val client = McpHttpClient("https://mcp.amap.com/mcp?key=old", { emptyMap() }, http, "sample", "1.0", query = { mapOf("key" to "k 1") })

        val result = client.connect()

        assertEquals("https://mcp.amap.com/mcp?key=k%201", sent.single())
        assertEquals("""服务没有回应 initialize：{"status":"0","info":"INVALID_USER_KEY"}""", result.errorMessage)
        assertEquals("https://a.test/mcp", withQuery("https://a.test/mcp", emptyMap()))
    }
}
