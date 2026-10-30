package cc.noharry.bothy.llm

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Downloads a [LocalModelSpec] into [dir]: the URLs in order, resuming `<file>.part` with a Range request, and keeps the
 * file only when its size and SHA-256 match the spec. A verified file gets `<file>.sha256` beside it, so [isReady] needs
 * no second hash of several gigabytes. Cancelling the collection keeps the partial file for the next [download].
 */
class ModelDownloader(
    private val dir: File,
    private val open: (URL) -> HttpURLConnection = { url -> url.openConnection() as HttpURLConnection },
) {
    sealed interface Progress {
        data class Downloading(val bytes: Long, val total: Long, val source: String) : Progress
        data object Verifying : Progress
        data class Done(val file: File) : Progress
        data class Failed(val reason: String) : Progress
    }

    fun file(spec: LocalModelSpec): File = File(dir, spec.fileName)

    fun isReady(spec: LocalModelSpec): Boolean =
        file(spec).length() == spec.sizeBytes && marker(spec).readTextOrNull()?.trim() == spec.sha256

    /** Bytes on disk so far, whole or partial. */
    fun downloadedBytes(spec: LocalModelSpec): Long =
        if (file(spec).isFile) file(spec).length() else partial(spec).length()

    fun delete(spec: LocalModelSpec) {
        listOf(file(spec), partial(spec), marker(spec)).forEach { it.delete() }
    }

    fun download(spec: LocalModelSpec): Flow<Progress> = flow {
        dir.mkdirs()
        // A file put there by hand (adb, or a copy) of the right size only needs checking.
        if (!(file(spec).length() == spec.sizeBytes || fetch(spec))) return@flow
        emit(Progress.Verifying)
        val whole = if (file(spec).isFile) file(spec) else partial(spec)
        if (sha256(whole) != spec.sha256) {
            delete(spec)
            emit(Progress.Failed("校验失败：文件内容和发布的 SHA-256 不一致，已删除，请重新下载"))
            return@flow
        }
        if (whole != file(spec)) whole.renameTo(file(spec))
        marker(spec).writeText(spec.sha256)
        emit(Progress.Done(file(spec)))
    }.flowOn(Dispatchers.IO)

    /** Fetches until the partial file is whole; false (after a [Progress.Failed]) when every source failed. */
    private suspend fun FlowCollector<Progress>.fetch(spec: LocalModelSpec): Boolean {
        var reason = "没有下载地址"
        repeat(ROUNDS) {
            for (url in spec.urls) {
                if (partial(spec).length() >= spec.sizeBytes) return true
                try {
                    fetchFrom(URL(url), spec)
                } catch (e: IOException) {
                    reason = "${URL(url).host}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
        if (partial(spec).length() >= spec.sizeBytes) return true
        emit(Progress.Failed("下载失败：$reason"))
        return false
    }

    private suspend fun FlowCollector<Progress>.fetchFrom(url: URL, spec: LocalModelSpec) {
        val part = partial(spec)
        val offset = part.length().coerceAtMost(spec.sizeBytes)
        val connection = open(url).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) throw IOException("HTTP $code")
            // A server that ignores the Range starts from the beginning again.
            val append = code == HttpURLConnection.HTTP_PARTIAL
            var bytes = if (append) offset else 0L
            var lastEmit = 0L
            connection.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        bytes += read
                        if (bytes - lastEmit >= EMIT_EVERY || bytes == spec.sizeBytes) {
                            lastEmit = bytes
                            emit(Progress.Downloading(bytes, spec.sizeBytes, url.host))
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun partial(spec: LocalModelSpec) = File(dir, "${spec.fileName}.part")

    private fun marker(spec: LocalModelSpec) = File(dir, "${spec.fileName}.sha256")

    private companion object {
        const val ROUNDS = 3
        const val TIMEOUT_MS = 30_000
        const val BUFFER = 256 * 1024
        const val EMIT_EVERY = 8L * 1024 * 1024
    }
}

private fun File.readTextOrNull(): String? = if (isFile) readText() else null

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
