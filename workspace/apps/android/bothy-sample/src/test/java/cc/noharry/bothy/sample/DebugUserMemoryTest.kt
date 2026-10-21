package cc.noharry.bothy.sample

import cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.bothy.kernel.api.RuntimeContextRequest
import cc.noharry.bothy.kernel.api.RuntimeContextScalar
import cc.noharry.bothy.kernel.api.ToolCallContext
import cc.noharry.bothy.kernel.api.ToolPrefetchRequest
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalAgentRuntimeApi::class)
class DebugUserMemoryTest {
    private val dir: File = Files.createTempDirectory("user-memory").toFile()
    private var clock = 1_000L
    private val book = DebugUserMemoryBook(File(dir, "memories.json")) { clock }

    @Test
    fun remembersChangesForgetsAndReadsBackFromTheFile() {
        val home = saved(book.remember(DebugMemoryCategory.PLACES, " 家在望京花园 ", DebugMemorySource.SAID, "记住我家在望京花园"))
        assertEquals("家在望京花园", home.content)
        assertEquals("记住我家在望京花园", home.said)
        // The same words again (punctuation aside) are kept once.
        val again = book.remember(DebugMemoryCategory.PLACES, "家在望京花园。", DebugMemorySource.SAID) as DebugMemoryWrite.Saved
        assertFalse(again.created)
        assertEquals(1, book.all().size)
        clock = 2_000L
        val moved = saved(book.update(home.id, "家在回龙观", null, DebugMemorySource.TYPED))
        assertEquals(DebugMemoryCategory.PLACES, moved.category)
        assertEquals(1_000L to 2_000L, moved.createdAtMillis to moved.updatedAtMillis)
        assertEquals("记住我家在望京花园", moved.said)
        book.remember(DebugMemoryCategory.HOME, "开空调一般 22 度", DebugMemorySource.TYPED)
        val reread = DebugUserMemoryBook(File(dir, "memories.json")).all()
        assertEquals(listOf("家在回龙观", "开空调一般 22 度"), reread.map { it.content })
        assertEquals(DebugMemorySource.TYPED, reread.last().source)
        assertTrue(book.forget(home.id) is DebugMemoryWrite.Removed)
        assertTrue(book.forget(home.id) is DebugMemoryWrite.Refused)
        assertEquals(listOf("开空调一般 22 度"), book.all().map { it.content })
        // 只清除记忆 empties the book and its file.
        book.clear()
        assertTrue(book.all().isEmpty())
        assertTrue(DebugUserMemoryBook(File(dir, "memories.json")).all().isEmpty())
    }

    @Test
    fun refusesSecretsLongNumbersAndOverlongWords() {
        assertNull(memoryContentError("老婆电话 13812345678"))
        assertEquals("不记密码、验证码这类信息", memoryContentError("车库门密码是 4321"))
        assertEquals("不记身份证号、银行卡号这类长号码", memoryContentError("身份证 110101199001011234"))
        assertEquals("不记身份证号、银行卡号这类长号码", memoryContentError("卡号 6222 0212 3456 7890"))
        assertEquals("内容是空的", memoryContentError("  "))
        assertTrue(memoryContentError("长".repeat(MAX_MEMORY_CHARS + 1))!!.startsWith("太长了"))
        assertTrue(book.remember(DebugMemoryCategory.OTHER, "验证码 123456", DebugMemorySource.SAID) is DebugMemoryWrite.Refused)
        assertTrue(book.all().isEmpty())
    }

    @Test
    fun keepsOneLineAndNoSecretsInTheWordsAsSaid() {
        val typed = saved(book.remember(DebugMemoryCategory.PLACES, "家在望京花园3号楼\n公司在国贸", DebugMemorySource.TYPED))
        assertEquals("家在望京花园3号楼 公司在国贸", typed.content)
        val home = saved(book.remember(DebugMemoryCategory.PLACES, "家在回龙观", DebugMemorySource.SAID, "记住我家在回龙观，门禁密码是3721"))
        assertNull(home.said)
        assertEquals("不记身份证号、银行卡号这类长号码", memoryContentError("卡号 6222/0212/3456/7890"))
        assertEquals("不记身份证号、银行卡号这类长号码", memoryContentError("卡号 6222 - 0212 - 3456 - 7890"))
        // A book written when 30 were allowed: 小博 uses the 24 last changed, in their order.
        val many = (1..30).map { DebugUserMemory("m$it", DebugMemoryCategory.OTHER, "事 $it", DebugMemorySource.TYPED, null, 0L, it.toLong()) }
        assertEquals((7..30).map { "m$it" }, memoriesInUse(many).map { it.id })
    }

