package cc.noharry.agent.appserver.a2a

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which stage the overlay shows outside client mode: the A2A view once an inbound task arrives, the device's own
 * session again once a run starts on the device. Published with the A2A client's state
 * ([cc.noharry.agent.appserver.protocol.A2aClientState.inboundFocus]), so an overlay that starts later opens on the right one.
 */
internal object InboundFocus {
    private val mutable = MutableStateFlow(false)
    val external: StateFlow<Boolean> = mutable

    fun inbound() {
        mutable.value = true
    }

    fun localRun() {
        mutable.value = false
    }
}
