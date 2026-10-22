@file:OptIn(ExperimentalA2aClientApi::class)

package cc.noharry.bothy.sample

import cc.noharry.agent.a2a.A2aClientConfig
import cc.noharry.agent.a2a.A2aTask
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import cc.noharry.agent.a2a.RemoteAgentRef
import cc.noharry.agent.a2a.server.A2aInputReceipt
import cc.noharry.agent.a2a.server.A2aOutput
import cc.noharry.agent.a2a.server.A2aRequest
import cc.noharry.agent.a2a.server.A2aRequestHandler
import cc.noharry.agent.a2a.server.A2aServer
import cc.noharry.agent.a2a.server.A2aServerConfig
import java.net.URI
import java.util.Collections
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real sockets: the packaged direct client against the real A2A server, as client mode runs on the device. */
class DebugA2aClientLoopbackTest {
    private val inputs = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
    private val answered = CompletableDeferred<Unit>()
    private lateinit var server: A2aServer

    @Before
    fun setUp() {
        server = A2aServer(A2aServerConfig(port = 0), object : A2aRequestHandler {
            override fun execute(request: A2aRequest): Flow<A2aOutput> = flow {
                emit(A2aOutput.Status("navi 正在调用", "started", "navi", "call-1"))
                emit(A2aOutput.Speech("好的，马上出发"))
                emit(A2aOutput.Question("q1", "走高速吗？", listOf("是", "否"), "pending"))
                withTimeout(5_000) { answered.await() }
                emit(A2aOutput.Question("q1", "走高速吗？", listOf("是", "否"), "answer_received"))
                emit(A2aOutput.Status("navi 调用完成", "completed", "navi", "call-1"))
                emit(A2aOutput.Speech("已开始导航，走高速", final = true))
                emit(A2aOutput.Result("已开始导航，全程高速约 30 公里"))
            }

            override suspend fun postInput(request: A2aRequest, messageId: String, text: String, questionId: String?): A2aInputReceipt {
                inputs += text to questionId
                answered.complete(Unit)
                return A2aInputReceipt(accepted = true)
            }
        })
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun clientModeRoundTripRoutesEveryChannelOverRealSockets() = runBlocking {
        val speech = mutableListOf<DebugA2aSpeech>()
        val client = DebugA2aModuleRegistry.packaged().create(DIRECT_MODULE_ID).factory()
            .create(A2aClientConfig(requestTimeout = 1.minutes, allowLoopbackHttpForDebug = true))
        val session = DebugA2aClientSession(
            client = client,
            remote = RemoteAgentRef("loopback", URI("http://127.0.0.1:${server.localPort}/a2a")),
            scope = this,
            ioDispatcher = Dispatchers.IO,
            onSpeech = { speech += it },
        )

        assertTrue(session.send("导航去机场", DebugA2aInputSource.CONSOLE))
        withTimeout(10_000) { while (session.state.question("q1") == null) delay(10) }
        session.answer("q1", "是", DebugA2aInputSource.CONSOLE)
        withTimeout(10_000) { while (session.state.running) delay(10) }

        val state = session.state
        assertEquals(listOf("是" to "q1"), inputs.toList())
        assertEquals(listOf("好的，马上出发" to false, "已开始导航，走高速" to true), speech.map { it.text to it.final })
        assertEquals("completed", state.items.filterIsInstance<DebugA2aClientItem.Tool>().single().phase)
        assertEquals("已开始导航，全程高速约 30 公里", state.items.filterIsInstance<DebugA2aClientItem.Display>().single().text)
        assertEquals("answer_received", state.question("q1")?.phase)
        assertEquals(A2aTask.Status.COMPLETED, state.items.filterIsInstance<DebugA2aClientItem.Terminal>().single().state)
        assertFalse(state.items.any { it is DebugA2aClientItem.Notice && it.error })
        val contextId = requireNotNull(state.contextId)

        // A follow-up in the same conversation continues the server context.
        assertTrue(session.send("换一条路线", DebugA2aInputSource.ADB))
        withTimeout(10_000) { while (session.state.running) delay(10) }
        assertEquals(contextId, session.state.contextId)
        client.close()
    }
}
