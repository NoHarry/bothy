package cc.noharry.agent.appserver.server

import android.content.Context
import cc.noharry.agent.appserver.finding
import cc.noharry.agent.appserver.model.DeepSeekCheck
import cc.noharry.agent.appserver.model.DeepSeekProbe
import cc.noharry.agent.appserver.model.DeepSeekProbes
import cc.noharry.agent.appserver.model.FetchFallback
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.model.SearchBackend
import cc.noharry.agent.appserver.model.contextWindowOverride
import cc.noharry.agent.appserver.model.deepSeekProbeFingerprint
import cc.noharry.agent.appserver.model.deepSeekRelayClient
import cc.noharry.agent.appserver.model.defaultContextWindow
import cc.noharry.agent.appserver.model.fetchDeepSeekBalance
import cc.noharry.agent.appserver.model.hasDeepSeekKey
import cc.noharry.agent.appserver.model.loadModelImageInput
import cc.noharry.agent.appserver.model.saveContextWindow
import cc.noharry.agent.appserver.model.saveModelImageInput
import cc.noharry.agent.appserver.model.view
import cc.noharry.agent.appserver.protocol.DeepSeekBalanceParams
import cc.noharry.agent.appserver.protocol.DeepSeekBalanceResult
import cc.noharry.agent.appserver.protocol.DeepSeekCheckView
import cc.noharry.agent.appserver.protocol.DeepSeekProbeView
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.ModelSettingsRequests
import cc.noharry.agent.appserver.protocol.ModelSettingsResult
import cc.noharry.agent.appserver.protocol.ModelSettingsView
import cc.noharry.agent.appserver.protocol.ProviderConnection
import cc.noharry.agent.appserver.protocol.WebLookupView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Model and service settings beyond the profile: each model's context window and image input, the DeepSeek balance and
 * check, and web search / the fetch fallback. Keys go in only; a check's key is resolved here and never sent back.
 */
internal class ModelSettingsRoutes(private val app: Context) : RouteModule {
    override fun RouteTable.install() {
        on(ModelSettingsRequests.MODELS_READ) { params ->
            withContext(Dispatchers.IO) {
                ModelSettingsResult(
                    params.models.map { model ->
                        val profile = params.profile.copy(model = model)
                        ModelSettingsView(
                            model = model,
                            windowOverride = contextWindowOverride(app, profile),
                            defaultWindow = defaultContextWindow(model),
                            imageInput = loadModelImageInput(app, profile),
                        )
                    },
                )
            }
        }
        on(ModelSettingsRequests.MODELS_SAVE) { params ->
            params.models.forEach { settings ->
                val profile = params.profile.copy(model = settings.model)
                saveContextWindow(app, profile, settings.windowOverride)
                saveModelImageInput(app, profile, settings.imageInput)
            }
            Empty
        }
        on(ModelSettingsRequests.DEEPSEEK_BALANCE) { balance(it) }
        on(ModelSettingsRequests.DEEPSEEK_PROBE) { connection ->
            withContext(Dispatchers.IO) { probeView(connection, DeepSeekProbes.saved(app, connection.profileId)) }
        }
        on(ModelSettingsRequests.DEEPSEEK_CHECK) { connection ->
            val key = withContext(Dispatchers.IO) { keyOf(connection) }
            probeView(connection, DeepSeekProbes.check(app, connection.profile(key), key))
        }
        lookup()
    }

    private fun RouteTable.lookup() {
        on(ModelSettingsRequests.LOOKUP_READ) { withContext(Dispatchers.IO) { lookupView() } }
        on(ModelSettingsRequests.LOOKUP_SET_SEARCH_API) { params ->
            withContext(Dispatchers.IO) {
                SearchBackend.set(app, ProviderConfig.load(app).activeProfile.id, params.on)
                lookupView()
            }
        }
        on(ModelSettingsRequests.LOOKUP_SET_FETCH_FALLBACK) { params ->
            withContext(Dispatchers.IO) {
                FetchFallback.set(app, params.on)
                lookupView()
            }
        }
    }

    /** The balance, or why it could not be read. */
    private suspend fun balance(params: DeepSeekBalanceParams): DeepSeekBalanceResult {
        val connection = params.connection
        val url = params.url
        val result = if (connection == null || url == null) {
            val key = params.apiKey.ifBlank { connection?.let { withContext(Dispatchers.IO) { keyOf(it) } }.orEmpty() }
            fetchDeepSeekBalance(key)
        } else {
            val (key, relay) = withContext(Dispatchers.IO) { keyOf(connection) to deepSeekRelayClient(app) }
            fetchDeepSeekBalance(key, relay, url)
        }
        return result.fold(
            { DeepSeekBalanceResult(balance = it.view()) },
            { DeepSeekBalanceResult(failure = Finding(FindingCode.MODEL_BALANCE_FAILED, more = listOf(it.finding()))) },
        )
    }

    private suspend fun probeView(connection: ProviderConnection, probe: DeepSeekProbe?): DeepSeekProbeView {
        val key = withContext(Dispatchers.IO) { keyOf(connection) }
        val current = probe?.takeIf { it.fingerprint == deepSeekProbeFingerprint(connection.endpoint, connection.baseUrl, key) }
        return DeepSeekProbeView(
            checked = probe != null,
            current = current != null,
            hasKey = key.isNotBlank(),
            balance = current?.balance?.view(),
            tools = current?.tools?.view(),
            passthrough = current?.passthrough?.view(),
        )
    }

    /** The key a run on [connection] would use: the one typed, else the one the configuration holds for it. */
    private fun keyOf(connection: ProviderConnection): String = ProviderConfig.load(app).let { config ->
        config.effectiveApiKey(connection.profile(config.keyFor(connection.profileId, connection.apiKey)))
    }

    private fun lookupView(): WebLookupView {
        val config = ProviderConfig.load(app)
        val profile = config.activeProfile
        return WebLookupView(
            profileId = profile.id,
            profileLabel = profile.displayLabel,
            searchApi = SearchBackend.usesApi(app, profile.id),
            // The on-device model and the No web_search profile have no search of their own.
            ownSearch = profile.serverSideSearch && profile.searchCapability != "none",
            fetchFallback = FetchFallback.enabled(app),
            deepSeekKey = config.hasDeepSeekKey(app),
        )
    }
}

/** The profile as filled in, for the DeepSeek check; its passthrough check needs a model, the selected one or the first. */
private fun ProviderConnection.profile(apiKey: String): ProviderProfile =
    ProviderProfile(profileId, label, provider, apiKey, model, baseUrl.trim(), endpoint)

private fun DeepSeekCheck.view() = DeepSeekCheckView(passed, note, url)
