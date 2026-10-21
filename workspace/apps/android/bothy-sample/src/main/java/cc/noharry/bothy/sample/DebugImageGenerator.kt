package cc.noharry.bothy.sample

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/** A generated image saved in app-private storage; [reference] is its path under `filesDir`. */
internal data class DebugGeneratedImage(
    val reference: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val elapsedMillis: Long,
    val revisedPrompt: String?,
)

internal sealed interface DebugImageResult {
    data class Success(val image: DebugGeneratedImage) : DebugImageResult
    data class Failure(val message: String, val elapsedMillis: Long) : DebugImageResult
}

/** What an images/generations response carried for its first image. */
internal sealed interface DebugImagePayload {
    class Inline(val bytes: ByteArray) : DebugImagePayload
    data class Remote(val url: String) : DebugImagePayload
}

/**
 * Calls an OpenAI-compatible `images/generations` endpoint and saves the first image under
 * `filesDir/generated-images/<session>/`, so deleting a session (or clearing runtime data) deletes its
 * pictures. Only the saved file's reference leaves this class: image bytes never enter tool results or
 * the model context.
 */
internal class DebugImageGenerator(private val context: Context) {
    suspend fun generate(
        config: DebugImageModelConfig,
        prompt: String,
        sessionId: String,
        keep: Int = MAX_KEPT_IMAGES,
    ): DebugImageResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        fun failure(message: String) = DebugImageResult.Failure(message, System.currentTimeMillis() - started)
        val key = loadDebugImageModelKey(context)
        when {
            key.isEmpty() -> return@withContext failure("No API key is stored for the image model.")
            config.validationError() != null -> return@withContext failure(config.validationError().orEmpty())
            prompt.isBlank() -> return@withContext failure("The prompt is empty.")
        }
        // One shared pool and dispatcher; only the timeouts differ per configuration.
        val client = SHARED_CLIENT.newBuilder().callTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS).build()
        try {
            val body = imageRequestBody(config, prompt).toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url("${config.baseUrl.trimEnd('/')}/images/generations")
                .header("Authorization", "Bearer $key").post(body).build()
            val reply = client.newCall(request).await(MAX_RESPONSE_BYTES)
            val raw = reply.body.toString(Charsets.UTF_8)
            // Some providers echo the presented key in auth errors.
            if (!reply.ok) return@withContext failure("HTTP ${reply.code}: ${raw.replace(key, "***").take(ERROR_BODY_CHARS)}")
            val response = Json.parseToJsonElement(raw).jsonObject
            val bytes = when (val payload = parseImagePayload(response)) {
                is DebugImagePayload.Inline -> payload.bytes
                // Signed result URLs carry their own authorisation; the API key is not sent to them.
                is DebugImagePayload.Remote -> {
                    val image = client.newCall(Request.Builder().url(payload.url).build()).await(MAX_IMAGE_BYTES)
                    if (!image.ok) return@withContext failure("Image download failed: HTTP ${image.code}")
                    image.body
                }
                null -> return@withContext failure("The response carried no image: ${raw.take(ERROR_BODY_CHARS)}")
            }
            val saved = saveGeneratedImage(sessionImagesDir(context, sessionId), bytes, keep)
            DebugImageResult.Success(saved.copy(revisedPrompt = revisedPrompt(response), elapsedMillis = System.currentTimeMillis() - started))
        } catch (error: IOException) {
            failure("Network error: ${error.message ?: error.javaClass.simpleName}")
        } catch (error: IllegalArgumentException) {
            failure("Unreadable response: ${error.message ?: error.javaClass.simpleName}")
        }
    }
}

private class HttpReply(val code: Int, val ok: Boolean, val body: ByteArray)

/**
 * Runs the call so a cancelled run (Stop) cancels the HTTP exchange at once instead of waiting out the timeout, and
 * reads at most [maxBytes] so an oversized reply fails instead of exhausting memory.
 */
private suspend fun Call.await(maxBytes: Long): HttpReply = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

        override fun onResponse(call: Call, response: Response) {
            val reply = try {
                response.use { HttpReply(it.code, it.isSuccessful, it.body?.let { body -> readCapped(body, maxBytes) } ?: ByteArray(0)) }
            } catch (error: IOException) {
                continuation.resumeWithException(error)
                return
            }
            continuation.resume(reply)
        }
    })
}

private fun readCapped(body: ResponseBody, maxBytes: Long): ByteArray {
    if (body.contentLength() > maxBytes) throw IOException("response larger than ${maxBytes / MEGABYTE} MB")
    val out = ByteArrayOutputStream()
    body.byteStream().use { input ->
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (out.size() + read > maxBytes) throw IOException("response larger than ${maxBytes / MEGABYTE} MB")
            out.write(buffer, 0, read)
        }
    }
    return out.toByteArray()
}

