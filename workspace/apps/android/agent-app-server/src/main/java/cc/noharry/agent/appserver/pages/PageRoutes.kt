package cc.noharry.agent.appserver.pages

import android.content.Context
import cc.noharry.agent.appserver.protocol.PageReadResult
import cc.noharry.agent.appserver.protocol.PageRequests
import cc.noharry.agent.appserver.server.RouteModule
import cc.noharry.agent.appserver.server.RouteTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The pages the agent drew, read when a front end shows one. */
internal class PageRoutes(private val app: Context) : RouteModule {
    override fun RouteTable.install() {
        on(PageRequests.READ) { params -> withContext(Dispatchers.IO) { PageReadResult(readDrawnPage(app, params.reference)) } }
    }
}
