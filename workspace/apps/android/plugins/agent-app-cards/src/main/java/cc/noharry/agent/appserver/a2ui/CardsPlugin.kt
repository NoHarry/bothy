package cc.noharry.agent.appserver.a2ui

import android.content.Context
import cc.noharry.agent.appserver.protocol.ExperimentalAppServerApi
import cc.noharry.agent.appserver.protocol.ServerFeatures
import cc.noharry.agent.appserver.server.AppServerPlugin
import cc.noharry.agent.appserver.server.PluginContext

/**
 * Cards and offers, for a host that lists it among its plugins: show_card and the offer tools in a run that has backend
 * tools for them to call, a picked option standing in for its checked call, and what the last reply showed published
 * to the clients. [hooks] are what the host does with a card ([CardHooks]).
 */
@ExperimentalAppServerApi
class CardsPlugin(private val hooks: (Context) -> CardHooks = { CardHooks.NONE }) : AppServerPlugin {
    override val id: String = "cards"

    override fun apply(ctx: PluginContext) {
        ctx.offer(ServerFeatures.CARDS)
        // Cards and options only show something: they never ask, are the main agent's, and are no service it used.
        ctx.ownToolServer(CARD_TOOL_SERVER, mainAgentOnly = true) { _, _ -> false }
        ctx.tools { context, mounted ->
            if (mounted.isEmpty()) emptyList() else listOf(OfferToolProvider(BindableTools(mounted), cards = hooks(context)))
        }
        // Outermost, so a picked option's tool is offered first.
        ctx.wrapTools { BoundCallHints(it) }
        ctx.prepareInput { sessionId, text, previous ->
            // A pick of an option registered with an offer tool carries its checked call instead of a bare ordinal.
            val pick = offerPick(text, previous, OfferStore.latest(sessionId))
            // Whatever the last reply showed (picks, slider, card) belongs to that turn only.
            OfferStore.endTurn(sessionId)
            if (pick != null) OfferStore.startPick(sessionId, pick)
            pick?.input
        }
        ctx.publish {
            follow(OfferStore.latestOffers) { copy(offers = it) }
            // Turned off, what its last reply showed leaves the screens with it.
            ctx.onStop { update { copy(offers = emptyMap()) } }
        }
    }
}

/** The tool server of the card and option tools, as their names read (`mcp__app_ui__show_card`). */
internal const val CARD_TOOL_SERVER = "app_ui"
