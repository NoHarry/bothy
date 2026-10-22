@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolPrefetchProvider
import cc.noharry.agent.kernel.api.ToolPrefetchRequest
import cc.noharry.agent.kernel.api.ToolPrefetchTrace
import cc.noharry.agent.kernel.api.ToolProvider

/** Catalogue-only enrichment; tool execution, identity and permissions remain on the delegate. */
internal class DebugSkillToolProvider(
    private val delegate: ToolProvider,
    private val workflows: List<DebugWorkflowSkill>,
) : ToolProvider by delegate, ToolPrefetchProvider {
    override val providerConcurrency: ProviderConcurrencyDeclaration = delegate.providerConcurrency
    private val remote: ToolPrefetchProvider? get() = delegate as? ToolPrefetchProvider
    override val remotePrefetchEnabled: Boolean get() = remote?.remotePrefetchEnabled == true
    override val prefetchOnSearch: Boolean get() = remote?.prefetchOnSearch == true
    override val prefetchTimeoutMillis: Long get() = remote?.prefetchTimeoutMillis ?: 1L

    override suspend fun listTools(): List<ToolDescriptor> {
        val tools = delegate.listTools()
        val selected = compatibleWorkflowTools(workflows, tools).map { it.name }.toSet()
        return tools.map { tool ->
            if (tool.name !in selected) tool else tool.copy(
                description = tool.description + debugOutputContractSummary(tool.outputSchemaJson),
            )
        }
    }

    override fun localToolNames(request: ToolPrefetchRequest): List<String> =
        (compatibleWorkflowTools(workflows, request.tools).map { it.name } + remote?.localToolNames(request).orEmpty())
            .distinct().take(request.maxTools)

    override suspend fun prefetchTools(request: ToolPrefetchRequest, trace: ToolPrefetchTrace): List<String> =
        remote?.prefetchTools(request, trace).orEmpty()
}