    @Test
    fun capsEachKindAndTheWhole() {
        repeat(MAX_PER_CATEGORY) { book.remember(DebugMemoryCategory.MEDIA, "喜欢歌手 $it", DebugMemorySource.SAID) }
        val full = book.remember(DebugMemoryCategory.MEDIA, "喜欢歌手 X", DebugMemorySource.SAID) as DebugMemoryWrite.Refused
        assertTrue(full.reason, full.reason.contains("娱乐"))
        val other = book.remember(DebugMemoryCategory.OTHER, "别的", DebugMemorySource.SAID) as DebugMemoryWrite.Saved
        assertTrue(book.update(other.memory.id, "别的", DebugMemoryCategory.MEDIA, DebugMemorySource.TYPED) is DebugMemoryWrite.Refused)
        val kinds = DebugMemoryCategory.entries.filter { it != DebugMemoryCategory.MEDIA }
        var n = 0
        while (book.all().size < MAX_MEMORIES) book.remember(kinds[n % kinds.size], "第 ${n++} 件", DebugMemorySource.SAID)
        assertTrue(book.remember(DebugMemoryCategory.TRAVEL, "再一件", DebugMemorySource.SAID) is DebugMemoryWrite.Refused)
    }

    @Test
    fun decodingDropsOnlyTheBrokenEntries() {
        val text = """{"version":1,"memories":[{"id":"m1","category":"home","content":"22 度","source":"typed","createdAt":5,"updatedAt":6},""" +
            """{"id":"m2","category":"nope","content":"x"},{"id":"m3","category":"people","content":""},"bad"]}"""
        val memories = decodeMemories(text)
        assertEquals(listOf("m1"), memories.map { it.id })
        assertEquals(memories, decodeMemories(encodeMemories(memories)))
    }

    @Test
    fun theToolRemembersUpdatesAndForgetsThroughTheBook() = runBlocking {
        val tool = DebugUserMemoryToolProvider { change -> change(book) }
        assertEquals(listOf(USER_MEMORY_TOOL), tool.listTools().map { it.name })
        val remembered = call(tool, """{"action":"remember","category":"identity","content":"叫我老王","said":"以后叫我老王"}""")
        assertEquals(true, remembered["ok"]?.jsonPrimitive?.content?.toBoolean())
        val id = remembered["id"]!!.jsonPrimitive.content
        assertEquals("以后叫我老王", book.all().single().said)
        call(tool, """{"action":"update","id":"$id","content":"叫我王哥"}""")
        assertEquals("叫我王哥", book.all().single().content)
        val missing = call(tool, """{"action":"remember","content":"没有类别"}""")
        assertEquals("remember 需要 category 和 content", missing["message"]?.jsonPrimitive?.content)
        val secret = call(tool, """{"action":"remember","category":"other","content":"支付密码 123456"}""")
        assertEquals("NOT_SAVED", secret["code"]?.jsonPrimitive?.content)
        call(tool, """{"action":"forget","id":"$id"}""")
        assertTrue(book.all().isEmpty())
        val unknown = tool.callTool(USER_MEMORY_TOOL, """{"action":"forget","id":"$id"}""", ToolCallContext("s", "r", "q"))
        assertFalse(unknown.success)
    }

    @Test
    fun wordsAboutTheUserOfferTheToolOnTheFirstRound() {
        val tool = DebugUserMemoryToolProvider { change -> change(book) }
        fun offered(words: String) = tool.localToolNames(ToolPrefetchRequest(words, emptyList(), ToolCallContext("s", "r", "q"), 24))
        assertEquals(listOf(USER_MEMORY_TOOL), offered("记住我家在望京花园3号楼，以后叫我老王"))
        assertEquals(listOf(USER_MEMORY_TOOL), offered("忘掉我公司地址"))
        assertEquals(listOf(USER_MEMORY_TOOL), offered("你都记得我什么"))
        assertEquals(emptyList<String>(), offered("导航去广州塔"))
        assertFalse(tool.remotePrefetchEnabled)
    }

