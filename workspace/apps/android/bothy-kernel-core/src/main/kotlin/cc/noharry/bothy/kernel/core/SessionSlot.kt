@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.kernel.core

import cc.noharry.bothy.kernel.api.CapabilityKey

class SessionSlot(
    val sessionId: String,
    val key: CapabilityKey<*>,
    val binding: CapabilityBinding,
) {
    override fun equals(other: Any?): Boolean =
        other is SessionSlot &&
            sessionId == other.sessionId &&
            key === other.key &&
            binding === other.binding

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + System.identityHashCode(key)
        result = 31 * result + System.identityHashCode(binding)
        return result
    }
}
