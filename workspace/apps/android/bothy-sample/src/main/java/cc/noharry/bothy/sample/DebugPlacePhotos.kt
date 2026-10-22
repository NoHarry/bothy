@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Pictures for a trip card, found by the device (owner 2026-10-17: 这种最好能带上图片): the model never sees pictures,
 * and the 24 pages its subagents opened for one trip named none it could use. For each day without a photo (its first
 * place) and each hotel without one: [find] the place in the trip's city ([photoQueries]). Each gets [PHOTO_LOOKUP_MS];
 * what is not found stays without.
 */
internal suspend fun DebugCard.withPlacePhotos(find: suspend (List<String>) -> String?): DebugCard {
    if (layout != DebugCardLayout.TRIP) return this
    val city = title.substringAfter('⇄', "").substringBefore('·').trim()
    val places = items.withIndex().filter { it.value.kind == DebugCardItemKind.PLACE }
    // A day's photo is its first sight's, not the way from the airport.
    val days = places.groupBy { it.value.group }.values.filter { stops -> stops.all { it.value.image == null } }
        .mapNotNull { stops -> stops.firstOrNull { !it.value.isTripTransfer() } }
    val hotels = items.withIndex().filter { it.value.kind == DebugCardItemKind.STAY && it.value.image == null }
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
private fun DebugCardItem.photoQueries(city: String): List<String> {
    // 「红山公园 / 大巴扎买伴手礼」 is two places; the first is looked up.
    val name = title.substringAfter('·').split('（', '(', '/', '／', '、', '+', '|').first().trim()
    val query = if (city.isEmpty() || city in name) name else "$name $city"
    return if (kind == DebugCardItemKind.PLACE) listOf("$query $SIGHT_QUERY", query) else listOf(query)
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

/** A public https page's pictures, read from its first bytes: its cover (og:image, twitter:image), then its photos. */
internal suspend fun debugPagePictures(url: String): List<String> = withContext(Dispatchers.IO) {
    val page = remotePictureUrl(url) ?: return@withContext emptyList()
    val request = Request.Builder().url(page).header("User-Agent", PAGE_AGENT).header("Accept", "text/html").build()
    PAGE_CLIENT.newCall(request).execute().use { response ->
        val body = response.body ?: return@use emptyList()
        if (!response.isSuccessful || body.contentType()?.subtype?.contains("html") == false) return@use emptyList()
        pagePictures(body.byteStream().readAtMost(MAX_PAGE_BYTES).toString(Charsets.UTF_8), page)
    }
}

/**
 * The pictures [html] shows, as https URLs: its cover first, then the first [PAGE_IMAGES] images of its text (lazy
 * ones by data-original or data-src), without logos, icons, QR codes and share buttons.
 */
internal fun pagePictures(html: String, page: HttpUrl): List<String> {
    val metas = META_TAG.findAll(html).map { it.value }.toList()
    val covers = LEAD_PICTURE_KEYS.flatMap { key ->
        metas.filter { tag -> listOf("property", "name").any { tag.attribute(it).equals(key, ignoreCase = true) } }.mapNotNull { it.attribute("content") }
    }
    val images = IMG_TAG.findAll(html).mapNotNull { tag -> IMG_SOURCES.firstNotNullOfOrNull { tag.value.attribute(it) } }.take(PAGE_IMAGES * 2).toList()
    return (covers + images)
        .map { it.replace("&amp;", "&").trim() }
        .filter { it.isNotEmpty() && !NOT_A_PHOTO.containsMatchIn(it) }
        .mapNotNull { source -> page.resolve(source)?.takeIf { it.isHttps }?.toString() }
        .distinct()
        .take(PAGE_IMAGES + covers.size)
}

/**
 * A remote photo's pixel size from its first bytes, without loading it (a sight's site may keep a 29 MB original):
 * null when it is no picture, larger than [MAX_PHOTO_BYTES] or not public https.
 */
internal suspend fun debugPhotoSize(url: String): Pair<Int, Int>? = withContext(Dispatchers.IO) {
    val photo = remotePictureUrl(url) ?: return@withContext null
    val request = Request.Builder().url(photo).header("User-Agent", PAGE_AGENT).header("Accept", "image/*").build()
    PAGE_CLIENT.newCall(request).execute().use { response ->
        val body = response.body ?: return@use null
        if (!response.isSuccessful || body.contentType()?.type != "image" || body.contentLength() > MAX_PHOTO_BYTES) return@use null
        val head = body.byteStream().readAtMost(PHOTO_HEAD_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(head, 0, head.size, bounds)
        (bounds.outWidth to bounds.outHeight).takeIf { (width, height) -> width > 0 && height > 0 }
    }
}

/** A DeepSeek key on the device makes the photo search; without one trip cards keep only the pictures they were given. */
internal fun debugPhotoSearch(context: Context): DeepSeekWebSearch? {
    val config = DebugProviderConfig.load(context.applicationContext)
    // The active profile's key first: another DeepSeek profile may keep an old one (it answered 401 on the bench).
    return (listOf(config.activeProfile) + config.profiles).firstNotNullOfOrNull { config.deepSeekToolsOf(context, it) }
}

/** What show_card does to a card before it shows: a trip's photos, then the header the device picks. */
internal fun debugCardFinisher(context: Context): suspend (DebugCard) -> DebugCard = { card ->
    val size: suspend (String) -> Pair<Int, Int>? = { debugPictureSize(context, it) }
    val search = if (card.layout == DebugCardLayout.TRIP) debugPhotoSearch(context) else null
    val started = System.currentTimeMillis()
    val pictured = search?.let { service ->
        card.withPlacePhotos { queries ->
            val query = queries.first()
            runCatching { findPlacePhoto(queries, { q -> service.search(q, PHOTO_SEARCH_RESULTS).map { it.url } }, ::debugPagePictures, ::debugPhotoSize) }
                .onFailure { Log.w(TAG, "Trip photo for $query failed: ${it.javaClass.simpleName} ${it.message}") }
                .onSuccess { Log.i(TAG, "Trip photo for $query: ${it ?: "none"}") }
                .getOrNull()
        }
    } ?: card
    if (search != null) {
        val before = card.items.count { it.image != null }
        Log.i(TAG, "Trip photos: ${pictured.items.count { it.image != null } - before} found in ${System.currentTimeMillis() - started} ms")
    }
    pictured.withBestHeader(size)
}

private fun String.attribute(name: String): String? =
    Regex("""(?i)\b$name\s*=\s*["']([^"']*)["']""").find(this)?.groupValues?.get(1)

private fun java.io.InputStream.readAtMost(limit: Int): ByteArray = use { input ->
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_BYTES)
    while (out.size() < limit) {
        val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    out.toByteArray()
}

/** Every hop stays https, as for the pictures themselves. */
private val PAGE_GUARD = Interceptor { chain ->
    if (!chain.request().url.isHttps) throw IOException("page redirected off https")
    chain.proceed(chain.request())
}

private val PAGE_CLIENT = OkHttpClient.Builder()
    .dns(PublicOnlyDns)
    .followSslRedirects(false)
    .addNetworkInterceptor(PAGE_GUARD)
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(3, TimeUnit.SECONDS)
    .callTimeout(4, TimeUnit.SECONDS)
    .build()

private const val TAG = "DebugPlacePhotos"
private val META_TAG = Regex("""(?i)<meta\b[^>]*>""")
private val LEAD_PICTURE_KEYS = listOf("og:image:secure_url", "og:image", "twitter:image", "twitter:image:src")
private val IMG_TAG = Regex("""(?i)<img\b[^>]*>""")
private val IMG_SOURCES = listOf("data-original", "data-src", "src")
/** Page furniture, the sales banners a travel site puts beside a hotel, and pictures it stamps with its mark. */
private val NOT_A_PHOTO = Regex("""(?i)logo|icon|avatar|qrcode|qr_|ewm|erweima|weixin|wechat|share|btn|loading|blank|placeholder|banner|sales|promo|advert|/ads?/|watermark|_water_|\.gif|\.svg|^data:""")
private const val PAGE_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
/** Pages declare their picture in the head. */
private const val MAX_PAGE_BYTES = 128 * 1024
private const val BUFFER_BYTES = 8 * 1024
private const val MAX_PHOTO_LOOKUPS = 6
private const val PHOTO_LOOKUP_MS = 8_000L
private const val PHOTO_SEARCH_RESULTS = 5
private const val PHOTO_PAGES = 5
private const val PAGE_IMAGES = 6
private const val MAX_PHOTO_CANDIDATES = 24
private const val SIGHT_QUERY = "景点"
/** A JPEG's size sits after its EXIF block, PNG's and WebP's at the start. */
private const val PHOTO_HEAD_BYTES = 128 * 1024
private const val MAX_PHOTO_BYTES = 3L * 1024 * 1024
private const val MIN_PHOTO_WIDTH_PX = 400
private const val MIN_PHOTO_HEIGHT_PX = 240
private const val MIN_PHOTO_ASPECT = 1.1f
private const val MAX_PHOTO_ASPECT = 2.4f
