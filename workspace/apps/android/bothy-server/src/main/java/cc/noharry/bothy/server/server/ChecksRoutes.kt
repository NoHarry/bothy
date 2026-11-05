package cc.noharry.bothy.server.server

import android.content.Context
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.server.RouteModule
import cc.noharry.agent.appserver.server.RouteTable
import cc.noharry.bothy.protocol.CheckRequests
import cc.noharry.bothy.server.evidence.HealthCheckRuns

/** Settings → 检查: a round of checks, whose results come in the checks section as each ends. */
internal class ChecksRoutes(private val app: Context) : RouteModule {
    override fun RouteTable.install() {
        on(CheckRequests.RUN) { params ->
            HealthCheckRuns.run(app, params.ids)
            Empty
        }
    }
}
