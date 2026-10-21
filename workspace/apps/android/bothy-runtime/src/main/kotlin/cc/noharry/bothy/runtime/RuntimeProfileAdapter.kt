@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.runtime

import cc.noharry.bothy.kernel.api.AgentCapabilities
import cc.noharry.bothy.kernel.api.AgentPlugin
import cc.noharry.bothy.kernel.api.AgentProfile
import cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.bothy.kernel.api.RuntimeContextProvider
import cc.noharry.bothy.kernel.api.RuntimeTurnObserver
import cc.noharry.bothy.kernel.api.SessionEventLog
import cc.noharry.bothy.kernel.api.ToolProvider
import cc.noharry.bothy.kernel.api.model.ModelClient
import cc.noharry.bothy.runtime.eventlog.FileBackedSessionEventLog
import cc.noharry.bothy.runtime.eventlog.InMemorySessionEventLog
import java.io.File
import cc.noharry.bothy.kernel.api.ExecEngine
import cc.noharry.bothy.kernel.api.RuntimeHook
import cc.noharry.bothy.kernel.core.CanonicalCapabilityKeys
import cc.noharry.bothy.kernel.core.DirectCapabilityPlugin
import cc.noharry.bothy.kernel.core.ProfileCompiler
import cc.noharry.bothy.kernel.core.RuntimeBlueprint
import cc.noharry.bothy.kernel.core.RuntimeCapabilityDeclarations

/** Compiles builder declarations plus packaged plugins through the single ProfileCompiler path. */
internal object RuntimeProfileAdapter {
    fun compile(
        profile: AgentProfile,
        declarations: RuntimeCapabilityDeclarations,
        packagedPlugins: List<AgentPlugin>,
    ): RuntimeBlueprint {
        val plugins = listOfNotNull(DirectCapabilityPlugin.from(declarations)) + packagedPlugins
        val blueprint = ProfileCompiler().compile(profile, plugins)
        try {
            CanonicalCapabilityKeys.validate(blueprint)
            return blueprint
        } catch (error: Throwable) {
            blueprint.close()
            throw error
        }
    }

    fun resolve(
        blueprint: RuntimeBlueprint,
        sessionEventLogRoot: File?,
        nowEpochMillis: () -> Long,
    ): BlueprintCapabilities {
        val eventLog = blueprint.resolveShared(AgentCapabilities.SESSION_EVENT_LOG)
            ?: sessionEventLogRoot?.let {
                cc.noharry.bothy.runtime.eventlog.AsyncSessionEventLog(FileBackedSessionEventLog(it, nowEpochMillis), nowEpochMillis)
            }
            ?: InMemorySessionEventLog(nowEpochMillis)
        return BlueprintCapabilities(
            modelClient = blueprint.resolveShared(AgentCapabilities.MODEL_CLIENT),
            execEngine = blueprint.resolveShared(AgentCapabilities.EXEC_ENGINE),
            eventLog = eventLog,
            toolProviders = blueprint.resolveSharedAll(AgentCapabilities.TOOL_PROVIDER),
            contextProviders = uniqueById(
                "contextProvider",
                blueprint.resolveSharedAll(AgentCapabilities.RUNTIME_CONTEXT_PROVIDER),
                RuntimeContextProvider::id,
            ),
            turnObservers = uniqueById(
                "turnObserver",
                blueprint.resolveSharedAll(AgentCapabilities.RUNTIME_TURN_OBSERVER),
                RuntimeTurnObserver::id,
            ),
            hooks = blueprint.resolveSharedAll(AgentCapabilities.RUNTIME_HOOK),
        )
    }

}

internal data class BlueprintCapabilities(
    val modelClient: ModelClient?,
    val execEngine: ExecEngine?,
    val eventLog: SessionEventLog,
    val toolProviders: List<ToolProvider>,
    val contextProviders: List<RuntimeContextProvider>,
    val turnObservers: List<RuntimeTurnObserver>,
    val hooks: List<RuntimeHook>,
)

private fun <T : Any> uniqueById(
    kind: String,
    values: Collection<T>,
    id: (T) -> String,
): List<T> {
    val seen = linkedSetOf<String>()
    values.forEach { value ->
        val valueId = id(value)
        require(valueId.isNotBlank()) { "$kind id must not be blank." }
        require(seen.add(valueId)) { "Duplicate $kind id: $valueId" }
    }
    return values.toList()
}
