@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.model

import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelClientException
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.bothy.llm.LlamaServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * The on-device profile's model client: waits for the server ([ready]; the first call may load the model), then
 * delegates to the client [clientFor] builds for that start's address and key. One call at a time: the server has a
 * single slot. A server that cannot start fails the run with its reason rather than being retried. A tool call the
 * model wrote as text goes on as that call ([withTextToolCalls]), and a call of a backend tool it made up as a search
 * ([withMadeUpCallsSearched]).
 */
internal class OnDeviceModelClient(
    private val ready: suspend () -> LlamaServer.State.Ready,
    private val clientFor: (LlamaServer.State.Ready) -> ModelClient,
) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration = ProviderConcurrencyDeclaration.serialized()

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        val server = try {
            ready()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ModelClientException(
                code = RuntimeErrorCode.PROVIDER_TRANSPORT_FAILED,
                recoverable = false,
                message = e.message ?: "本机模型没有启动",
                cause = e,
            )
        }
        val events = clientFor(server).complete(request).withTextToolCalls(request.tools.mapTo(HashSet()) { it.name })
        emitAll(events.withMadeUpCallsSearched(request))
    }
}
