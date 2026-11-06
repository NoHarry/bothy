package cc.noharry.agent.appserver.a2ui

import cc.noharry.agent.appserver.protocol.view.WidgetCard

/**
 * What a host does with show_card's cards in one run ([cc.noharry.agent.appserver.runtime.RunAddOns.cards]): a check
 * that can turn a card back, what a card gets before it shows (pictures, a header), and what the model is told after a
 * card shows. [NONE] shows cards as the model drew them.
 */
interface CardHooks {
    /** Why [card] cannot show in the run [runId], in words the model can fix it by; null shows it. */
    fun problem(runId: String, card: WidgetCard): String? = null

    /** [card] as it shows. */
    suspend fun finish(card: WidgetCard): WidgetCard = card

    /** Said after a card showed, after the server's "give the main point": how the reply closes; "" for nothing. */
    fun closing(): String = ""

    companion object {
        val NONE: CardHooks = object : CardHooks {}
    }
}
