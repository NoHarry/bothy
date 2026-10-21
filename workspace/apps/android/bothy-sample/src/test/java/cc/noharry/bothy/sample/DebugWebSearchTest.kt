@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DebugWebSearchTest {
    private val sent = Collections.synchronizedList(mutableListOf<String>())

    @Test
    fun resultsComeBackWithSummariesAndABusyServiceIsAskedAgain() = runBlocking {
        val search = provider(
            429 to """{"error":{"message":"QPS limit exceeded"}}""",
            200 to """{"results":[
                {"title":"被越秀硬控了","url":"https://www.nfnews.com/a.html","snippet":"短","summary":"国医市集 10月17日至19日","site_name":"nfnews.com","date":"2026-10-17T16:00:00+00:00"},
                {"title":"只有摘要","url":"https://b.example.com","snippet":"片段","summary":""},
                {"title":"没有地址","url":""}]}""",
        )

        val results = search.search("广州 周末 活动", 5)

        assertEquals(2, sent.size)
        assertTrue(sent.last(), sent.last().contains("\"query\":\"广州 周末 活动\"") && sent.last().contains("\"count\":5"))
        assertEquals(listOf("https://www.nfnews.com/a.html", "https://b.example.com"), results.map { it.url })
        assertEquals("国医市集 10月17日至19日", results[0].summary)
        assertEquals("nfnews.com", results[0].site)
        assertEquals("片段", results[1].summary)
    }

    @Test
    fun aPageTheServiceCannotFetchIsNoFallback() = runBlocking {
        assertNull(provider(404 to """{"error":{"message":"no content"}}""").fetch("https://gone.example.com"))
        val page = provider(200 to """{"title":"文章","content":"# 文章\n\n正文"}""").fetch("https://a.example.com")
        assertEquals("# 文章\n\n正文", page?.markdown)
    }

    @Test
    fun aProfileSearchesThroughTheApiOnlyWhenSetToAndAKeyIsThere() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val deepSeek = DebugProviderProfile("ds", "DeepSeek", DebugProvider.ANTHROPIC, "k", "deepseek-flash", "https://api.deepseek.com/anthropic")
        val config = DebugProviderConfig.load(context).let { it.copy(profiles = it.profiles + deepSeek) }

        assertNull(debugWebSearch(context, config, deepSeek))
        DebugSearchBackend.set(context, "ds", true)
        assertNotNull(debugWebSearch(context, config, deepSeek))
        DebugSearchBackend.set(context, "ds", false)
        assertNull(debugWebSearch(context, config, deepSeek))
    }

    @Test
    fun subagentsCanSearchThroughTheApiWhateverTheMainAgentUses() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val deepSeek = DebugProviderProfile("ds2", "DeepSeek", DebugProvider.ANTHROPIC, "k", "deepseek-flash", "https://api.deepseek.com/anthropic")
        val config = DebugProviderConfig.load(context).let { it.copy(profiles = it.profiles + deepSeek) }

        assertFalse(loadDebugSubagentSettings(context).searchApi)
        saveDebugSubagentSettings(context, DebugSubagentSettings(searchApi = true))

        assertTrue(loadDebugSubagentSettings(context).searchApi)
        assertNull(debugWebSearch(context, config, deepSeek))
        assertNotNull(debugWebSearch(context, config, deepSeek, forceApi = true))
        saveDebugSubagentSettings(context, DebugSubagentSettings())
    }

    @Test
    fun theFetchFallbackIsItsOwnSwitchOnByDefaultWhateverTheSearch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val deepSeek = DebugProviderProfile("ds3", "DeepSeek", DebugProvider.ANTHROPIC, "k", "deepseek-flash", "https://api.deepseek.com/anthropic")
        val gateway = DebugProviderProfile("gw", "Gateway", DebugProvider.OPENAI, "k", "deepseek-flash", "https://openrouter.ai/api/v1")
        val loaded = DebugProviderConfig.load(context)
        val keyed = loaded.copy(profiles = loaded.profiles + deepSeek + gateway)
        val unkeyed = loaded.copy(profiles = loaded.profiles.filterNot { it.isDeepSeek() } + gateway)

        // The profile keeps its server-side search and still gets pages through the Fetch API, with a DeepSeek key
        // from any profile.
        assertNull(debugWebSearch(context, keyed, gateway))
        assertNotNull(debugWebFetchFallback(context, keyed, gateway))
        assertTrue(keyed.hasDeepSeekKey(context))
        assertNull(debugWebFetchFallback(context, unkeyed, gateway))
        assertFalse(unkeyed.hasDeepSeekKey(context))

        DebugFetchFallback.set(context, false)
        assertNull(debugWebFetchFallback(context, keyed, deepSeek))
        DebugFetchFallback.set(context, true)
    }

    private fun provider(vararg answers: Pair<Int, String>): DeepSeekWebSearch {
        val queue = ArrayDeque(answers.toList())
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            sent += Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            val (code, body) = queue.removeFirst()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()
        return DeepSeekWebSearch(apiKey = { "test-key" }, baseUrl = "https://api.deepseek.com/v1/tools", client = client)
    }
}
