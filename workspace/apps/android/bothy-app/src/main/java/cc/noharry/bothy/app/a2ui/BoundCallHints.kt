@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.a2ui
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolPrefetchProvider
import cc.noharry.agent.kernel.api.ToolPrefetchRequest
import cc.noharry.agent.kernel.api.ToolPrefetchTrace
import cc.noharry.agent.kernel.api.ToolProvider

/**
 * Offers a picked option's bound tool from the first round of the run that acts on the pick, so the model calls it
 * directly instead of searching for it. Catalogue hints only; execution, identity and the delegate's own prefetch
 * stay on the delegate.
 */
internal class BoundCallHints(private val delegate: ToolProvider) : ToolProvider by delegate, ToolPrefetchProvider {
    override val providerConcurrency: ProviderConcurrencyDeclaration = delegate.providerConcurrency
    private val inner: ToolPrefetchProvider? get() = delegate as? ToolPrefetchProvider
    override val remotePrefetchEnabled: Boolean get() = inner?.remotePrefetchEnabled == true
    override val prefetchOnSearch: Boolean get() = inner?.prefetchOnSearch == true
    override val prefetchTimeoutMillis: Long get() = inner?.prefetchTimeoutMillis ?: 1L

    override fun localToolNames(request: ToolPrefetchRequest): List<String> {
        val picked = OfferStore.pickFor(request.context.sessionId, request.input)?.calls.orEmpty()
            .filter { it.providerId == delegate.providerId }.map { it.toolName }
        return (picked + inner?.localToolNames(request).orEmpty()).distinct().take(request.maxTools)
    }

    override suspend fun prefetchTools(request: ToolPrefetchRequest, trace: ToolPrefetchTrace): List<String> =
        inner?.prefetchTools(request, trace).orEmpty()
}
