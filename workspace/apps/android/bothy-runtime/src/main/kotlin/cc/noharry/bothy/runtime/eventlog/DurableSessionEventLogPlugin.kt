@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.runtime.eventlog

import android.content.Context
import cc.noharry.bothy.kernel.api.AgentCapabilities
import cc.noharry.bothy.kernel.api.AgentPlugin
import cc.noharry.bothy.kernel.api.AgentPluginDescriptor
import cc.noharry.bothy.kernel.api.AgentPluginScope
import cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.bothy.kernel.api.KernelApiRange
import cc.noharry.bothy.kernel.api.KernelApiVersions
import cc.noharry.bothy.kernel.api.PluginVersion
import cc.noharry.bothy.kernel.api.ProviderConcurrencyDeclaration
import java.io.File

/**
 * Packaged SINGLE [AgentCapabilities.SESSION_EVENT_LOG] using a host-owned app-private
 * no-backup root. Official composition entry; not a second Builder.
 */
@ExperimentalAgentRuntimeApi
class DurableSessionEventLogPlugin private constructor(
    private val root: File,
) : AgentPlugin {
    override val id: String = PLUGIN_ID
    override val descriptor: AgentPluginDescriptor = AgentPluginDescriptor(
        id = PLUGIN_ID,
        pluginVersion = PluginVersion(1, 0, 0),
        kernelApiRange = KernelApiRange(
            min = KernelApiVersions.CURRENT,
            maxInclusive = KernelApiVersions.CURRENT,
        ),
        provides = setOf(AgentCapabilities.SESSION_EVENT_LOG),
    )

    override fun install(scope: AgentPluginScope) {
        scope.provide(
            AgentCapabilities.SESSION_EVENT_LOG,
            AsyncSessionEventLog(FileBackedSessionEventLog(root)),
            ProviderConcurrencyDeclaration.serialized(),
        )
    }

    companion object {
        const val PLUGIN_ID: String = "durable-session-event-log"
        const val ROOT_DIRECTORY: String = "bothy-runtime"

        fun appPrivate(context: Context): DurableSessionEventLogPlugin {
            return DurableSessionEventLogPlugin(File(context.noBackupFilesDir, ROOT_DIRECTORY))
        }

        fun forRoot(root: File): DurableSessionEventLogPlugin = DurableSessionEventLogPlugin(root)
    }
}
