package cc.noharry.bothy.server.cards

import android.content.Context
import android.util.Log
import cc.noharry.agent.appserver.a2a.a2aAgentConnected
import cc.noharry.agent.appserver.a2ui.CardHooks
import cc.noharry.agent.appserver.a2ui.cardPictureSize
import cc.noharry.agent.appserver.a2ui.pagePictureUrls
import cc.noharry.agent.appserver.a2ui.photoSize
import cc.noharry.agent.appserver.a2ui.webPageSearch
import cc.noharry.agent.appserver.protocol.view.CardLayout
import cc.noharry.agent.appserver.protocol.view.WidgetCard

/**
 * Bothy's cards in one run ([CardHooks]): a trip too tight for its travel goes back once ([TripTimingGate]), a trip gets
 * photos of its days and hotels ([withPlacePhotos]), every card with items leads with the picture the device picks
 * ([withBestHeader]), and the reply closes with a next step that fits the time, the user's cloud assistant too while
 * one is connected.
 */
internal class BothyCards(
    private val context: Context,
    private val cloudAssistant: Boolean = a2aAgentConnected(context),
) : CardHooks {
    private val tripTiming = TripTimingGate()

    override fun problem(runId: String, card: WidgetCard): String? = tripTiming.problem(runId, card)

    override suspend fun finish(card: WidgetCard): WidgetCard {
        val search = if (card.layout == CardLayout.TRIP) webPageSearch(context, PHOTO_SEARCH_RESULTS) else null
        val started = System.currentTimeMillis()
        val pictured = search?.let { pages ->
            card.withPlacePhotos { queries ->
                val query = queries.first()
                runCatching { findPlacePhoto(queries, pages, ::pagePictureUrls, ::photoSize) }
                    .onFailure { Log.w(PHOTO_TAG, "Trip photo for $query failed: ${it.javaClass.simpleName} ${it.message}") }
                    .onSuccess { Log.i(PHOTO_TAG, "Trip photo for $query: ${it ?: "none"}") }
                    .getOrNull()
            }
        } ?: card
        if (search != null) {
            val before = card.items.count { it.image != null }
            Log.i(PHOTO_TAG, "Trip photos: ${pictured.items.count { it.image != null } - before} found in ${System.currentTimeMillis() - started} ms")
        }
        return pictured.withBestHeader { cardPictureSize(context, it) }
    }

    // The reply's closing offer follows when the place is due (owner 2026-10-19: 周六的演出问「要不要算路过去」不符合
    // 现实). Here, right before finish, rather than in the voice skill, which has no room left in the host prompt.
    override fun closing(): String = REPLY_CLOSING + if (cloudAssistant) CLOUD_ASSISTANT_OFFER else ""
}

private const val REPLY_CLOSING = "结尾问下一步要合时宜：马上要去的才问导航；改天或几个小时以后的，问要不要到点提醒出发。"

/**
 * Owner 2026-10-19: 云端助手已经登录时，旅行规划要问要不要发给云端助手. The trip skill's own closing said so, but the
 * line after a card, right before finish, offered only reminders and won; with the assistant connected it says it too.
 */
private const val CLOUD_ASSISTANT_OFFER = "云端助手连着，同一个问题里也问要不要把这些发给云端助手（备份行程、订票、存进日历）。"
