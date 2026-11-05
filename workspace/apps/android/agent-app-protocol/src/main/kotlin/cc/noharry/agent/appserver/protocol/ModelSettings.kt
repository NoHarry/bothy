package cc.noharry.agent.appserver.protocol

import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.Serializable

/** DeepSeek's own API host; a profile there has the balance query and the Search and Fetch APIs. */
const val DEEPSEEK_HOST = "api.deepseek.com"

/** Whether [this] base URL is on DeepSeek's own host. */
fun String.isDeepSeekUrl(): Boolean = runCatching { java.net.URI(trim()).host }.getOrNull() == DEEPSEEK_HOST

/** A context window as typed: `128000`, `128K` or `1M`; null when it is not a positive whole number of tokens. */
fun parseContextWindow(text: String): Long? {
    val value = text.trim().uppercase()
    val multiplier = when (value.lastOrNull()) { 'K' -> 1_000L; 'M' -> 1_000_000L; else -> 1L }
    val number = if (multiplier == 1L) value else value.dropLast(1)
    return runCatching { number.toBigDecimal().multiply(multiplier.toBigDecimal()).longValueExact() }
        .getOrNull()?.takeIf { it > 0 }
}

/** Model IDs trimmed, blanks dropped, each once, in their order. */
fun normalizeProviderModels(models: List<String>): List<String> =
    models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** One model of a profile: the context window set for it (null: its default), the default, and whether it takes images. */
@Serializable
data class ModelSettingsView(
    val model: String,
    val windowOverride: Long? = null,
    val defaultWindow: Long = 0,
    val imageInput: Boolean = true,
)

/** The profile (as [ProviderEditState] names it) and the models whose settings are asked for. */
@Serializable
data class ModelSettingsParams(val profile: ProviderEditState, val models: List<String>)

@Serializable
data class ModelSettingsResult(val models: List<ModelSettingsView>)

/** Each model's window ([ModelSettingsView.windowOverride], null for the default) and image input, saved for [profile]. */
@Serializable
data class ModelSettingsSaveParams(val profile: ProviderEditState, val models: List<ModelSettingsView>)

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
data class DeepSeekBalanceResult(val balance: DeepSeekBalanceView? = null, val failure: Finding? = null)

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

/**
 * 联网搜索 and the fetch fallback for the active profile: whether it searches through DeepSeek's Search API, whether it
 * has a search of its own, whether pages refused by the device go through DeepSeek's Fetch API, and whether any profile
 * has a DeepSeek key for those.
 */
@Serializable
data class WebLookupView(
    val profileId: String = "",
    val profileLabel: String = "",
    val searchApi: Boolean = false,
    val ownSearch: Boolean = true,
    val fetchFallback: Boolean = true,
    val deepSeekKey: Boolean = false,
)

/** Model and service settings beyond the profile itself. */
object ModelSettingsRequests {
    val MODELS_READ = RequestMethod("modelSettings/read", ModelSettingsParams.serializer(), ModelSettingsResult.serializer())
    val MODELS_SAVE = RequestMethod("modelSettings/save", ModelSettingsSaveParams.serializer(), Empty.serializer())
    val DEEPSEEK_BALANCE = RequestMethod("deepseek/balance", DeepSeekBalanceParams.serializer(), DeepSeekBalanceResult.serializer())

    /** The stored check of the connection's profile, measured against the connection as filled in. */
    val DEEPSEEK_PROBE = RequestMethod("deepseek/probe", ProviderConnection.serializer(), DeepSeekProbeView.serializer())

    /** Checks the connection as filled in and keeps the result for its profile. */
    val DEEPSEEK_CHECK = RequestMethod("deepseek/check", ProviderConnection.serializer(), DeepSeekProbeView.serializer())
    val LOOKUP_READ = RequestMethod("webLookup/read", Empty.serializer(), WebLookupView.serializer())
    val LOOKUP_SET_SEARCH_API = RequestMethod("webLookup/setSearchApi", FlagParams.serializer(), WebLookupView.serializer())
    val LOOKUP_SET_FETCH_FALLBACK = RequestMethod("webLookup/setFetchFallback", FlagParams.serializer(), WebLookupView.serializer())
}
