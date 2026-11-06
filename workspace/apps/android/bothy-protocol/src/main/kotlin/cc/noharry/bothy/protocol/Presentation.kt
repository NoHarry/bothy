package cc.noharry.bothy.protocol

import cc.noharry.agent.appserver.protocol.TakenResult
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.Serializable

/** Debug: one of the mascot's motions by name. */
@Serializable
data class MascotParams(val name: String)

/** Requests Bothy's server sends the client presenting the assistant, beside the protocol's own. */
object BothyPresentationRequests {
    /** Debug: plays a motion of the mascot on screen; not taken when none is up or the name is unknown. */
    val PREVIEW_MASCOT = RequestMethod("presentation/previewMascot", MascotParams.serializer(), TakenResult.serializer())
}
