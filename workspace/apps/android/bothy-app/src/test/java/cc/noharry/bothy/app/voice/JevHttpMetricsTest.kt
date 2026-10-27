@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.voice
import cc.noharry.agent.kernel.api.ToolPrefetchTrace
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class JevHttpMetricsTest {
    @Test
    fun derivedClientsReuseSameSocketAndRecordIndependentStageMetrics() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("{}")) }
            server.start()
            val config = JevConfig(endpoint = server.url("/classify").toString())
            val first = HttpTrace()
            val second = HttpTrace()
            assertEquals("{}", executeJevRequest(config, "fixture-secret", "{}", first))
            assertEquals("{}", executeJevRequest(config, "fixture-secret", "{}", second))
            assertEquals("false", first.data["connectionReused"]!!.jsonPrimitive.content)
            assertEquals("true", second.data["connectionReused"]!!.jsonPrimitive.content)
            assertEquals(first.data["connectionId"], second.data["connectionId"])
            assertEquals(first.data["connectionScope"], second.data["connectionScope"])
            assertEquals("1", first.phase("connect")["attempts"]!!.jsonPrimitive.content)
            assertEquals("0", second.phase("connect")["attempts"]!!.jsonPrimitive.content)
            assertEquals("true", second.data["transportFinished"]!!.jsonPrimitive.content)
            assertNotNull(second.data["uploadEndToResponseHeadersMs"])
            assertEquals(0, server.takeRequest().sequenceNumber)
            assertEquals(1, server.takeRequest().sequenceNumber)
            assertFalse(second.data.toString().contains("fixture-secret"))
            assertFalse(second.data.toString().contains("classify"))
            assertEquals("200", second.data["httpStatus"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun httpFailureRetainsSafeTransportEvidenceWithoutResponseOrHeaderSecrets() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("secret", "private-value").setBody("private-body"))
            server.start()
            val trace = HttpTrace()
            try {
                executeJevRequest(JevConfig(endpoint = server.url("/private-path").toString()), "private-key", "{}", trace)
                fail("HTTP failure must propagate")
            } catch (expected: IOException) {
                assertFalse(expected.message.orEmpty().contains("private"))
            }
            assertEquals("503", trace.data["httpStatus"]!!.jsonPrimitive.content)
            assertEquals("true", trace.data["transportFinished"]!!.jsonPrimitive.content)
            assertFalse(trace.data.toString().contains("private"))
        }
    }

    @Test
    fun cancelledCallStillEmitsOneSnapshotWithoutInventingResponseTiming() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val trace = HttpTrace()
            val result = withTimeoutOrNull(500) {
                executeJevRequest(JevConfig(endpoint = server.url("/").toString()), "fixture", "{}", trace)
            }
            assertNull(result)
            assertEquals(1, trace.snapshots)
            assertFalse(trace.data.containsKey("timeToResponseHeadersMs"))
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        }
    }
}

private class HttpTrace : ToolPrefetchTrace {
    var data: JsonObject = JsonObject(emptyMap())
    var snapshots = 0
    override fun request(json: String) = Unit
    override fun response(json: String) = Unit
    override fun diagnostics(json: String) { data = Json.parseToJsonElement(json).jsonObject; snapshots++ }
    fun phase(name: String): JsonObject = data["phases"]!!.jsonObject[name]!!.jsonObject
}
