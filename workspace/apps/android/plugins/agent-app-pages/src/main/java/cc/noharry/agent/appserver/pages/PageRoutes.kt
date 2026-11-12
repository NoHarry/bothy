package cc.noharry.agent.appserver.pages

import android.content.Context
import cc.noharry.agent.appserver.protocol.PageReadResult
import cc.noharry.agent.appserver.protocol.PageRequests
import cc.noharry.agent.appserver.server.RouteModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The pages the agent drew, read when a front end shows one (`pages/read`), which the plugin answers while it runs. */
fun pageRoutes(app: Context): RouteModule = RouteModule {
    on(PageRequests.READ) { params -> withContext(Dispatchers.IO) { PageReadResult(readDrawnPage(app, params.reference)) } }
}
