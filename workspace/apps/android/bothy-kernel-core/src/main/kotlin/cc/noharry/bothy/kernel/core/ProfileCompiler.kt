@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.kernel.core

import cc.noharry.bothy.kernel.api.AgentPlugin
import cc.noharry.bothy.kernel.api.AgentProfile
import cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.bothy.kernel.api.KernelApiVersion
import cc.noharry.bothy.kernel.api.KernelApiVersions
import cc.noharry.bothy.kernel.api.PluginContractMessages

/**
 * Compiles an [AgentProfile] and packaged plugins into an immutable
 * [RuntimeBlueprint]. This is the only new composition path; it is not a
 * second runtime builder or execution loop.
 */
@ExperimentalAgentRuntimeApi
class ProfileCompiler {
    fun compile(
        profile: AgentProfile,
        plugins: List<AgentPlugin>,
        kernelApiVersion: KernelApiVersion = KernelApiVersions.CURRENT,
    ): RuntimeBlueprint {
        require(profile.kernelApiVersion == kernelApiVersion) {
            PluginContractMessages.PROFILE_KERNEL_MISMATCH
        }
        val registry = PluginRegistry()
        try {
            registry.installAll(plugins, kernelApiVersion)
            return RuntimeBlueprint(profile, registry)
        } catch (error: Throwable) {
            registry.close()
            throw error
        }
    }
}
