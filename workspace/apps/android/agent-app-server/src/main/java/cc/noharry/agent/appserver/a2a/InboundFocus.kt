package cc.noharry.agent.appserver.a2a

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether another client's task arrived after the device's own last run: a screen that shows one thing at a time shows
 * the A2A tasks then, outside client mode too, and the device's own session again once a run starts on the device.
 * Published with the A2A client's state ([cc.noharry.agent.appserver.protocol.A2aClientState.inboundFocus]), so a
 * screen that opens later shows the right one.
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
