@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.kernel.core

import cc.noharry.bothy.kernel.api.CapabilityKey
import cc.noharry.bothy.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.bothy.kernel.api.SessionCapabilityFactory

sealed class CapabilityBinding {
    abstract val key: CapabilityKey<*>
    abstract val concurrency: ProviderConcurrencyDeclaration
}

class SharedCapabilityBinding(
    override val key: CapabilityKey<*>,
    val value: Any,
    override val concurrency: ProviderConcurrencyDeclaration,
) : CapabilityBinding()

class FactoryCapabilityBinding(
    override val key: CapabilityKey<*>,
    val factory: SessionCapabilityFactory<*>,
    override val concurrency: ProviderConcurrencyDeclaration,
) : CapabilityBinding()

fun bindingsMixSharedAndFactory(bindings: Iterable<CapabilityBinding>): Boolean {
    var shared = false
    var factory = false
    for (binding in bindings) {
        when (binding) {
            is SharedCapabilityBinding -> shared = true
            is FactoryCapabilityBinding -> factory = true
        }
        if (shared && factory) return true
    }
    return false
}
