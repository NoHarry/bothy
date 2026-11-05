package cc.noharry.bothy.protocol

import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.FlagParams
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod

/** The Bothy Playground app: the virtual head unit, home and map the demo drives. */
const val PLAYGROUND_PACKAGE = "cc.noharry.bothy.playground"

/** Requests about the Playground. */
object PlaygroundRequests {
    /** The Playground drives a real car's navigation ([FlagParams.on]) or the simulated one. */
    val SET_REAL = RequestMethod("playground/setReal", FlagParams.serializer(), Empty.serializer())
}
