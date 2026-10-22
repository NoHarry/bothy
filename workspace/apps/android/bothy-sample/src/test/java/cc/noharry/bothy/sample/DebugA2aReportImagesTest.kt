@file:OptIn(ExperimentalA2aClientApi::class)

package cc.noharry.bothy.sample

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import cc.noharry.agent.a2a.RemoteAgentRef
import java.io.File
import java.net.URI
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DebugA2aReportImagesTest {
    private lateinit var context: Context
    private val server = MockWebServer()
    private val id = "0f9866f0-1d06-4b26-b0c2-150168931aa1"

    @Before
    fun start() {
        context = ApplicationProvider.getApplicationContext()
        server.start()
        DebugA2aTokens.save(context, "user-grok-bot", "tok-0123456789")
    }

    @After
    fun stop() {
        server.shutdown()
        DebugA2aTokens.clear(context, "user-grok-bot")
        File(context.filesDir, GENERATED_IMAGES_DIR).deleteRecursively()
    }

    @Test
    fun picturesAreFetchedWithTheProfileTokenAndSavedAsGeneratedPictures() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(PNG)))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>"))
        val remote = RemoteAgentRef(id = "user-grok-bot", endpoint = URI(server.url("/a2a").toString()))

        val saved = fetchReportPictures(context, remote, id, listOf("/files/$id/1.png" to "门头", "/files/$id/2.png" to ""))

        assertEquals(listOf(DebugA2aReportPicture("generated-images/delegation-0f9866f01d064b26/1.png", "门头")), saved)
        assertEquals("Bearer tok-0123456789", server.takeRequest().getHeader("Authorization"))
        assertTrue(generatedImageFile(context, saved.single().reference)!!.readBytes().contentEquals(PNG))
    }

    @Test
    fun pathsOutsideTheRelaysFilesRouteAreNeverRequested() = runBlocking {
        val remote = RemoteAgentRef(id = "user-grok-bot", endpoint = URI(server.url("/a2a").toString()))

        val saved = fetchReportPictures(context, remote, id, listOf("https://img.example.com/a.png" to "", "/a2a" to ""))

        assertTrue(saved.isEmpty())
        assertEquals(0, server.requestCount)
    }

    private companion object {
        val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13)
    }
}
