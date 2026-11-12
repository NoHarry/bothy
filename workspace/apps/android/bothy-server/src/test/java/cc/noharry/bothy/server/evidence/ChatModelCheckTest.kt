package cc.noharry.bothy.server.evidence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.model.ModelService
import cc.noharry.agent.appserver.model.ProviderAdapter
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.model.ServiceEndpoint
import cc.noharry.agent.appserver.model.ServiceModel
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.ServiceView
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.parts
import cc.noharry.agent.appserver.protocol.text
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.agent.appserver.server.PluginAdditions
import cc.noharry.bothy.protocol.BothyFindingCode
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The selected model's check (设置 → 检查, 保存并检查) reads a service's model list as the server lists it, and on a
 * service with no model list still tells a good key from a wrong one, against a server on this machine that answers as
 * the service would: a list whose ids carry a prefix (Gemini's `models/`), no list at all (Zhipu, Model Studio: `/models`
 * is not there), and a plain OpenAI-compatible relay.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatModelCheckTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val answers = ServiceOnThisMachine()
    private lateinit var base: String

    @Before
    fun start() {
        AppServerTesting.reset()
        server.dispatcher = answers
        server.start()
        base = server.url("/v1").toString().trimEnd('/')
    }

    @After
    fun stop() {
        server.shutdown()
        AppServerTesting.reset()
    }

    @Test
    fun aServiceWhoseListPrefixesItsIdsListsTheSelectedModel() = runBlocking {
        val gemini = service(listedIdPrefix = "models/")

        val result = check(gemini, profile(model = "flash", key = GOOD_KEY))

        assertEquals(result.finding.toString(), CheckState.OK, result.state)
        assertEquals(Finding(BothyFindingCode.CHECKS_SERVICE_AVAILABLE, more = listOf(name("flash"))), result.finding)
        assertEquals("/v1/models", server.takeRequest(1, TimeUnit.SECONDS)?.path)
    }

    @Test
    fun aServiceWithNoListAndAGoodKeyIsAvailableAfterOneToken() = runBlocking {
        val zhipu = service(listsModels = false)

        val result = check(zhipu, profile(model = "flash", key = GOOD_KEY))

        assertEquals(result.finding.toString(), CheckState.OK, result.state)
        val asked = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/chat/completions", asked.path)
        assertEquals("Bearer $GOOD_KEY", asked.getHeader("Authorization"))
        val body = asked.body.readUtf8()
        assertTrue(body, body.contains("\"max_tokens\":1") && body.contains("\"model\":\"flash\""))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aServiceWithNoListAndAWrongKeyIsAWrongKey() = runBlocking {
        val zhipu = service(listsModels = false)

        val result = check(zhipu, profile(model = "flash", key = "sk-wrong"))

        assertEquals(CheckState.FAIL, result.state)
        assertEquals(CheckFix.MODELS, result.fix)
        assertEquals(parts(name("flash"), finding(BothyFindingCode.CHECKS_HTTP_KEY, 401)), result.finding)
    }

    @Test
    fun aServiceWithNoListThatDoesNotOfferTheModelSaysWhatItOffers() = runBlocking {
        val zhipu = service(listsModels = false)

        val result = check(zhipu, profile(model = "retired", key = GOOD_KEY))

        assertEquals(CheckState.WARN, result.state)
        assertEquals(BothyFindingCode.CHECKS_MODEL_NOT_LISTED.name, result.finding?.code)
        assertEquals(listOf("flash", "pro"), result.finding!!.more[2].more.map { it.args.single() })
    }

    @Test
    fun aPlainRelayIsCheckedAgainstItsListAsBefore() = runBlocking {
        val listed = check(null, profile(model = "relay-chat", key = GOOD_KEY))
        val stale = check(null, profile(model = "gone", key = GOOD_KEY))
        val wrongKey = check(null, profile(model = "relay-chat", key = "sk-wrong"))

        assertEquals(CheckState.OK, listed.state)
        assertEquals(CheckState.WARN, stale.state)
        assertEquals(BothyFindingCode.CHECKS_MODEL_NOT_LISTED.name, stale.finding?.code)
        assertEquals(parts(name("relay-chat"), finding(BothyFindingCode.CHECKS_HTTP_KEY, 401)), wrongKey.finding)
    }

    /** [profile] selected, on [service] when one is given (its adapter running), checked against the server's list. */
    private suspend fun check(service: ModelService?, profile: ProviderProfile): CheckResult {
        val adapters = listOfNotNull(service).map { known -> object : ProviderAdapter { override val services = listOf(known) } }
        answers.hasList = service?.listsModels != false
        AppServerTesting.running(PluginAdditions(providerAdapters = adapters))
        val config = ProviderConfig.load(context).let { it.copy(profiles = it.profiles + profile, activeProfileId = profile.id) }
        val lists = object : ModelListServer {
            override suspend fun services(): List<ServiceView> = listOfNotNull(service?.view())

            override suspend fun models(profile: ProviderProfile): List<String> = AppServerTesting.models(context, profile, profile.apiKey)
        }
        return checkSelectedModel(config, lists)
    }

    private fun service(listsModels: Boolean = true, listedIdPrefix: String = "") = ModelService(
        id = "service",
        label = "Service",
        endpoints = listOf(ServiceEndpoint(ProviderEndpoint.CHAT_COMPLETIONS, base)),
        models = listOf(ServiceModel("flash"), ServiceModel("pro")),
        listsModels = listsModels,
        listedIdPrefix = listedIdPrefix,
    )

    private fun profile(model: String, key: String) =
        ProviderProfile("checked", "Checked", ModelProvider.OPENAI, key, model, base, ProviderEndpoint.CHAT_COMPLETIONS, streamEnabled = false)

    private fun name(model: String) = parts(text("Checked"), text(model))

    /**
     * A service on this machine: its list at `/v1/models`, two ids with Gemini's `models/` prefix and a relay's plain
     * one, or, without a list ([hasList] false, as Zhipu and Model Studio), no such address whatever the key; 401 for
     * any key but [GOOD_KEY]; one token of chat for the model `flash`, and an unknown model for any other.
     */
    private class ServiceOnThisMachine : Dispatcher() {
        @Volatile
        var hasList = true

        override fun dispatch(request: RecordedRequest): MockResponse {
            if (!hasList && request.path == "/v1/models") return json(404, """{"error":{"message":"Not Found"}}""")
            if (request.getHeader("Authorization") != "Bearer $GOOD_KEY") {
                return json(401, """{"error":{"message":"Authentication Fails, your api key is invalid"}}""")
            }
            val body = request.body.peek().readUtf8()
            return when (request.path) {
                "/v1/models" -> json(200, """{"object":"list","data":[{"id":"models/flash"},{"id":"models/pro"},{"id":"relay-chat"}]}""")
                "/v1/chat/completions" -> if (body.contains("\"model\":\"flash\"")) {
                    json(200, """{"choices":[{"index":0,"message":{"role":"assistant","content":"Hi"},"finish_reason":"length"}],"usage":{"prompt_tokens":6,"completion_tokens":1}}""")
                } else {
                    json(400, """{"error":{"code":"1211","message":"Unknown model"}}""")
                }
                else -> json(404, """{"error":{"message":"Not Found"}}""")
            }
        }
    }

    private companion object {
        const val GOOD_KEY = "sk-good"

        fun json(status: Int, body: String): MockResponse =
            MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    }
}
