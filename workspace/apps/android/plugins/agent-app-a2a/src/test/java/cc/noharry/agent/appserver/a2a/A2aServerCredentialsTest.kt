package cc.noharry.agent.appserver.a2a

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.a2a.server.A2aAuthorizer
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.SettingsRequests
import cc.noharry.agent.appserver.protocol.TextResult
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import cc.noharry.agent.appserver.server.RouteTable
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class A2aServerCredentialsTest {
    @Test fun explicitSettingsRequestReturnsTheIncomingServerCredential() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val handlers = mutableMapOf<String, suspend (JsonElement) -> JsonElement>()
        val table = object : RouteTable {
            override fun <P, R> on(method: RequestMethod<P, R>, block: suspend (P) -> R) {
                handlers[method.name] = { params ->
                    Json.encodeToJsonElement(method.result, block(Json.decodeFromJsonElement(method.params, params)))
                }
            }
        }
        // Without the local endpoint there is no token to read.
        with(a2aRoutes(context, localServer = false)) { table.install() }
        assertFalse(SettingsRequests.A2A_SERVER_TOKEN.name in handlers)
        handlers.clear()
        with(a2aRoutes(context, localServer = true)) { table.install() }
        val request = handlers.getValue(SettingsRequests.A2A_SERVER_TOKEN.name)
        val params = Json.encodeToJsonElement(Empty.serializer(), Empty)
        val token = Json.decodeFromJsonElement(TextResult.serializer(), request(params)).text
        assertEquals(A2aServerManager.serverToken(context), token)
        assertEquals(token, Json.decodeFromJsonElement(TextResult.serializer(), request(params)).text)
        val authorizer = A2aAuthorizer.bearer(token)
        assertFalse(authorizer.allows(emptyMap()))
        assertTrue(authorizer.allows(mapOf("authorization" to "Bearer $token")))
    }

    @Test fun privateCredentialPersistsAndUnauthenticatedRequestsAreRejected() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File(context.noBackupFilesDir, "a2a/server-token")
        file.delete()
        val first = A2aServerManager.serverToken(context)
        assertEquals(64, first.length)
        assertEquals(first, A2aServerManager.serverToken(context))
        val authorizer = A2aAuthorizer.bearer(first)
        assertFalse(authorizer.allows(emptyMap()))
        assertFalse(authorizer.allows(mapOf("authorization" to "Bearer wrong-token")))
        assertTrue(authorizer.allows(mapOf("authorization" to "Bearer $first")))
    }

    @Test fun aCredentialThatCannotBeWrittenLeavesTheEndpointOffWithoutFailingThePlugin() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dir = File(context.noBackupFilesDir, "a2a")
        dir.deleteRecursively()
        dir.parentFile?.mkdirs()
        // Where the token's directory should be, a file: the token cannot be kept.
        dir.writeText("not a directory")
        try {
            A2aServerManager.start(context, A2aHost.NONE, NoServerTurns)
            assertThrows(Exception::class.java) { A2aServerManager.serverToken(context) }
        } finally {
            A2aServerManager.stop()
            dir.delete()
        }
    }

    @Test fun interruptedCredentialWriteRecoversWithoutAcceptingPartialToken() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File(context.noBackupFilesDir, "a2a/server-token")
        file.parentFile?.mkdirs()
        file.writeText("partial")
        val token = A2aServerManager.serverToken(context)
        assertEquals(64, token.length)
        assertEquals(token, file.readText())
    }
}

/** Turns that never run: the endpoint does not start. */
private object NoServerTurns : cc.noharry.agent.appserver.server.OutsideTurns {
    override fun follow(runId: String) = error("no turn runs")
    override suspend fun run(text: String, runId: String, sessionId: String) = error("no turn runs")
    override suspend fun input(sessionId: String, runId: String, messageId: String, text: String, questionId: String?) = error("no turn runs")
    override suspend fun cancel(sessionId: String, runId: String) = error("no turn runs")
}
