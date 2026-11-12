package cc.noharry.agent.appserver.pages

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.PageRequests
import cc.noharry.agent.appserver.protocol.view.SHOW_HTML_TOOL
import cc.noharry.agent.appserver.runtime.RunFacts
import cc.noharry.agent.appserver.server.DataClearing
import cc.noharry.agent.appserver.server.PluginHost
import cc.noharry.agent.appserver.server.PluginSwitches
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drawn pages come only with their plugin: the tool, the request that reads a page, the run's lines, the files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PagesPluginTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val host = PluginHost(app, listOf(PagesPlugin(whenToDraw = listOf(WHEN))), AllOn())

    @Test
    fun runningItBringsTheToolTheRequestAndTheLinesOnlyToARunThePageCanShowIn() {
        host.startAll()
        val additions = host.additions
        assertTrue(PageRequests.READ.name in additions.routes)
        // Drawing only shows something: its calls never ask, and no plugin's MCP server takes its name.
        assertEquals(false, additions.acts(PAGE_TOOL_SERVER_ID, SHOW_HTML_TOOL, "{}"))
        assertTrue(PAGE_TOOL_SERVER_ID in additions.ownToolServers)
        fun tools(facts: RunFacts) = additions.runTools.flatMap { it(facts) }.map { it.providerId }
        fun lines(facts: RunFacts) = additions.contextLines.flatMap { it(facts) }
        assertEquals(listOf(PAGE_TOOL_SERVER_ID), tools(facts(inFront = true)))
        assertEquals(listOf(WHEN), lines(facts(inFront = true)))
        // Out of sight a session that took the tool up still has it, and the run is told to answer in words.
        assertEquals(listOf(PAGE_TOOL_SERVER_ID), tools(facts(inFront = false)))
        assertTrue(lines(facts(inFront = false)).single().contains("answer in words"))
        // Never in an app that does not say when its screens show, for the host's own model, or in a subagent.
        listOf(facts(inFront = null), facts(inFront = true, hostModel = true), facts(inFront = true, child = true)).forEach {
            assertTrue(tools(it).isEmpty() && lines(it).isEmpty())
        }
    }

    @Test
    fun turnedOffItTakesAllOfItBackAndAClearingStillDeletesItsPages() {
        host.startAll()
        val first = saveDrawnPage(app, "session-1", "<p>x</p>")
        val second = saveDrawnPage(app, "session-2", "<p>y</p>")
        host.setEnabled("pages", false)
        with(host.additions) {
            assertTrue(routes.isEmpty() && runTools.isEmpty() && contextLines.isEmpty() && ownToolServers.isEmpty())
            assertNull(acts(PAGE_TOOL_SERVER_ID, SHOW_HTML_TOOL, "{}"))
            // Off, a conversation deleted takes its pages, and the runtime data cleared takes them all.
            assertTrue(clearData(DataClearing("session-1")))
            assertNull(readDrawnPage(app, first))
            assertNotNull(readDrawnPage(app, second))
            assertTrue(clearData(DataClearing(sessionId = null)))
        }
        assertNull(readDrawnPage(app, second))
        host.setEnabled("pages", true)
        assertTrue(PageRequests.READ.name in host.additions.routes)
    }

    @Test
    fun clearingRuntimeDataDeletesEveryConversationsPages() {
        host.startAll()
        val first = saveDrawnPage(app, "session-1", "<p>1</p>")
        val second = saveDrawnPage(app, "session-2", "<p>2</p>")
        assertTrue(host.additions.clearData(DataClearing("session-1")))
        assertNull(readDrawnPage(app, first))
        assertNotNull(readDrawnPage(app, second))
        assertTrue(host.additions.clearData(DataClearing(sessionId = null)))
        assertNull(readDrawnPage(app, second))
    }

    @Test
    fun theGenericPersonaReadsTheToolInEnglish() = runBlocking {
        val tool = PageToolProvider(onScreen = true, cards = { true }) { _, _ -> "" }
        val words = listOf(tool.displayName) + tool.listTools().flatMap { listOf(it.title.orEmpty(), it.description, it.inputSchemaJson) }
        words.forEach { text -> assertFalse(text, text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) }
        // With cards offered, a page does not stand in for them.
        assertTrue(tool.listTools().single().description.contains("show_card"))
        assertFalse(PageToolProvider(onScreen = true) { _, _ -> "" }.listTools().single().description.contains("show_card"))
    }

    private fun facts(inFront: Boolean?, hostModel: Boolean = false, child: Boolean = false) =
        RunFacts(app as Context, "", child = child, webSearch = false, subagentTypes = emptySet(), appInFront = inFront, hostModel = hostModel)

    private class AllOn : PluginSwitches {
        private val off = mutableSetOf<String>()

        override fun isOn(id: String): Boolean = id !in off

        override fun set(id: String, on: Boolean) {
            if (on) off -= id else off += id
        }
    }

    private companion object {
        const val WHEN = "Draw long answers as pages."
    }
}
