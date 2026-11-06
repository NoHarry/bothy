@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.a2ui
import android.content.Context
import android.graphics.BitmapFactory
import cc.noharry.agent.appserver.image.generatedImageFile
import cc.noharry.agent.appserver.model.DeepSeekWebSearch
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.deepSeekToolsOf
import cc.noharry.agent.appserver.protocol.view.SAVED_PICTURE
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request

/*
 * Pictures from the web for cards, fetched safely (public https only, a few bytes each): a page's own pictures, a
 * photo's size without loading it, a card picture's size, and a search for pages.
 */

/** A public https page's pictures, read from its first bytes: its cover (og:image, twitter:image), then its photos. */
suspend fun pagePictureUrls(url: String): List<String> = withContext(Dispatchers.IO) {
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
suspend fun photoSize(url: String): Pair<Int, Int>? = withContext(Dispatchers.IO) {
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

/** A card picture's size: a saved one's from its file, a public one's from the first bytes of the photo. */
suspend fun cardPictureSize(context: Context, reference: String): Pair<Int, Int>? = when {
    SAVED_PICTURE.matches(reference) -> withContext(Dispatchers.IO) {
        val file = generatedImageFile(context, reference) ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        (bounds.outWidth to bounds.outHeight).takeIf { it.first > 0 && it.second > 0 }
    }
    else -> photoSize(reference)
}

/**
 * A web search for pages, each query billed (DeepSeek's Search API, ¥5 per thousand): their addresses, the first
 * [results]. Null without a DeepSeek key on the device.
 */
fun webPageSearch(context: Context, results: Int): (suspend (String) -> List<String>)? =
    pageSearch(context)?.let { service -> { query -> service.search(query, results).map { it.url } } }

/** The active profile's key first: another DeepSeek profile may keep an old one (it answered 401 on the bench). */
private fun pageSearch(context: Context): DeepSeekWebSearch? {
    val config = ProviderConfig.load(context.applicationContext)
    return (listOf(config.activeProfile) + config.profiles).firstNotNullOfOrNull { config.deepSeekToolsOf(context, it) }
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
private const val PAGE_IMAGES = 6
/** A JPEG's size sits after its EXIF block, PNG's and WebP's at the start. */
private const val PHOTO_HEAD_BYTES = 128 * 1024
private const val MAX_PHOTO_BYTES = 3L * 1024 * 1024
