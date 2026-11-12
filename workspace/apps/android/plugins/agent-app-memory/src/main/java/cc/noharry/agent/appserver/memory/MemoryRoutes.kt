package cc.noharry.agent.appserver.memory

import android.content.Context
import cc.noharry.agent.appserver.protocol.DoneResult
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.MemoryRequests
import cc.noharry.agent.appserver.protocol.MemorySource
import cc.noharry.agent.appserver.protocol.MemoryWriteResult
import cc.noharry.agent.appserver.server.RouteModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The memory page's requests, which the plugin answers while it runs: the switch, memories typed or changed on the page
 * (written as [MemorySource.TYPED] into the book the model's `user_memory` tool writes) and clearing them. The memories
 * themselves go out as state; `memory/lessons` (the runtime's own lessons) is the server's, there either way.
 */
fun memoryRoutes(app: Context): RouteModule = RouteModule {
    on(MemoryRequests.SET_ENABLED) {
        withContext(Dispatchers.IO) { setUserMemoryEnabled(app, it.on) }
        Empty
    }
    on(MemoryRequests.WRITE) { params ->
        val result = withContext(Dispatchers.IO) {
            UserMemoryStore.write(app) { book ->
                val id = params.id
                if (id == null) {
                    book.remember(params.category, params.content, MemorySource.TYPED)
                } else {
                    book.update(id, params.content, params.category, MemorySource.TYPED)
                }
            }
        }
        when (result) {
            is MemoryWrite.Saved -> MemoryWriteResult(saved = true, created = result.created)
            is MemoryWrite.Removed -> MemoryWriteResult(saved = true)
            is MemoryWrite.Refused -> MemoryWriteResult(saved = false, refusal = refusal(result))
        }
    }
    on(MemoryRequests.DELETE) { params ->
        withContext(Dispatchers.IO) { UserMemoryStore.write(app) { it.forget(params.id) } }
        Empty
    }
    on(MemoryRequests.CLEAR) { DoneResult(withContext(Dispatchers.IO) { UserMemoryStore.clear(app) }) }
}
