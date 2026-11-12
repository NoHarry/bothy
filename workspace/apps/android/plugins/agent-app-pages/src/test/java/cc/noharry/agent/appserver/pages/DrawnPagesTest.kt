package cc.noharry.agent.appserver.pages

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.view.SHOW_HTML_TOOL
import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolPrefetchRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DrawnPagesTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun aPageIsKeptUnderItsSessionAndReadByItsReferenceUntilTheSessionGoes() {
        val reference = saveDrawnPage(app, "session-1", "<p>三款对比</p>")
        assertTrue(reference, reference.startsWith("drawn-pages/") && reference.endsWith(".html"))
        assertEquals("<p>三款对比</p>", readDrawnPage(app, reference))
        assertTrue(deleteDrawnPages(app, "session-1"))
        assertNull(readDrawnPage(app, reference))
    }

    @Test
    fun aReferenceReadsNothingOutsideTheSessionsPages() {
        saveDrawnPage(app, "session-2", "<p>x</p>")
        assertNull(readDrawnPage(app, "drawn-pages/../shared_prefs/x.html"))
        assertNull(readDrawnPage(app, "generated-images/abc/img-1.html"))
        assertNull(readDrawnPage(app, "drawn-pages/abc/missing.html"))
    }

    @Test
    fun anOfflinePageMayShowAndOneThatLoadsFromTheNetworkIsSentBackWithWhy() {
        assertNull(pageProblem("对比", "<style>b{color:red}</style><a href=\"https://example.com\">官网</a>"))
        assertEquals("title is required.", pageProblem(" ", "<p>x</p>"))
        val script = pageProblem("图表", "<script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>")
        assertTrue(script.orEmpty(), script.orEmpty().contains("offline"))
        assertTrue(pageProblem("图", "<img src='//example.com/a.png'>") != null)
        assertTrue(pageProblem("长", "x".repeat(MAX_PAGE_CHARS + 1)).orEmpty().contains("keep it under"))
    }

    @Test
    fun aPageOfUnclosedTagsIsCheckedQuickly() {
        val started = System.nanoTime()
        assertNull(pageProblem("坏", "<img ".repeat(MAX_PAGE_CHARS / 5 - 1)))
        assertTrue(pageProblem("坏", "<img ".repeat(MAX_PAGE_CHARS)).orEmpty().contains("keep it under"))
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $millis ms", millis < 5_000)
    }

    @Test
    fun theToolSavesThePageUnderTheRunsSessionAndAnswersWithItsReference() = runBlocking {
        val saved = mutableListOf<Pair<String, String>>()
        val tool = PageToolProvider(onScreen = true) { session, html -> saved += session to html; "drawn-pages/s/page-1-abcd.html" }
        val arguments = buildJsonObject {
            put("title", "房贷计算")
            put("html", "<input id=a>")
        }.toString()
        val result = tool.callTool(SHOW_HTML_TOOL, arguments, ToolCallContext("session-9", "run", "request"))
        val json = Json.parseToJsonElement(result.resultJson).jsonObject
        assertTrue(result.success)
        assertEquals(listOf("session-9" to "<input id=a>"), saved)
        assertEquals("drawn-pages/s/page-1-abcd.html", json["page"]?.jsonPrimitive?.content)
        assertEquals("房贷计算", json["title"]?.jsonPrimitive?.content)
        val refused = tool.callTool(SHOW_HTML_TOOL, buildJsonObject { put("title", "空") }.toString(), ToolCallContext("s", "r", "q"))
        assertFalse(Json.parseToJsonElement(refused.resultJson).jsonObject["ok"]!!.jsonPrimitive.boolean)
        assertEquals(1, saved.size)
    }

    @OptIn(ExperimentalAgentRuntimeApi::class)
    @Test
    fun aRunThatStartedWithTheConversationOffScreenDrawsNothing() = runBlocking {
        val saved = mutableListOf<String>()
        val tool = PageToolProvider(onScreen = false) { _, html -> saved += html; "drawn-pages/s/page.html" }
        assertEquals(listOf(SHOW_HTML_TOOL), tool.listTools().map { it.name })
        assertTrue(tool.localToolNames(ToolPrefetchRequest("画个对比", emptyList(), ToolCallContext("s", "r", "q"), 24)).isEmpty())
        val arguments = buildJsonObject {
            put("title", "对比")
            put("html", "<p>x</p>")
        }.toString()
        val result = tool.callTool(SHOW_HTML_TOOL, arguments, ToolCallContext("s", "r", "q"))
        assertFalse(result.success)
        assertEquals("PAGE_NOT_ON_SCREEN", Json.parseToJsonElement(result.resultJson).jsonObject["code"]?.jsonPrimitive?.content)
        assertTrue(saved.isEmpty())
    }
}