private fun saveGeneratedImage(directory: File, bytes: ByteArray, keep: Int): DebugGeneratedImage {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "the image could not be decoded" }
    directory.mkdirs()
    val file = File(directory, "${generatedImageName()}.${imageExtension(bytes)}")
    file.writeBytes(bytes)
    // Generated files are a cache for the screen, not a gallery: only a session's newest are kept.
    directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(keep)?.forEach { it.delete() }
    return DebugGeneratedImage(
        reference = "$GENERATED_IMAGES_DIR/${directory.name}/${file.name}",
        width = bounds.outWidth,
        height = bounds.outHeight,
        bytes = bytes.size.toLong(),
        elapsedMillis = 0L,
        revisedPrompt = null,
    )
}

/** One folder per session (base64url of its ID, as the HTML artifacts use), so a session's pictures go with it. */
internal fun sessionImagesDir(context: Context, sessionId: String): File =
    File(File(context.filesDir, GENERATED_IMAGES_DIR), encodeSessionId(sessionId.ifBlank { UNKNOWN_SESSION }))

/** Deletes the pictures of one session; true when nothing of it is left. */
internal fun deleteSessionGeneratedImages(context: Context, sessionId: String): Boolean {
    val directory = sessionImagesDir(context, sessionId)
    return !directory.exists() || runCatching { directory.deleteRecursively() }.getOrDefault(false)
}

/**
 * `img-` plus the time in base 36 and four random letters. Trace redaction treats a run of ten or more digits,
 * spaces and dashes as a phone number, so a dated name ("20261009-172728") would reach the transcript, and the
 * overlay, as `[REDACTED_PHONE]`.
 */
internal fun generatedImageName(nowMillis: Long = System.currentTimeMillis(), random: Random = Random.Default): String =
    "img-${nowMillis.toString(NAME_RADIX)}-" + (1..NAME_SUFFIX_LETTERS).map { NAME_LETTERS[random.nextInt(NAME_LETTERS.length)] }.joinToString("")

internal fun imageRequestBody(config: DebugImageModelConfig, prompt: String): JsonObject = buildJsonObject {
    put("model", config.model)
    put("prompt", prompt)
    put("n", 1)
    if (config.size.isNotBlank()) put("size", config.size)
}

/** The first image of an images/generations response: inline base64 wins over a URL. */
internal fun parseImagePayload(response: JsonObject): DebugImagePayload? {
    val first = response["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
    first["b64_json"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { encoded ->
        return DebugImagePayload.Inline(Base64.decode(encoded.substringAfter("base64,"), Base64.DEFAULT))
    }
    return first["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        ?.let(DebugImagePayload::Remote)
}

private fun revisedPrompt(response: JsonObject): String? =
    response["data"]?.jsonArray?.firstOrNull()?.jsonObject?.get("revised_prompt")?.jsonPrimitive?.contentOrNull

internal fun imageExtension(bytes: ByteArray): String = when {
    bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
    bytes.size > 11 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
    else -> "jpg"
}

/** The file behind a reference returned by [DebugImageGenerator]; null for anything outside a session folder. */
internal fun generatedImageFile(context: Context, reference: String): File? {
    val root = File(context.filesDir, GENERATED_IMAGES_DIR).canonicalFile
    val file = File(context.filesDir, reference).canonicalFile
    return file.takeIf { it.parentFile?.parentFile == root && it.isFile }
}

internal const val GENERATED_IMAGES_DIR = "generated-images"
/** Folder for generations started outside a session (settings test, adb); cleared with runtime data. */
internal const val IMAGE_TEST_SESSION = "image-model-test"
private const val UNKNOWN_SESSION = "unknown-session"
private const val MAX_KEPT_IMAGES = 30
private const val MEGABYTE = 1_048_576L
/** A 4K PNG as base64 is about 16 MB; JSON beyond this is not an image reply. */
private const val MAX_RESPONSE_BYTES = 32 * MEGABYTE
private const val MAX_IMAGE_BYTES = 24 * MEGABYTE
private const val BUFFER_BYTES = 64 * 1024
private val SHARED_CLIENT = OkHttpClient()
private const val NAME_RADIX = 36
private const val NAME_SUFFIX_LETTERS = 4
private const val NAME_LETTERS = "abcdefghjkmnpqrstuvwxyz"
private const val ERROR_BODY_CHARS = 300
private val JSON_MEDIA_TYPE = "application/json".toMediaType()
