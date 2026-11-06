package cc.noharry.agent.appserver.deepseek

import android.content.Context
import cc.noharry.agent.appserver.finding
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.deepseek.DeepSeekBalanceParams
import cc.noharry.agent.appserver.protocol.deepseek.DeepSeekBalanceResult
import cc.noharry.agent.appserver.protocol.deepseek.DeepSeekCheckView
import cc.noharry.agent.appserver.protocol.deepseek.DeepSeekProbeView
import cc.noharry.agent.appserver.protocol.deepseek.DeepSeekRequests
import cc.noharry.agent.appserver.protocol.deepseek.ProviderConnection
import cc.noharry.agent.appserver.server.RouteModule
import cc.noharry.agent.appserver.server.RouteTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DeepSeek's own requests ([DeepSeekRequests]): an official key's or a relay's balance, and the check of a relay that
 * finds DeepSeek's balance, its Search and Fetch APIs and whether it passes DeepSeek's history fields on. Keys go in
 * only; a check's key is resolved here and never sent back.
 */
internal class DeepSeekRoutes(private val app: Context) : RouteModule {
    override fun RouteTable.install() {
        on(DeepSeekRequests.BALANCE) { balance(it) }
        on(DeepSeekRequests.PROBE) { connection ->
            withContext(Dispatchers.IO) { probeView(connection, DeepSeekProbes.saved(app, connection.profileId)) }
        }
        on(DeepSeekRequests.CHECK) { connection ->
            val key = withContext(Dispatchers.IO) { keyOf(connection) }
            probeView(connection, DeepSeekProbes.check(app, connection.profile(key), key))
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
}

/** The profile as filled in, for the DeepSeek check; its passthrough check needs a model, the selected one or the first. */
private fun ProviderConnection.profile(apiKey: String): ProviderProfile =
    ProviderProfile(profileId, label, provider, apiKey, model, baseUrl.trim(), endpoint)

private fun DeepSeekCheck.view() = DeepSeekCheckView(passed, note, url)
