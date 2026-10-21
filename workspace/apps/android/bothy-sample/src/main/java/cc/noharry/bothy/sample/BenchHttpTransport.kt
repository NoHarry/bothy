package cc.noharry.bothy.sample

import android.content.Context
import cc.noharry.bothy.transport.HttpEvent
import cc.noharry.bothy.transport.HttpRequest
import cc.noharry.bothy.transport.HttpResponse
import cc.noharry.bothy.transport.HttpTransport
import java.io.IOException
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.util.concurrent.TimeUnit

/**
 * Bench HTTP transport that trusts an extra CA bundle bundled with the app.
 *
 * Some bench devices ship a trimmed system CA store that omits the roots backing the live
 * provider endpoint (for example DigiCert Global Root G2 / TrustAsia). When the system network
 * security config does not fully apply on such a ROM, OkHttp's default trust manager rejects the
 * TLS handshake. This transport builds an OkHttpClient whose TrustManager trusts both the system
 * roots and the [raw CA bundle][RAW_CA_RESOURCE], so the debug console can reach the live provider
 * on those benches without modifying the bothy-runtime transport. Chains are verified in full (PKIX).
 */
internal class BenchHttpTransport(
    context: Context,
    /** 0 lifts the limit on a whole call, for a long download; the read timeout still catches a stall. */
    private val callTimeoutSeconds: Long = CALL_TIMEOUT_SECONDS,
) : HttpTransport {
    private val client: OkHttpClient = buildClient(context)

    /** Cancelling the collector cancels the call, so a caller's timeout ends a stalled stream at once. */
    override fun stream(request: HttpRequest): Flow<HttpEvent> = flow {
        val call = client.newCall(request.toOkHttpRequest())
        coroutineScope {
            // Cancelled with this coroutine even while the read below blocks; cancelling the call ends that read.
            val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                call.execute().use { resp ->
                    emit(HttpEvent.Response(resp.code, resp.headers.toMultimap()))
                    val source = resp.body?.source()
                    if (source != null) {
                        val buffer = Buffer()
                        while (source.read(buffer, CHUNK_SIZE) != -1L) {
                            val bytes = buffer.readByteArray()
                            if (bytes.isNotEmpty()) {
                                emit(HttpEvent.Data(bytes))
                            }
                        }
                    }
                    emit(HttpEvent.Done)
                }
            } finally {
                canceller.cancel()
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Cancelling the caller cancels the call (a timeout around it takes effect at once). */
    override suspend fun execute(request: HttpRequest): HttpResponse = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request.toOkHttpRequest())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use { HttpResponse(it.code, it.headers.toMultimap(), it.body?.bytes() ?: ByteArray(0)) }
                }
                continuation.resumeWith(result)
            }
        })
    }

    private fun buildClient(context: Context): OkHttpClient =
        benchTrustedClient(context)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
            .build()

    private fun HttpRequest.toOkHttpRequest(): Request {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        val methodName = method.uppercase()
        val requestBody = when (methodName) {
            "GET", "HEAD" -> null
            else -> (body ?: ByteArray(0)).toRequestBody()
        }
        return builder.method(methodName, requestBody).build()
    }

    private companion object {
        const val CHUNK_SIZE = 8192L
        const val CONNECT_TIMEOUT_SECONDS = 30L
        const val WRITE_TIMEOUT_SECONDS = 60L
        const val READ_TIMEOUT_SECONDS = 180L
        const val CALL_TIMEOUT_SECONDS = 240L
    }
}

/**
 * An OkHttp builder trusting the system roots plus the bundled bench CA bundle; also what WebSockets connect with.
 * Builders share one client's connection pool and dispatcher, and the trust is built once per process.
 */
internal fun benchTrustedClient(context: Context): OkHttpClient.Builder = benchBaseClient(context).newBuilder()

@Volatile private var baseClient: OkHttpClient? = null

private fun benchBaseClient(context: Context): OkHttpClient = baseClient ?: synchronized(BenchHttpTransport::class) {
    baseClient ?: run {
        val trustManager = trustManagerWithExtraCas(context.applicationContext)
        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        OkHttpClient.Builder().sslSocketFactory(sslContext.socketFactory, trustManager).build().also { baseClient = it }
    }
}

/**
 * The platform's own path validation (PKIX), over a key store holding the system roots and the bundled bench CA
 * bundle. Some bench ROMs trim the system store and do not honour network_security_config for an in-app bundle, so
 * the roots are loaded here; every chain is still verified link by link up to one of them.
 */
private fun trustManagerWithExtraCas(context: Context): X509TrustManager {
    val roots = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
    system.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()?.acceptedIssuers.orEmpty()
        .forEachIndexed { index, root -> roots.setCertificateEntry("system-$index", root) }
    context.resources.openRawResource(R.raw.deepseek_cas).use { input: InputStream ->
        CertificateFactory.getInstance("X.509").generateCertificates(input).filterIsInstance<X509Certificate>()
            .forEachIndexed { index, root -> roots.setCertificateEntry("bench-$index", root) }
    }
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(roots) }
    return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
}