    @Test
    fun eachKindIsOneBlockOfFactsWhoseIdsTheToolUses() = runBlocking {
        val request = RuntimeContextRequest("run", "session", "request", null, "你记得我什么", 0L, null)
        assertTrue(DebugUserMemoryContext { emptyList() }.provide(request).blocks.isEmpty())
        val home = saved(book.remember(DebugMemoryCategory.PLACES, "家在望京花园", DebugMemorySource.SAID))
        book.remember(DebugMemoryCategory.IDENTITY, "叫我老王", DebugMemorySource.SAID)
        val contribution = DebugUserMemoryContext { book.all() }.provide(request)
        assertEquals(listOf("user_memory.identity", "user_memory.places"), contribution.blocks.map { it.id })
        val fact = contribution.blocks.last().data.single()
        assertEquals(home.id, fact.id)
        assertEquals("常去地点" to "user_memory", fact.subject to fact.predicate)
        assertEquals(RuntimeContextScalar.Text("家在望京花园"), fact.value)
        book.update(home.id, "家在回龙观", null, DebugMemorySource.SAID)
        val changed = DebugUserMemoryContext { book.all() }.provide(request)
        assertTrue(changed.fingerprint != contribution.fingerprint)
    }

    @Test
    fun theProfileReachesTheEndToEndModelAndTheCompanion() {
        book.remember(DebugMemoryCategory.IDENTITY, "叫我老王", DebugMemorySource.SAID)
        book.remember(DebugMemoryCategory.HOME, "开空调一般 22 度", DebugMemorySource.SAID)
        val profile = memoryProfileText(book.all())
        assertTrue(profile, profile.contains("- 称呼与身份：叫我老王\n- 设备与家居偏好：开空调一般 22 度"))
        assertEquals("", memoryProfileText(emptyList()))
        val on = realtimeInstructions("你是小博。", profile, remembers = true)
        assertTrue(on.startsWith("你是小博。\n"))
        assertTrue(on.contains("由小博记下") && on.endsWith("开空调一般 22 度"))
        val off = realtimeInstructions("你是小博。", profile, remembers = false)
        assertFalse(off.contains("由小博记下") || off.contains("老王"))
        val system = companionMessages("你是小博。", emptyList(), "今天好累", profile).first().second
        assertTrue(system.endsWith(profile))
        val companion = DebugCompanionConfig(enabled = true, mode = DebugCompanionMode.ROUTE)
        assertFalse(decideCompanionRoute(companion, "记住我喜欢周杰伦", 0.95, null, false).toCompanion)
        assertFalse(decideCompanionRoute(companion, "以后叫我老王吧", 0.95, null, false).toCompanion)
    }

    @Test
    fun theHeaderNamesTheUserAndCountsTheDays() {
        val day = 24L * 60 * 60 * 1000
        assertEquals("还没有记住什么", memorySummary(emptyList(), 0L).line)
        book.remember(DebugMemoryCategory.IDENTITY, "以后叫我老王，别叫全名", DebugMemorySource.SAID)
        clock += 2 * day
        book.remember(DebugMemoryCategory.PLACES, "公司在望京 SOHO", DebugMemorySource.TYPED)
        val summary = memorySummary(book.all(), clock + day / 2)
        assertEquals("老王", summary.name)
        assertEquals("小博记住了 2 件事 · 认识你 3 天", summary.line)
        assertEquals(listOf("2", "1", "2"), summary.metrics.take(3).map { it.first })
        assertEquals("正在记下来", "Running mcp__memory__user_memory".stepPhrase())
        // What a model wrote on the bench (2026-10-15), and other ways of putting it.
        listOf(
            "称呼为老王" to "老王", "用户希望被称为小李" to "小李", "我叫王磊，是程序员" to "王磊", "称呼：老张" to "老张", "用户名叫杜强" to "杜强",
            "名字是张三" to "张三", "以后叫我老王吧" to "老王", "叫我老王就行" to "老王", "我叫王磊是程序员" to "王磊", "别叫我老王" to null,
            "不用叫我王总" to null, "不要再叫我老王" to null,
        ).forEach { (words, name) ->
            val memory = DebugUserMemory("m", DebugMemoryCategory.IDENTITY, words, DebugMemorySource.SAID, null, 0L, 0L)
            assertEquals(words, name, memorySummary(listOf(memory), 0L).name)
        }
    }

    private fun saved(result: DebugMemoryWrite): DebugUserMemory = (result as DebugMemoryWrite.Saved).memory

    private suspend fun call(tool: DebugUserMemoryToolProvider, arguments: String) =
        Json.parseToJsonElement(tool.callTool(USER_MEMORY_TOOL, arguments, ToolCallContext("s", "r", "q")).resultJson).jsonObject
}
