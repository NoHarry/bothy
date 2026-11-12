package cc.noharry.agent.appserver.pages

import cc.noharry.agent.appserver.protocol.ExperimentalAppServerApi
import cc.noharry.agent.appserver.protocol.ServerFeatures
import cc.noharry.agent.appserver.runtime.RunFacts
import cc.noharry.agent.appserver.runtime.modelText
import android.app.Application
import cc.noharry.agent.appserver.server.AppServerPlugin
import cc.noharry.agent.appserver.server.DataClearing
import cc.noharry.agent.appserver.server.PluginContext

/**
 * Pages the model draws in the conversation (`show_html`), for a host that lists it among its plugins and whose screens
 * say when they are in front (`app/inFront`). Every main run with a service's model has the tool (a session that took it
 * up still finds it), but only a run that started while the app's screens were in front may draw: such a run reads
 * [whenToDraw], the host's words on when a page beats words, and any other is told to answer in words. The host's own
 * models (on-device ones) write no page that works, and a subagent shows nothing, so neither gets it. The pages are kept
 * under the conversation's folder (`files/drawn-pages/`), read back with `pages/read`, and deleted with the conversation
 * or with the runtime data, whether the plugin runs or not. Drawing never asks: it only shows something.
 */
@ExperimentalAppServerApi
class PagesPlugin(private val whenToDraw: List<String> = emptyList()) : AppServerPlugin {
    override val id: String = "pages"

    override fun apply(ctx: PluginContext) {
        val app = ctx.app
        ctx.routes(pageRoutes(app))
        ctx.ownToolServer(PAGE_TOOL_SERVER_ID) { _, _ -> false }
        ctx.runTools { facts ->
            if (!offered(facts)) emptyList() else listOf(PageToolProvider(facts.context, facts.appInFront == true) { ctx.offers(ServerFeatures.CARDS) })
        }
        ctx.contextLines { facts ->
            when {
                !offered(facts) -> emptyList()
                facts.appInFront == true -> whenToDraw
                else -> listOf(awayRule())
            }
        }
        ctx.onClearData { clearing -> deleteDrawnPages(app, clearing.sessionId) }
    }

    /** Off, the pages it drew go with their conversation, or all of them with the runtime data, all the same. */
    override fun clearWhileOff(app: Application, clearing: DataClearing): Boolean = deleteDrawnPages(app, clearing.sessionId)
}

/** Whether a run has the page tool: a main run with a service's model, in an app whose screens say when they are in front. */
private fun offered(facts: RunFacts): Boolean = !facts.child && !facts.hostModel && facts.appInFront != null

/**
 * While the conversation is not on screen, the run is told not to draw (a session may have taken the tool up in the
 * app, and a page nobody sees is refused), in words that do not name the tool.
 */
private fun awayRule(): String = modelText(
    "用户现在没在看对话页（App 不在前台）：这次不画界面，用文字回答。",
    "The user is not looking at the conversation now (the app is not in front): draw no page this time; answer in words.",
)
