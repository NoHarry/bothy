package cc.noharry.agent.appserver.protocol.deepseek

import cc.noharry.agent.appserver.protocol.BalanceView
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.Serializable

/*
 * The DeepSeek adapter's requests: a key's balance and the check of a relay. A server answers them only when its host
 * installs that adapter.
 */

/** DeepSeek's own API host; a profile there has the balance query and the Search and Fetch APIs. */
const val DEEPSEEK_HOST = "api.deepseek.com"

/** Whether [this] base URL is on DeepSeek's own host. */
fun String.isDeepSeekUrl(): Boolean = runCatching { java.net.URI(trim()).host }.getOrNull() == DEEPSEEK_HOST

/** The connection a DeepSeek check is about, as filled in: saved or not; a blank [apiKey] is the one saved for [profileId]. */
@Serializable
data class ProviderConnection(
    val profileId: String,
    val label: String = "",
    val provider: ModelProvider = ModelProvider.OPENAI,
    val endpoint: ProviderEndpoint = ProviderEndpoint.defaultFor(ModelProvider.OPENAI),
    val apiKey: String = "",
    val model: String = "",
    val baseUrl: String = "",
)

/**
 * An official key's balance with the key typed in ([apiKey]), else the one saved for [connection]; or, with [connection]
 * and [url], a relay's at [url], with the key the server resolves for that connection (the key never comes back).
 */
@Serializable
data class DeepSeekBalanceParams(val apiKey: String = "", val connection: ProviderConnection? = null, val url: String? = null)

/** The balance, or why it could not be read. */
@Serializable
data class DeepSeekBalanceResult(val balance: BalanceView? = null, val failure: Finding? = null)

/** One part of a DeepSeek check: passed with the [url] that worked, or failed; [note] says what came back. */
@Serializable
data class DeepSeekCheckView(val passed: Boolean, val note: Finding, val url: String? = null)

/**
 * A profile's DeepSeek check as stored: whether there is one ([checked]), whether it was made for the connection as now
 * filled in ([current]), whether that connection has a key at all ([hasKey]), and its parts when current.
 */
@Serializable
data class DeepSeekProbeView(
    val checked: Boolean = false,
    val current: Boolean = false,
    val hasKey: Boolean = false,
    val balance: DeepSeekCheckView? = null,
    val tools: DeepSeekCheckView? = null,
    /** Null off the Messages endpoint, which is the only one with native updates. */
    val passthrough: DeepSeekCheckView? = null,
)

/** DeepSeek's own requests, answered by a server whose host installs its adapter. */
object DeepSeekRequests {
    val BALANCE = RequestMethod("deepseek/balance", DeepSeekBalanceParams.serializer(), DeepSeekBalanceResult.serializer())

    /** The stored check of the connection's profile, measured against the connection as filled in. */
    val PROBE = RequestMethod("deepseek/probe", ProviderConnection.serializer(), DeepSeekProbeView.serializer())

    /** Checks the connection as filled in and keeps the result for its profile. */
    val CHECK = RequestMethod("deepseek/check", ProviderConnection.serializer(), DeepSeekProbeView.serializer())
}
