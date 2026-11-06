package cc.noharry.bothy.server.cards

import cc.noharry.agent.appserver.protocol.view.CardItem
import cc.noharry.agent.appserver.protocol.view.CardItemKind
import cc.noharry.agent.appserver.protocol.view.CardLayout
import cc.noharry.agent.appserver.protocol.view.WidgetCard
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pictures for a trip card, found by the device (owner 2026-10-17: 这种最好能带上图片): the model never sees pictures,
 * and the 24 pages its subagents opened for one trip named none it could use. For each day without a photo (its first
 * place) and each hotel without one: [find] the place in the trip's city ([photoQueries]). Each gets [PHOTO_LOOKUP_MS];
 * what is not found stays without.
 */
internal suspend fun WidgetCard.withPlacePhotos(find: suspend (List<String>) -> String?): WidgetCard {
    if (layout != CardLayout.TRIP) return this
    val city = title.substringAfter('⇄', "").substringBefore('·').trim()
    val places = items.withIndex().filter { it.value.kind == CardItemKind.PLACE }
    // A day's photo is its first sight's, not the way from the airport.
    val days = places.groupBy { it.value.group }.values.filter { stops -> stops.all { it.value.image == null } }
        .mapNotNull { stops -> stops.firstOrNull { !it.value.isTripTransfer() } }
    val hotels = items.withIndex().filter { it.value.kind == CardItemKind.STAY && it.value.image == null }
    val wanted = (days + hotels).take(MAX_PHOTO_LOOKUPS)
    if (wanted.isEmpty()) return this
    val found = coroutineScope {
        wanted.map { (index, item) ->
            async { index to withTimeoutOrNull(PHOTO_LOOKUP_MS) { runCatching { find(item.photoQueries(city)) }.getOrNull() } }
        }.awaitAll()
    }.toMap()
    return copy(items = items.mapIndexed { index, item -> found[index]?.let { item.copy(image = it) } ?: item })
}

/**
 * 「成都大熊猫繁育研究基地（含花花）」 in 成都 → 成都大熊猫繁育研究基地, for a sight first as one (「… 景点」: a sight's own site
 * and the news show thumbnails, travel sites show it whole), then by its name alone.
 */
private fun CardItem.photoQueries(city: String): List<String> {
    // 「红山公园 / 大巴扎买伴手礼」 is two places; the first is looked up.
    val name = title.substringAfter('·').split('（', '(', '/', '／', '、', '+', '|').first().trim()
    val query = if (city.isEmpty() || city in name) name else "$name $city"
    return if (kind == CardItemKind.PLACE) listOf("$query $SIGHT_QUERY", query) else listOf(query)
}

/**
 * The first photo of [queries] the device can show, a query at a time (each search is billed, ¥5 per thousand): its
 * result pages in order, each page's own pictures ([pagePictures]: its cover, then the photos in its text), measured
 * together ([size]); the first that is a landscape photo at least [MIN_PHOTO_WIDTH_PX]×[MIN_PHOTO_HEIGHT_PX] wins.
 * Most result pages (news, official sites) name no cover, but their articles carry photos.
 */
internal suspend fun findPlacePhoto(
    queries: List<String>,
    search: suspend (String) -> List<String>,
    pagePictures: suspend (String) -> List<String>,
    size: suspend (String) -> Pair<Int, Int>?,
): String? = queries.firstNotNullOfOrNull { query ->
    val pages = runCatching { search(query) }.getOrDefault(emptyList()).take(PHOTO_PAGES)
    photoOfPages(pages, pagePictures, size)
}

private suspend fun photoOfPages(
    pages: List<String>,
    pagePictures: suspend (String) -> List<String>,
    size: suspend (String) -> Pair<Int, Int>?,
): String? {
    val candidates = coroutineScope { pages.map { page -> async { runCatching { pagePictures(page) }.getOrDefault(emptyList()) } }.awaitAll() }
        .flatten().distinct().take(MAX_PHOTO_CANDIDATES)
    val sizes = coroutineScope { candidates.map { picture -> async { runCatching { size(picture) }.getOrNull() } }.awaitAll() }
    return candidates.zip(sizes).firstOrNull { (_, measured) -> measured?.isLandscapePhoto() == true }?.first
}

private fun Pair<Int, Int>.isLandscapePhoto(): Boolean =
    first >= MIN_PHOTO_WIDTH_PX && second >= MIN_PHOTO_HEIGHT_PX && first.toFloat() / second in MIN_PHOTO_ASPECT..MAX_PHOTO_ASPECT

internal const val PHOTO_TAG = "DebugPlacePhotos"
private const val MAX_PHOTO_LOOKUPS = 6
private const val PHOTO_LOOKUP_MS = 8_000L
internal const val PHOTO_SEARCH_RESULTS = 5
private const val PHOTO_PAGES = 5
private const val MAX_PHOTO_CANDIDATES = 24
private const val SIGHT_QUERY = "景点"
private const val MIN_PHOTO_WIDTH_PX = 400
private const val MIN_PHOTO_HEIGHT_PX = 240
private const val MIN_PHOTO_ASPECT = 1.1f
private const val MAX_PHOTO_ASPECT = 2.4f
