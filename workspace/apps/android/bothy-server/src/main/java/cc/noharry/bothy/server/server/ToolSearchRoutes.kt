package cc.noharry.bothy.server.server

import android.content.Context
import cc.noharry.agent.appserver.protocol.SaveResult
import cc.noharry.agent.appserver.server.PreferencesSource
import cc.noharry.agent.appserver.server.RouteModule
import cc.noharry.agent.appserver.server.RouteTable
import cc.noharry.bothy.protocol.ToolSearchRequests
import cc.noharry.bothy.server.voice.config
import cc.noharry.bothy.server.voice.loadJevConfig
import cc.noharry.bothy.server.voice.saveJevToolSearch

/** Settings → 工具与技能 → 工具检索: Jev's tool search, saved and then shown again as the person set it. */
internal class ToolSearchRoutes(private val app: Context) : RouteModule {
    override fun RouteTable.install() {
        on(ToolSearchRequests.SET) { settings ->
            SaveResult(saveJevToolSearch(app, settings.config(loadJevConfig(app))).also { PreferencesSource.changed() })
        }
    }
}
