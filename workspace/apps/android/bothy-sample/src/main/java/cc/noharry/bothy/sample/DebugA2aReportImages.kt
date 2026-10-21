package cc.noharry.bothy.sample

import android.content.Context
import cc.noharry.bothy.a2a.ExperimentalA2aClientApi
import cc.noharry.bothy.a2a.RemoteAgentRef
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** A picture a delegation report attached, saved where the overlay and console find generated pictures. */
internal data class DebugA2aReportPicture(val reference: String, val caption: String)

/**
 * The relay URL of a report picture: only a `/files/<task>/<n>.<ext>` path on the profile endpoint's own origin, so
 * a report can never make the device fetch anything else.
 */
internal fun reportPictureUrl(endpoint: URI, path: String): HttpUrl? {
    if (!REPORT_FILE_PATH.matches(path)) return null
    val origin = endpoint.toString().toHttpUrlOrNull() ?: return null
    return origin.newBuilder().encodedPath(path).query(null).fragment(null).build()
}

/** The generated-images folder for one delegated task. */
internal fun reportPictureFolder(taskId: String): String =
    REPORT_FOLDER_PREFIX + taskId.filter { it.isLetterOrDigit() }.take(REPORT_FOLDER_ID_CHARS)

/**
 * Downloads a report's pictures with the profile's access token into `generated-images/delegation-<task>/`; a picture
 * that fails is skipped. Keeps the newest [MAX_REPORT_FOLDERS] delegation folders.
 */
@OptIn(ExperimentalA2aClientApi::class)
internal suspend fun fetchReportPictures(
    context: Context,
    remote: RemoteAgentRef,
    taskId: String,
    pictures: List<Pair<String, String>>,
    client: OkHttpClient = REPORT_PICTURE_CLIENT,
): List<DebugA2aReportPicture> = withContext(Dispatchers.IO) {
    if (pictures.isEmpty()) return@withContext emptyList()
    val token = DebugA2aTokens.read(context, remote.id)
    val folder = reportPictureFolder(taskId)
    val dir = File(context.filesDir, "$GENERATED_IMAGES_DIR/$folder")
    val saved = pictures.take(MAX_REPORT_PICTURES).mapIndexedNotNull { index, (path, caption) ->
        val url = reportPictureUrl(remote.endpoint, path) ?: return@mapIndexedNotNull null
        val bytes = download(client, url, token) ?: return@mapIndexedNotNull null
        dir.mkdirs()
        val file = File(dir, "${index + 1}.${imageExtension(bytes)}")
        runCatching { file.writeBytes(bytes) }.getOrNull() ?: return@mapIndexedNotNull null
        DebugA2aReportPicture("$GENERATED_IMAGES_DIR/$folder/${file.name}", caption)
    }
    pruneReportFolders(File(context.filesDir, GENERATED_IMAGES_DIR))
    saved
}

private fun download(client: OkHttpClient, url: HttpUrl, token: String): ByteArray? {
    val request = Request.Builder().url(url).apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }.build()
    return try {
        client.newCall(request).execute().use { response ->
            val body = response.body ?: return null
            val type = body.contentType()
            if (!response.isSuccessful || type?.type != "image" || body.contentLength() > MAX_REPORT_PICTURE_BYTES) return null
            body.byteStream().use { stream ->
                val bytes = stream.readNBytesCompat(MAX_REPORT_PICTURE_BYTES + 1)
                bytes.takeIf { it.isNotEmpty() && it.size <= MAX_REPORT_PICTURE_BYTES }
            }
        }
    } catch (_: IOException) {
        null
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_BYTES)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private fun pruneReportFolders(root: File) {
    root.listFiles { file -> file.isDirectory && file.name.startsWith(REPORT_FOLDER_PREFIX) }
        ?.sortedByDescending { it.lastModified() }
        ?.drop(MAX_REPORT_FOLDERS)
        ?.forEach { it.deleteRecursively() }
}

private val REPORT_FILE_PATH = Regex("/files/[0-9a-f-]{36}/[1-9]\\.(?:jpg|png|webp)")
private val REPORT_PICTURE_CLIENT = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .followRedirects(false)
    .build()
private const val REPORT_FOLDER_PREFIX = "delegation-"
private const val REPORT_FOLDER_ID_CHARS = 16
private const val MAX_REPORT_PICTURES = 3
private const val MAX_REPORT_PICTURE_BYTES = 2 * 1024 * 1024
private const val MAX_REPORT_FOLDERS = 10
private const val BUFFER_BYTES = 16 * 1024
