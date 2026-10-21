package cc.noharry.bothy.sample

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import java.io.IOException
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

/**
 * Every picture the Sample draws loads through Glide, which caches it and decodes it at the slot's size: a picture
 * saved on the device (`generated-images/<folder>/<file>`: generated, or delegated and downloaded) or a public https URL
 * (a card's photos). Remote fetches are https only, to public addresses only, image content only and size-capped, so
 * a card can name a photo on the web but never make the device call its own services or the local network.
 */
@Composable
internal fun rememberPicture(reference: String, maxDimension: Int): State<ImageBitmap?> {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(null, reference, maxDimension) {
        val model = pictureModel(context, reference)
        if (model == null) {
            Log.w(TAG, "Picture $reference is neither saved on the device nor a public https URL")
            return@produceState
        }
        value = loadPicture(context, model, maxDimension)
        if (value == null) Log.w(TAG, "Picture $reference could not be shown")
    }
}

/** What Glide loads for [reference]: the saved file, the checked URL, or null for anything else. */
internal fun pictureModel(context: Context, reference: String): Any? = when {
    SAVED_PICTURE.matches(reference) -> generatedImageFile(context, reference)
    remotePictureUrl(reference) != null -> GlideUrl(reference)
    else -> null
}

/**
 * A picture URL a card may name: https, no credentials, at most [MAX_PICTURE_URL_CHARS], and not a local name or a
 * non-public address. A host name is checked again when it resolves ([PublicOnlyDns]).
 */
internal fun remotePictureUrl(reference: String): HttpUrl? {
    if (reference.length > MAX_PICTURE_URL_CHARS) return null
    val url = reference.toHttpUrlOrNull() ?: return null
    if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    val host = url.host.lowercase()
    if (host == "localhost" || LOCAL_SUFFIXES.any { host.endsWith(it) }) return null
    if (ADDRESS_LITERAL.matches(host) && !InetAddress.getByName(host).isPublic()) return null
    return url
}

/** The bitmap at most [maxDimension] on its longer side, or null when it cannot be had. */
internal suspend fun loadPicture(context: Context, model: Any, maxDimension: Int): ImageBitmap? {
    val target = glide(context).asBitmap().load(model)
        .downsample(DownsampleStrategy.CENTER_INSIDE)
        .override(maxDimension)
        .submit()
    return try {
        runInterruptible(Dispatchers.IO) { target.get() }.asImageBitmap()
    } catch (cancelled: CancellationException) {
        target.cancel(true)
        throw cancelled
    } catch (_: Exception) {
        // The target is not cleared: a cleared bitmap goes back to Glide's pool while the screen may still draw it.
        null
    }
}

/** Glide with remote pictures fetched by [PICTURE_CLIENT]; registered once, on first use. */
private fun glide(context: Context): RequestManager {
    val app = context.applicationContext
    if (glideReady.compareAndSet(false, true)) {
        Glide.get(app).registry.replace(GlideUrl::class.java, InputStream::class.java, OkHttpUrlLoader.Factory(PICTURE_CLIENT))
    }
    return Glide.with(app)
}

/** Resolves only to public addresses: a name that points at the device, the LAN or a carrier NAT is refused. */
internal object PublicOnlyDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        Dns.SYSTEM.lookup(hostname).filter { it.isPublic() }.ifEmpty { throw UnknownHostException("$hostname has no public address") }
}

internal fun InetAddress.isPublic(): Boolean {
    if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) return false
    val bytes = address
    // Carrier-grade NAT 100.64.0.0/10 and IPv6 unique local fc00::/7 are private too.
    if (bytes.size == 4 && bytes[0].toInt() == 100 && (bytes[1].toInt() and 0xC0) == 64) return false
    if (this is Inet6Address && (bytes[0].toInt() and 0xFE) == 0xFC) return false
    return true
}

/** Every hop (redirects included) must stay https and answer with an image no larger than [MAX_PICTURE_BYTES]. */
private val PICTURE_GUARD = Interceptor { chain ->
    if (!chain.request().url.isHttps) throw IOException("picture redirected off https")
    val response = chain.proceed(chain.request())
    val body = response.body
    val type = body?.contentType()
    if (response.isSuccessful && (type?.type != "image" || (body.contentLength()) > MAX_PICTURE_BYTES)) {
        response.close()
        throw IOException("not a picture of at most $MAX_PICTURE_BYTES bytes: $type")
    }
    // A chunked answer declares no length: the cap holds while it is read.
    if (response.isSuccessful && body != null) response.newBuilder().body(body.limitedTo(MAX_PICTURE_BYTES)).build() else response
}

/** [this] body, failing once more than [maxBytes] have been read. */
internal fun ResponseBody.limitedTo(maxBytes: Long): ResponseBody {
    val counted = object : ForwardingSource(source()) {
        private var read = 0L

        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = super.read(sink, byteCount)
            if (n > 0) {
                read += n
                if (read > maxBytes) throw IOException("picture larger than $maxBytes bytes")
            }
            return n
        }
    }
    return counted.buffer().asResponseBody(contentType(), contentLength())
}

private val PICTURE_CLIENT = OkHttpClient.Builder()
    .dns(PublicOnlyDns)
    .followSslRedirects(false)
    .addNetworkInterceptor(PICTURE_GUARD)
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .build()

private val glideReady = AtomicBoolean(false)
private val ADDRESS_LITERAL = Regex("""[0-9.]+|.*:.*""")
private val LOCAL_SUFFIXES = listOf(".local", ".localhost", ".lan", ".internal", ".home.arpa")
internal const val MAX_PICTURE_URL_CHARS = 2048
private const val MAX_PICTURE_BYTES = 8L * 1024 * 1024
private const val TAG = "DebugPicture"
