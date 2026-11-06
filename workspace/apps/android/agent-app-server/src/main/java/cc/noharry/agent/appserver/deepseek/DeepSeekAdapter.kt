package cc.noharry.agent.appserver.deepseek

import android.content.Context
import cc.noharry.agent.appserver.model.ProviderAdapter
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.model.WebLookup
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.server.RouteModule

/**
 * DeepSeek beyond chat ([ProviderAdapter]): its Search and Fetch APIs (on its own host, or behind a relay a check found
 * them behind), its system updates and `tool_addition` in a run's history on its Anthropic endpoint (or a relay that
 * passes them on), its balance and the relay check ([DeepSeekRoutes]), and one key for its two preset profiles.
 */
object DeepSeekAdapter : ProviderAdapter {
    override fun lookup(context: Context, config: ProviderConfig, profile: ProviderProfile): WebLookup? = config.deepSeekToolsOf(context, profile)

    // Only where the APIs are: a relay's client waits for the trust store, which start-up loads off the main thread.
    override fun hasLookup(context: Context, config: ProviderConfig, profile: ProviderProfile): Boolean =
        config.deepSeekToolsUrl(context, profile) != null

    override fun nativeUpdates(context: Context, config: ProviderConfig, profile: ProviderProfile): Boolean =
        profile.usesDeepSeekNativeUpdates() || profile.endpoint == ProviderEndpoint.MESSAGES &&
            DeepSeekProbes.current(context, profile, config.effectiveApiKey(profile))?.passthrough?.passed == true

    override fun routes(app: Context): List<RouteModule> = listOf(DeepSeekRoutes(app))

    override fun shareKeys(profiles: List<ProviderProfile>): List<ProviderProfile> = profiles.withSharedDeepSeekApiKeys()
}

/** DeepSeek's own Anthropic endpoint: system updates and `tool_addition` in history, `defer_loading` declarations. */
internal fun ProviderProfile.usesDeepSeekNativeUpdates(): Boolean =
    endpoint == ProviderEndpoint.MESSAGES && runCatching { java.net.URI(baseUrl).host }.getOrNull() == "api.deepseek.com"

/**
 * DeepSeek OpenAI-compatible and Anthropic-compatible ports share one API key.
 * When [DEEPSEEK_ANTHROPIC_PROFILE_ID] has a blank key, inherit from [DEEPSEEK_API_PROFILE_ID].
 */
internal fun List<ProviderProfile>.withSharedDeepSeekApiKeys(): List<ProviderProfile> {
    val openaiKey = firstOrNull { it.id.equals(DEEPSEEK_API_PROFILE_ID, ignoreCase = true) }
        ?.apiKey
        ?.takeIf { it.isNotBlank() }
        ?: return this
    return map { profile ->
        if (
            profile.id.equals(DEEPSEEK_ANTHROPIC_PROFILE_ID, ignoreCase = true) &&
            profile.apiKey.isBlank()
        ) {
            profile.copy(apiKey = openaiKey)
        } else {
            profile
        }
    }
}

internal const val DEEPSEEK_API_PROFILE_ID = "deepseek-api"
internal const val DEEPSEEK_ANTHROPIC_PROFILE_ID = "deepseek-anthropic"
