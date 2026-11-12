package cc.noharry.agent.appserver.memory

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.MemoryCategory
import cc.noharry.agent.appserver.protocol.MemoryRequests
import cc.noharry.agent.appserver.protocol.MemorySource
import cc.noharry.agent.appserver.protocol.MemoryView
import cc.noharry.agent.appserver.protocol.ServerFeatures
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcNotification
import cc.noharry.agent.appserver.runtime.RunFacts
import cc.noharry.agent.appserver.server.DataClearing
import cc.noharry.agent.appserver.server.PluginHost
import cc.noharry.agent.appserver.server.PluginSwitches
import cc.noharry.agent.appserver.server.PublishedState
import java.io.File
import kotlinx.coroutines.flow.Flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the model remembers comes only with its plugin, and goes with it: the tool, the facts each round, the requests,
 * the section, the file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MemoryPluginTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val host = PluginHost(app, listOf(MemoryPlugin(page = "Me → Memory")), AllOn())

    @After
    fun stop() = host.setEnabled("memory", false).let { }

    @Test
    fun withoutThePluginNothingIsRememberedReadOrWritten() {
        // The person's switch is on, as it is at first: whether the plugin runs decides.
        assertTrue(app.getSharedPreferences("app_server_user_memory", Context.MODE_PRIVATE).getBoolean("enabled", true))
        assertFalse(userMemoryEnabled(app))
        assertTrue(UserMemoryStore.inUse(app).isEmpty())
        assertTrue(UserMemoryStore.write(app) { it.remember(MemoryCategory.OTHER, "x", MemorySource.SAID) } is MemoryWrite.Refused)
        assertFalse(File(app.filesDir, "user-memory").exists())
    }

    @Test
    fun runningItBringsTheToolTheFactsTheRequestsAndTheSection() {
        host.startAll()
        idle()
        val additions = host.additions
        assertTrue(ServerFeatures.MEMORY in additions.offers)
        val memoryRequests = listOf(MemoryRequests.SET_ENABLED, MemoryRequests.WRITE, MemoryRequests.DELETE, MemoryRequests.CLEAR)
        assertEquals(memoryRequests.map { it.name }.toSet(), additions.routes.keys)
        assertTrue(USER_MEMORY_SERVER_ID in additions.ownToolServers)
        // Remembering, changing and forgetting never ask, in any approval mode (the gate's pluginActs reads this).
        listOf("remember", "update", "forget").forEach { action ->
            assertEquals(action, false, additions.acts(USER_MEMORY_SERVER_ID, USER_MEMORY_TOOL, """{"action":"$action"}"""))
        }
        assertEquals(listOf(USER_MEMORY_SERVER_ID), additions.runTools.flatMap { it(facts()) }.map { it.providerId })
        assertEquals(1, additions.contextProviders.flatMap { it(facts()) }.size)
        // The person's switch off: neither the tool nor the facts.
        setUserMemoryEnabled(app, false)
        assertTrue(additions.runTools.flatMap { it(facts()) }.isEmpty() && additions.contextProviders.flatMap { it(facts()) }.isEmpty())
        setUserMemoryEnabled(app, true)
        val state = RecordingState()
        host.publishInto(state)
        remember("Home is 3 Elm Street")
        idle()
        assertEquals(listOf("Home is 3 Elm Street"), state.current.memory.memories.map { it.content })
        assertEquals(listOf("Home is 3 Elm Street"), UserMemoryStore.inUse(app).map { it.content })
    }

    @Test
    fun turnedOffItPublishesNothingRefusesARunStillHoldingTheToolAndWorksAgainOn() {
        host.startAll()
        idle()
        val state = RecordingState()
        host.publishInto(state)
        remember("Call me Sam")
        idle()
        host.setEnabled("memory", false)
        assertEquals(MemoryView(), state.current.memory)
        with(host.additions) { assertTrue(offers.isEmpty() && routes.isEmpty() && runTools.isEmpty() && contextProviders.isEmpty()) }
        assertFalse(userMemoryEnabled(app))
        assertTrue(UserMemoryStore.inUse(app).isEmpty())
        val late = UserMemoryStore.write(app) { it.remember(MemoryCategory.OTHER, "too late", MemorySource.SAID) }
        assertEquals(MemoryRefusal.Off, (late as MemoryWrite.Refused).why)
        // On again, the book is read again, and published.
        host.setEnabled("memory", true)
        idle()
        host.publishInto(RecordingState())
        assertEquals(listOf("Call me Sam"), UserMemoryStore.inUse(app).map { it.content })
    }

    @Test
    fun clearingRuntimeDataTakesTheMemoriesOnlyWhenAskedTo() {
        host.startAll()
        idle()
        remember("Home is 3 Elm Street")
        assertTrue(host.additions.clearData(DataClearing("session-1")))
        assertTrue(host.additions.clearData(DataClearing(sessionId = null)))
        assertEquals(1, UserMemoryStore.inUse(app).size)
        assertTrue(host.additions.clearData(DataClearing(sessionId = null, also = setOf("memory"))))
        assertTrue(UserMemoryStore.inUse(app).isEmpty())
    }

    @Test
    fun turnedOffTheMemoriesStillGoWhenTheRuntimeDataIsClearedWithThem() {
        host.startAll()
        idle()
        remember("Home is 3 Elm Street")
        host.setEnabled("memory", false)
        val book = File(app.filesDir, "user-memory/memories.json")
        assertTrue(book.exists())
        // Deleting a conversation, or clearing without the memories, keeps them.
        assertTrue(host.additions.clearData(DataClearing("session-1")))
        assertTrue(host.additions.clearData(DataClearing(sessionId = null)))
        assertTrue(book.exists())
        assertTrue(host.additions.clearData(DataClearing(sessionId = null, also = setOf("memory"))))
        assertFalse(book.exists())
        host.setEnabled("memory", true)
        idle()
        assertTrue(UserMemoryStore.inUse(app).isEmpty())
    }

    private fun remember(content: String) {
        val written = UserMemoryStore.write(app) { it.remember(MemoryCategory.PLACES, content, MemorySource.TYPED) }
        assertTrue(written is MemoryWrite.Saved)
    }

    /** The plugin reads its book on a thread of its own, and its section follows on the main looper. */
    private fun idle() {
        Thread.sleep(LOAD_MILLIS)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun facts() = RunFacts(app as Context, "", child = false, webSearch = false, subagentTypes = emptySet())

    private class RecordingState : PublishedState {
        override var current = ServerState()
        override fun update(change: ServerState.() -> ServerState) { current = current.change() }
        override fun <T> follow(flow: Flow<T>, into: ServerState.(T) -> ServerState) = error("a plugin follows through its own scope")
        override fun broadcast(notification: JsonRpcNotification) = Unit
    }

    private class AllOn : PluginSwitches {
        private val off = mutableSetOf<String>()

        override fun isOn(id: String): Boolean = id !in off

        override fun set(id: String, on: Boolean) {
            if (on) off -= id else off += id
        }
    }

    private companion object {
        const val LOAD_MILLIS = 200L
    }
}
