package cc.noharry.bothy.app.mcp
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.bothy.app.a2a.A2aTokens
import cc.noharry.bothy.app.console.status
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConnectorsTest {
    private lateinit var context: Context
    private val server = MockWebServer()
    private val profiles = RecordingProfiles()
    private val notes = mutableListOf<String>()
    private val grok = GROK_BOT_CONNECTOR

    @Before
    fun start() {
        context = ApplicationProvider.getApplicationContext()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
        A2aTokens.clear(context, "connector-grok-bot")
    }

    private fun controller(relay: String = "https://relay.example.com") = ConnectorController(
        context,
        relayUrl = { relay },
        profiles = profiles,
        clientFor = { RelayConnectClient(server.url("/")) },
        carLabel = "TB323FU",
        note = { notes += it },
    )

    private fun json(status: Int, body: String) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    private fun registered() = json(
        201,
        """{"device_id":"car-1","token":"car_secret","code":"ABCD-EFGH","code_expires_at":"2026-10-12T03:00:00Z",""" +
            """"connect_url":"https://relay.example.com/connect?code=ABCD-EFGH"}""",
    )

    private fun state(state: String, verified: Boolean = false, account: String? = null) = json(
        200,
        """{"state":"$state","verified":$verified,"agent_name":"Grok Bot","label":"TB323FU",""" +
            """"account":${account?.let { "\"$it\"" } ?: "null"}}""",
    )

    private fun signedIn(state: String = "connected") =
        json(200, """{"account":"noharry","state":"$state","account_url":"https://relay.example.com/account"}""")

    private suspend fun connected(controller: ConnectorController) {
        server.enqueue(registered())
        server.enqueue(state("connected"))
        controller.connect(grok)
        controller.refresh(grok)
        repeat(2) { server.takeRequest() }
    }

    @Test
    fun connectingRegistersTheCarAndClaimingCreatesTheProfile() = runBlocking {
        val controller = controller()
        server.enqueue(registered())

        val pairing = controller.connect(grok)

        val expected = ConnectorStatus.Pairing(
            "ABCD-EFGH", "https://relay.example.com/connect?code=ABCD-EFGH", Instant.parse("2026-10-12T03:00:00Z").toEpochMilli(),
        )
        assertEquals(expected, pairing)
        assertEquals(expected, controller.status(grok))
        val register = server.takeRequest()
        assertEquals("/connect/devices", register.path)
        assertTrue(register.body.readUtf8().contains("TB323FU"))
        assertEquals("car_secret", A2aTokens.read(context, "connector-grok-bot"))
        val prefs = File(context.applicationInfo.dataDir, "shared_prefs/debug_connectors.xml").readText()
        assertFalse(prefs.contains("car_secret"))

        server.enqueue(state("pending"))
        assertEquals(expected, controller.refresh(grok))
        assertEquals("Bearer car_secret", server.takeRequest().getHeader("Authorization"))
        assertTrue(profiles.saved.isEmpty())

        server.enqueue(state("connected"))
        assertEquals(ConnectorStatus.Connected(verified = false), controller.refresh(grok))
        assertEquals(
            listOf(listOf("Grok Bot", "https://relay.example.com/a2a", grok.profileDescription, "car_secret")),
            profiles.saved,
        )

        server.enqueue(state("connected", verified = true))
        assertEquals(ConnectorStatus.Connected(verified = true), controller.refresh(grok))
        assertEquals(1, profiles.saved.size)
    }

    @Test
    fun unpairingTheLastCarWaitsForTheBotsCleanUpReportThenForgetsTheCar() = runBlocking {
        val controller = controller()
        connected(controller)
        server.enqueue(json(202, """{"state":"cleaning","task_id":"t1","text":"已交给 Grok Bot，正在处理。"}"""))
        server.enqueue(
            json(200, """{"jsonrpc":"2.0","id":"x","result":{"kind":"task","id":"t1","status":{"state":"completed",""" +
                """"message":{"kind":"message","role":"agent","parts":[{"kind":"text","text":"例行任务已删，接着删连接器。"}]}}}}"""),
        )
        server.enqueue(json(200, """{"ok":true}"""))
        val progress = mutableListOf<String>()

        val text = controller.disconnect(grok) { progress += it }

        assertEquals("Grok Bot 回报：例行任务已删，接着删连接器。", text)
        assertEquals("/connect/device/unpair", server.takeRequest().path)
        assertTrue(server.takeRequest().body.readUtf8().contains("tasks/get"))
        val forget = server.takeRequest()
        assertEquals("DELETE" to "/connect/device", forget.method to forget.path)
        assertTrue(progress.single().contains("删除它那边的例行任务和连接器"))
        assertEquals(listOf("Grok Bot"), profiles.removed)
        assertEquals("", A2aTokens.read(context, "connector-grok-bot"))
        assertEquals(ConnectorStatus.Disconnected, controller.status(grok))
        assertTrue(controller.events(grok).first().endsWith("解除绑定：Grok Bot 回报：例行任务已删，接着删连接器。"))
        assertEquals("【连接应用】解除绑定：Grok Bot 回报：例行任务已删，接着删连接器。", notes.last())
    }

    @Test
    fun unpairingGoesAtOnceWhenTheRelayKeepsTheBotsSide() = runBlocking {
        val controller = controller()
        connected(controller)
        server.enqueue(json(200, """{"state":"disconnected","text":"已解除绑定。还有 1 台车连着 Grok Bot，它那边的设置保留。"}"""))

        assertEquals("已解除绑定。还有 1 台车连着 Grok Bot，它那边的设置保留。", controller.disconnect(grok))
        assertEquals(1, server.requestCount - 2)
        assertEquals(ConnectorStatus.Disconnected, controller.status(grok))

        connected(controller)
        server.enqueue(json(500, """{"error":"中转服务出错了。"}"""))
        assertTrue(controller.disconnect(grok).startsWith("本机已解除绑定，但中转服务出错了。"))
        assertEquals(ConnectorStatus.Disconnected, controller.status(grok))
    }

    @Test
    fun aCarTheBotDisconnectedForgetsItselfAndSaysSo() = runBlocking {
        val controller = controller()
        connected(controller)
        server.enqueue(json(401, """{"error":"设备令牌不对，请重新连接。"}"""))

        assertEquals(ConnectorStatus.Disconnected, controller.refresh(grok))
        assertEquals(listOf("Grok Bot"), profiles.removed)
        assertTrue(controller.events(grok).first().endsWith("Grok Bot 那边解除了绑定。"))
        assertTrue(controller.events(grok)[1].endsWith("已连接到你的 Grok Bot。"))
        assertEquals("【连接应用】Grok Bot 那边解除了绑定。", notes.last())
    }

    @Test
    fun aNewCodeReplacesTheShownOne() = runBlocking {
        val controller = controller()
        server.enqueue(registered())
        controller.connect(grok)
        server.enqueue(
            json(200, """{"code":"WXYZ-2345","code_expires_at":"2026-10-12T03:10:00Z","connect_url":"https://relay.example.com/connect?code=WXYZ-2345"}"""),
        )

        val fresh = controller.refreshCode(grok) as ConnectorStatus.Pairing

        assertEquals("WXYZ-2345", fresh.code)
        assertEquals(fresh, controller.status(grok))
        server.takeRequest()
        assertEquals("/connect/device/code", server.takeRequest().path)
    }

    @Test
    fun relayRefusalsAndAMissingRelayAddressAreExplained() = runBlocking {
        server.enqueue(json(429, """{"error":"注册太频繁，请稍后再试。"}"""))
        assertFailure("注册太频繁，请稍后再试。") { controller().connect(grok) }
        assertFailure("中转地址无效（打包时的 AGENT_RELAY_URL 需要是 https 地址）。") { controller(relay = "http://relay.example.com").connect(grok) }
        assertEquals(ConnectorStatus.Disconnected, controller().status(grok))
    }

    @Test
    fun theConnectionTestGoesThroughA2aAndWaitsForTheReport() = runBlocking {
        val controller = controller()
        connected(controller)
        val working = """{"jsonrpc":"2.0","id":"x","result":{"kind":"task","id":"t1","status":{"state":"working"}}}"""
        val done = """{"jsonrpc":"2.0","id":"x","result":{"kind":"task","id":"t1","status":{"state":"completed",""" +
            """"message":{"kind":"message","role":"agent","parts":[{"kind":"text","text":"连接成功，可以开始用了"}]}}}}"""
        server.enqueue(json(200, working))
        server.enqueue(json(200, done))

        assertEquals("连接成功，可以开始用了", controller.test(grok))

        val send = server.takeRequest()
        assertEquals("/a2a", send.path)
        assertEquals("Bearer car_secret", send.getHeader("Authorization"))
        assertTrue(send.body.readUtf8().contains("message/send"))
        assertTrue(server.takeRequest().body.readUtf8().contains("tasks/get"))
        assertEquals(ConnectorStatus.Connected(verified = true), controller.status(grok))

        server.enqueue(json(200, """{"jsonrpc":"2.0","id":"x","error":{"code":-32003,"message":"rate limited"}}"""))
        assertFailure("测试没发出去：rate limited") { controller.test(grok) }
    }

    @Test
    fun grokBotCarriesItsRelayWhichTheBuildMayOverride() {
        assertEquals(DEFAULT_AGENT_RELAY_URL, connectorRelayUrl { emptyMap() })
        assertEquals("https://relay.example.com", connectorRelayUrl { mapOf("AGENT_RELAY_URL" to "https://relay.example.com") })
        assertEquals("relay.example.com", controller().relayHost(grok))
    }

    @Test
    fun withoutARelayPairingSaysHowToConfigureOne() = runBlocking {
        assertEquals("", DEFAULT_AGENT_RELAY_URL)
        assertFailure("还没有配置中转：部署 tools/agent-relay 后，在 local.properties 写上 AGENT_RELAY_URL=https://… 再重新打包。") {
            controller(relay = "").connect(grok)
        }
    }

    @Test
    fun thePairingLinkBecomesAQrCodeWithFinderPatterns() {
        val modules = qrModules("https://relay.example.com/connect?code=ABCD-EFGH")

        assertTrue(modules.size >= 21)
        assertTrue(modules.all { it.size == modules.size })
        val last = modules.size - 1
        for ((row, column) in listOf(0 to 0, 0 to last - 6, last - 6 to 0)) {
            assertTrue((0..6).all { modules[row][column + it] && modules[row + it][column] })
            assertFalse(modules[row + 1][column + 1])
            assertTrue(modules[row + 3][column + 3])
        }
    }

    private suspend fun assertFailure(message: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected DebugConnectorException")
        } catch (error: ConnectorException) {
            assertEquals(message, error.message)
        }
    }

    private class RecordingProfiles : ConnectorProfiles {
        val saved = mutableListOf<List<String>>()
        val removed = mutableListOf<String>()

        override suspend fun save(context: Context, label: String, endpoint: String, description: String, token: String): String? {
            saved += listOf(label, endpoint, description, token)
            return null
        }

        override suspend fun remove(context: Context, label: String) {
            removed += label
        }
    }

    @Test
    fun signingInOnANewCarJoinsTheAccountsConnectionWithoutPairing() = runBlocking {
        val controller = controller()
        server.enqueue(registered())
        server.enqueue(signedIn())
        server.enqueue(state("connected", verified = true, account = "noharry"))

        val status = controller.signIn(grok, " noharry ", "correct horse", register = false)

        assertEquals(ConnectorStatus.Connected(verified = true), status)
        assertEquals("noharry", controller.account(grok))
        assertEquals("https://relay.example.com/account", controller.accountPage(grok))
        assertEquals("/connect/devices", server.takeRequest().path)
        val login = server.takeRequest()
        assertEquals("/connect/device/login", login.path)
        assertEquals("Bearer car_secret", login.getHeader("Authorization"))
        assertEquals("""{"name":"noharry","password":"correct horse","register":false}""", login.body.readUtf8())
        assertEquals(listOf("Grok Bot"), profiles.saved.map { it[0] })
        assertTrue(controller.events(grok).any { it.endsWith("登录了账号「noharry」。") })
    }

    @Test
    fun aConnectedCarRegistersAnAccountAndCanLeaveItAgain() = runBlocking {
        val controller = controller()
        connected(controller)
        server.enqueue(signedIn())
        server.enqueue(state("connected", account = "noharry"))

        controller.signIn(grok, "noharry", "correct horse", register = true)

        assertEquals("noharry", controller.account(grok))
        assertTrue(server.takeRequest().body.readUtf8().contains(""""register":true"""))
        server.takeRequest()
        server.enqueue(json(200, """{"ok":true}"""))
        controller.signOut(grok)
        assertEquals("/connect/device/logout", server.takeRequest().path)
        assertEquals(null, controller.account(grok))
        assertTrue(controller.status(grok) is ConnectorStatus.Connected)
        assertTrue(controller.events(grok).first().contains("退出了账号「noharry」"))
    }

    @Test
    fun aRefusedSignInCarriesTheRelaysWordsAndAnAccountGoneOnTheRelayIsForgotten() = runBlocking {
        val controller = controller()
        connected(controller)
        server.enqueue(json(401, """{"error":"用户名或密码不对。"}"""))
        try {
            controller.signIn(grok, "noharry", "wrong password", register = false)
            fail("a refused sign-in must throw")
        } catch (refused: ConnectorException) {
            assertEquals("用户名或密码不对。", refused.message)
        }
        assertEquals(null, controller.account(grok))

        server.enqueue(signedIn())
        server.enqueue(state("connected", account = "noharry"))
        controller.signIn(grok, "noharry", "correct horse", register = false)
        server.enqueue(state("connected", account = null))
        controller.refresh(grok)
        assertEquals(null, controller.account(grok))
    }
}
