package cc.noharry.agent.appserver.memory

import android.app.Application
import cc.noharry.agent.appserver.protocol.ExperimentalAppServerApi
import cc.noharry.agent.appserver.protocol.MemoryView
import cc.noharry.agent.appserver.protocol.ServerFeatures
import cc.noharry.agent.appserver.server.AppServerPlugin
import cc.noharry.agent.appserver.server.DataClearing
import cc.noharry.agent.appserver.server.PluginContext

/**
 * What the model remembers about the person across conversations, for a host that lists it among its plugins: the
 * `user_memory` tool (found like the host's tools; remembering, changing and forgetting never ask, in any approval mode:
 * they are the assistant's own notes, which the person reads and deletes on the memory page; its declared effect stays) and what is remembered
 * given to every round of a run, both while the person's memory switch is on; the memory page's requests
 * (`memory/setEnabled`, `memory/write`, `memory/delete`, `memory/clear`) and section; the book in
 * `files/user-memory/memories.json`, cleared when the runtime data is cleared with the memories (whether it runs or not). While it runs it
 * offers [ServerFeatures.MEMORY]. [page] is where the person reads and edits what is remembered, in the persona's
 * language, which the tool's words name; null names nowhere. Stopped, nothing is read, written or published.
 */
@ExperimentalAppServerApi
class MemoryPlugin(private val page: String? = null) : AppServerPlugin {
    override val id: String = "memory"

    override fun apply(ctx: PluginContext) {
        val app = ctx.app
        UserMemoryStore.start(app, page)
        ctx.onStop(UserMemoryStore::stop)
        ctx.offer(ServerFeatures.MEMORY)
        ctx.routes(memoryRoutes(app))
        // The owner's rule (2026-11-10): writing the assistant's memory never asks, "deny all" included.
        ctx.ownToolServer(USER_MEMORY_SERVER_ID) { _, _ -> false }
        ctx.runTools { facts -> if (userMemoryEnabled(facts.context)) listOf(UserMemoryToolProvider(facts.context)) else emptyList() }
        ctx.contextProviders { facts -> if (userMemoryEnabled(facts.context)) listOf(UserMemoryContext(facts.context)) else emptyList() }
        // Only a clearing of every conversation that asks for the memories too takes them; deleting one keeps them.
        ctx.onClearData { clearing -> !clearing.alsoClears(id) || UserMemoryStore.clear(app) }
        ctx.publish {
            follow(UserMemoryStore.memories) { copy(memory = memory.copy(memories = it)) }
            follow(UserMemoryStore.enabled) { copy(memory = memory.copy(enabled = it)) }
            // Turned off, the memories leave the screens with it.
            ctx.onStop { update { copy(memory = MemoryView()) } }
        }
    }

    /** Off, the book is deleted all the same when the runtime data is cleared with the memories. */
    override fun clearWhileOff(app: Application, clearing: DataClearing): Boolean =
        !clearing.alsoClears(id) || UserMemoryStore.deleteBook(app)
}
