package cc.noharry.bothy.sample

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The picture a card with items leads with, chosen by the device (owner 2026-10-17: 头图看着不好). The model names
 * pictures by URL and never sees them, so it led with a news site's square 「精彩活动」 banner while two event photos
 * sat in the rows. Of the header it named and its items' pictures, the largest wide one leads (at least
 * [HEADER_MIN_WIDTH_PX] wide and [HEADER_MIN_ASPECT] times as wide as tall); with none, the card has no header and the
 * pictures stay with their rows. A card that is only its photo keeps it. [measure] gives width and height, or null.
 */
internal suspend fun DebugCard.withBestHeader(measure: suspend (String) -> Pair<Int, Int>?): DebugCard {
    if (items.isEmpty()) return this
    // A trip's photos lead its days; the same picture again as the header would only repeat one.
    if (layout == DebugCardLayout.TRIP) return copy(image = null)
    val candidates = (listOfNotNull(image) + items.mapNotNull { it.image }).distinct().take(MAX_HEADER_CANDIDATES)
    if (candidates.isEmpty()) return this
    // Each picture gets [HEADER_PROBE_MS]; one that does not load in time is not a header.
    val sizes = coroutineScope { candidates.map { url -> async { url to withTimeoutOrNull(HEADER_PROBE_MS) { measure(url) } } }.awaitAll() }
    val best = sizes.mapNotNull { (url, size) -> size?.let { url to it } }
        .filter { (_, size) -> size.first >= HEADER_MIN_WIDTH_PX && size.first >= size.second * HEADER_MIN_ASPECT }
        .maxByOrNull { (_, size) -> size.first.toLong() * size.second }
    return copy(image = best?.first)
}

/** A picture's size as the device decodes it (bounded by [HEADER_PROBE_DECODE_PX]), through the card's own loader. */
internal suspend fun debugPictureSize(context: Context, reference: String): Pair<Int, Int>? {
    val model = pictureModel(context, reference) ?: return null
    return loadPicture(context, model, HEADER_PROBE_DECODE_PX)?.let { it.width to it.height }
}

private const val MAX_HEADER_CANDIDATES = 6
private const val HEADER_PROBE_MS = 4_000L
private const val HEADER_PROBE_DECODE_PX = 1_600
private const val HEADER_MIN_WIDTH_PX = 480
private const val HEADER_MIN_ASPECT = 1.25f
