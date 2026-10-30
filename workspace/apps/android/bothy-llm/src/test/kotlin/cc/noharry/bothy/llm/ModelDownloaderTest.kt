package cc.noharry.bothy.llm

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModelDownloaderTest {
    private val content = ByteArray(300_000) { (it % 251).toByte() }
    private lateinit var server: MockWebServer
    private lateinit var dir: File

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        dir = Files.createTempDirectory("models").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    @Test
    fun downloadsVerifiesAndMarksTheFile() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(content)))
        val downloader = ModelDownloader(dir)

        val progress = downloader.download(spec()).toList()

        assertTrue(progress.last() is ModelDownloader.Progress.Done)
        assertTrue(ModelDownloader.Progress.Verifying in progress)
        assertTrue(downloader.isReady(spec()))
        assertEquals(content.size.toLong(), downloader.file(spec()).length())
    }

    @Test
    fun resumesAPartialFileWithARangeRequest() = runTest {
        File(dir, "model.gguf.part").writeBytes(content.copyOfRange(0, 100_000))
        server.enqueue(
            MockResponse().setResponseCode(206).setBody(Buffer().write(content.copyOfRange(100_000, content.size))),
        )

        val progress = ModelDownloader(dir).download(spec()).toList()

        assertEquals("bytes=100000-", server.takeRequest().getHeader("Range"))
        assertTrue(progress.last() is ModelDownloader.Progress.Done)
    }

    @Test
    fun movesToTheNextSourceWhenOneFails() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(Buffer().write(content)))
        val spec = spec(urls = listOf(server.url("/a").toString(), server.url("/b").toString()))

        val progress = ModelDownloader(dir).download(spec).toList()

        assertTrue(progress.last() is ModelDownloader.Progress.Done)
        assertEquals(listOf("/a", "/b"), listOf(server.takeRequest().path, server.takeRequest().path))
    }

    @Test
    fun dropsAFileWhoseHashDoesNotMatch() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(content)))
        val downloader = ModelDownloader(dir)

        val progress = downloader.download(spec(sha256 = "0".repeat(64))).toList()

        assertTrue(progress.last() is ModelDownloader.Progress.Failed)
        assertFalse(downloader.file(spec()).exists())
        assertFalse(File(dir, "model.gguf.part").exists())
    }

    @Test
    fun onlyChecksAFileAlreadyInPlace() = runTest {
        File(dir, "model.gguf").writeBytes(content)

        val progress = ModelDownloader(dir).download(spec()).toList()

        assertEquals(0, server.requestCount)
        assertTrue(progress.last() is ModelDownloader.Progress.Done)
    }

    private fun spec(
        urls: List<String> = listOf(server.url("/model.gguf").toString()),
        sha256: String = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) },
    ) = LocalModelSpec("test", "Test", "model.gguf", content.size.toLong(), sha256, urls, contextTokens = 4096)
}
