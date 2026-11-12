package cc.noharry.bothy.app.model

import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.ModelFacts
import cc.noharry.agent.appserver.protocol.ModelSettingsParams
import cc.noharry.agent.appserver.protocol.ModelSettingsRequests
import cc.noharry.agent.appserver.protocol.ModelSettingsResult
import cc.noharry.agent.appserver.protocol.ModelSettingsView
import cc.noharry.agent.appserver.protocol.ProviderCreateParams
import cc.noharry.agent.appserver.protocol.ProviderCreateResult
import cc.noharry.agent.appserver.protocol.ProviderEditState
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.ServiceEndpointView
import cc.noharry.agent.appserver.protocol.ServiceListResult
import cc.noharry.agent.appserver.protocol.ServiceRequests
import cc.noharry.agent.appserver.protocol.ServiceView
import cc.noharry.agent.appserver.protocol.SettingsRequests
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import cc.noharry.agent.appserver.protocol.text
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.frontend.ServerSender
import cc.noharry.bothy.protocol.BothyFindingCode
import cc.noharry.bothy.protocol.CheckLine
import cc.noharry.bothy.protocol.OnboardingRequests

/** DeepSeek as `services/list` gives it: one host, two endpoint kinds, a balance and a relay check. */
internal val DEEPSEEK = ServiceView(
    id = "deepseek",
    label = "DeepSeek",
    endpoints = listOf(
        ServiceEndpointView(ProviderEndpoint.CHAT_COMPLETIONS, "https://api.deepseek.com"),
        ServiceEndpointView(ProviderEndpoint.MESSAGES, "https://api.deepseek.com/anthropic"),
    ),
    keyHint = "Create an API key at https://platform.deepseek.com/api_keys",
    keyPages = mapOf("" to "https://platform.deepseek.com/api_keys"),
    models = listOf("deepseek-flash", "deepseek-pro"),
    balance = true,
    relayCheck = true,
    defaultModel = "deepseek-flash",
)

/** Moonshot: hosts in China and abroad, whose keys do not cross. */
internal val MOONSHOT = ServiceView(
    id = "moonshot",
    label = "Moonshot (Kimi)",
    endpoints = listOf(
        ServiceEndpointView(ProviderEndpoint.CHAT_COMPLETIONS, "https://api.moonshot.cn/v1", "cn"),
        ServiceEndpointView(ProviderEndpoint.CHAT_COMPLETIONS, "https://api.moonshot.ai/v1", "global"),
    ),
    keyHint = "Create an API key at https://platform.kimi.com/console/api-keys (international: https://platform.kimi.ai/console/api-keys); the two sites' keys are not interchangeable",
    keyPages = mapOf("cn" to "https://platform.kimi.com/console/api-keys", "global" to "https://platform.kimi.ai/console/api-keys"),
    models = listOf("kimi-k3.1-pro", "kimi-k3.1-flash"),
    defaultModel = "kimi-k3.1-pro",
)

/** OpenRouter: a list of its own to fetch, no default model. */
internal val OPENROUTER = ServiceView(
    id = "openrouter",
    label = "OpenRouter",
    endpoints = listOf(ServiceEndpointView(ProviderEndpoint.CHAT_COMPLETIONS, "https://openrouter.ai/api/v1")),
    keyHint = "Create an API key at https://openrouter.ai/keys",
    keyPages = mapOf("" to "https://openrouter.ai/keys"),
)

/**
 * The server as the provider form and the setup's model key talk to it: the [services] it knows, each model's facts, a
 * new profile's id, a key that checks out, and what was sent.
 */
internal class FakeModelServer(private val services: List<ServiceView>) : ServerSender {
    val sent = mutableListOf<Pair<String, Any?>>()

    /** The params of every [method] request sent, in order. */
    @Suppress("UNCHECKED_CAST")
    fun <P> sentFor(method: RequestMethod<P, *>): List<P> = sent.filter { it.first == method.name }.map { it.second as P }

    @Suppress("UNCHECKED_CAST")
    override fun <P, R> send(method: RequestMethod<P, R>, params: P, onRefused: (JsonRpcException) -> Unit, onAnswer: (R) -> Unit) {
        sent += method.name to params
        when (method.name) {
            ServiceRequests.LIST.name -> onAnswer(ServiceListResult(services) as R)
            ModelSettingsRequests.MODELS_READ.name -> onAnswer(ModelSettingsResult((params as ModelSettingsParams).models.map(::facts)) as R)
            ModelSettingsRequests.MODELS_SAVE.name -> onAnswer(Empty as R)
            SettingsRequests.PROVIDER_CREATE.name -> onAnswer(ProviderCreateResult("manual-created") as R)
            SettingsRequests.PROVIDER_SAVE_MODELS.name -> onAnswer(Empty as R)
            OnboardingRequests.SAVE_MODEL_KEY.name -> onAnswer(CheckLine(Finding(BothyFindingCode.ONBOARDING_MODEL_READY, more = listOf(text("checked")))) as R)
        }
    }

    private fun facts(model: String) = ModelSettingsView(
        model,
        defaultWindow = 128_000,
        facts = ModelFacts(maxOutputTokens = 64_000, requestOutputTokens = 32_000),
    )
}

/** What the form asked the console's actions to do. */
internal class RecordedActions {
    val created = mutableListOf<ProviderCreateParams>()
    val savedModels = mutableListOf<Pair<ProviderEditState, List<String>>>()
    val savedConnections = mutableListOf<String>()

    val actions = ConsoleActions(
        submit = {}, steer = {}, approveConfirmation = {}, denyConfirmation = {}, selectConfirmationMode = {}, stop = {},
        newSession = {}, selectSession = {}, deleteSession = {}, clearRuntimeData = {}, selectProvider = {}, selectProfile = {},
        setManualProfileEnabled = {}, selectModel = {}, selectVariant = {}, toggleStream = {}, selectBackend = {}, reloadConfig = {},
        saveConfig = { _, _, _, baseUrl, _, done -> savedConnections += baseUrl; done(null) },
        saveNewProfile = { params, saved -> created += params; saved("manual-created") },
        deleteProfile = {},
        fetchModels = { _, _, _, _, _, _, _ -> },
        saveProviderModels = { editor, models -> savedModels += editor to models },
        toggleItem = {}, setMcpProviderEnabled = { _, _ -> }, setA2aProfileEnabled = { _, _ -> },
        saveA2aProfile = { _, _ -> }, deleteA2aProfile = {}, pickSafWorkspace = {}, clearSafWorkspace = {}, openOverlay = {},
        openHtmlViewer = { _, _ -> },
    )
}
