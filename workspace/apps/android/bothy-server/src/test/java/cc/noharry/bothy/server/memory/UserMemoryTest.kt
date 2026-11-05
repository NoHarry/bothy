package cc.noharry.bothy.server.memory
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.image.ImageToolProvider
import cc.noharry.agent.appserver.memory.MemoryRefusal
import cc.noharry.agent.appserver.memory.MemoryWrite
import cc.noharry.agent.appserver.memory.USER_MEMORY_TOOL
import cc.noharry.agent.appserver.memory.UserMemoryBook
import cc.noharry.agent.appserver.memory.UserMemoryContext
import cc.noharry.agent.appserver.memory.UserMemoryToolProvider
import cc.noharry.agent.appserver.memory.decodeMemories
import cc.noharry.agent.appserver.memory.encodeMemories
import cc.noharry.agent.appserver.memory.memoriesInUse
import cc.noharry.agent.appserver.memory.memoryContentError
import cc.noharry.agent.appserver.memory.memoryProfileText
import cc.noharry.agent.appserver.memory.refusal
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.MAX_MEMORIES
import cc.noharry.agent.appserver.protocol.MAX_MEMORY_CHARS
import cc.noharry.agent.appserver.protocol.MAX_PER_CATEGORY
import cc.noharry.agent.appserver.protocol.MemoryCategory
import cc.noharry.agent.appserver.protocol.MemorySource
import cc.noharry.agent.appserver.protocol.UserMemory
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.HostPlaces
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.RuntimeContextRequest
import cc.noharry.agent.kernel.api.RuntimeContextScalar
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolPrefetchRequest
import cc.noharry.bothy.protocol.CompanionConfig
import cc.noharry.bothy.protocol.CompanionMode
import cc.noharry.bothy.server.companion.companionMessages
import cc.noharry.bothy.server.companion.decideCompanionRoute
import cc.noharry.bothy.server.server.BothyPlaces
import cc.noharry.bothy.server.voice.realtime.realtimeInstructions
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalAgentRuntimeApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh", application = Application::class)
class UserMemoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir: File = Files.createTempDirectory("user-memory").toFile()
    private var clock = 1_000L
    private val book = UserMemoryBook(File(dir, "memories.json")) { clock }

    @Test
    fun remembersChangesForgetsAndReadsBackFromTheFile() {
        val home = saved(book.remember(MemoryCategory.PLACES, " 家在望京花园 ", MemorySource.SAID, "记住我家在望京花园"))
        assertEquals("家在望京花园", home.content)
        assertEquals("记住我家在望京花园", home.said)
        // The same words again (punctuation aside) are kept once.
        val again = book.remember(MemoryCategory.PLACES, "家在望京花园。", MemorySource.SAID) as MemoryWrite.Saved
        assertFalse(again.created)
        assertEquals(1, book.all().size)
        clock = 2_000L
        val moved = saved(book.update(home.id, "家在回龙观", null, MemorySource.TYPED))
        assertEquals(MemoryCategory.PLACES, moved.category)
        assertEquals(1_000L to 2_000L, moved.createdAtMillis to moved.updatedAtMillis)
        assertEquals("记住我家在望京花园", moved.said)
        book.remember(MemoryCategory.HOME, "开空调一般 22 度", MemorySource.TYPED)
        val reread = UserMemoryBook(File(dir, "memories.json")).all()
        assertEquals(listOf("家在回龙观", "开空调一般 22 度"), reread.map { it.content })
        assertEquals(MemorySource.TYPED, reread.last().source)
        assertTrue(book.forget(home.id) is MemoryWrite.Removed)
        assertTrue(book.forget(home.id) is MemoryWrite.Refused)
        assertEquals(listOf("开空调一般 22 度"), book.all().map { it.content })
        // 只清除记忆 empties the book and its file.
        book.clear()
        assertTrue(book.all().isEmpty())
        assertTrue(UserMemoryBook(File(dir, "memories.json")).all().isEmpty())
    }

    @Test
    fun refusesSecretsLongNumbersAndOverlongWords() {
        assertNull(memoryContentError("老婆电话 13812345678"))
        assertEquals("不记密码、验证码这类信息", memoryContentError("车库门密码是 4321"))
        assertEquals("不记身份证号、银行卡号这类长号码", memoryContentError("身份证 110101199001011234"))
        assertEquals("不记身份证号、银行卡号这类长号码", memoryContentError("卡号 6222 0212 3456 7890"))
        assertEquals("内容是空的", memoryContentError("  "))
        assertTrue(memoryContentError("长".repeat(MAX_MEMORY_CHARS + 1))!!.startsWith("太长了"))
        val secret = book.remember(MemoryCategory.OTHER, "验证码 123456", MemorySource.SAID) as MemoryWrite.Refused
        assertEquals(MemoryRefusal.Secret, secret.why)
        assertEquals(FindingCode.MEMORY_REFUSED_SECRET.name, refusal(secret).code)
        assertTrue(book.all().isEmpty())
    }

    @Test
    fun keepsOneLineAndNoSecretsInTheWordsAsSaid() {
        val typed = saved(book.remember(MemoryCategory.PLACES, "家在望京花园3号楼\n公司在国贸", MemorySource.TYPED))
        assertEquals("家在望京花园3号楼 公司在国贸", typed.content)
        val home = saved(book.remember(MemoryCategory.PLACES, "家在回龙观", MemorySource.SAID, "记住我家在回龙观，门禁密码是3721"))
        assertNull(home.said)
        assertEquals("不记身份证号、银行卡号这类长号码", memoryContentError("卡号 6222/0212/3456/7890"))
        assertEquals("不记身份证号、银行卡号这类长号码", memoryContentError("卡号 6222 - 0212 - 3456 - 7890"))
        // A book written when 30 were allowed: 小博 uses the 24 last changed, in their order.
        val many = (1..30).map { UserMemory("m$it", MemoryCategory.OTHER, "事 $it", MemorySource.TYPED, null, 0L, it.toLong()) }
        assertEquals((7..30).map { "m$it" }, memoriesInUse(many).map { it.id })
    }

    @Test
    fun capsEachKindAndTheWhole() {
        repeat(MAX_PER_CATEGORY) { book.remember(MemoryCategory.MEDIA, "喜欢歌手 $it", MemorySource.SAID) }
        val full = book.remember(MemoryCategory.MEDIA, "喜欢歌手 X", MemorySource.SAID) as MemoryWrite.Refused
        assertTrue(full.reason, full.reason.contains("娱乐"))
        // The page says the same in its own words; 小博 reads the reason.
        assertEquals(MemoryRefusal.CategoryFull(MemoryCategory.MEDIA), full.why)
        val shown = refusal(full)
        assertEquals(FindingCode.MEMORY_REFUSED_CATEGORY_FULL.name, shown.code)
        assertEquals(FindingCode.MEMORY_CATEGORY_MEDIA.name, shown.more.first().code)
        val other = book.remember(MemoryCategory.OTHER, "别的", MemorySource.SAID) as MemoryWrite.Saved
        assertTrue(book.update(other.memory.id, "别的", MemoryCategory.MEDIA, MemorySource.TYPED) is MemoryWrite.Refused)
        val kinds = MemoryCategory.entries.filter { it != MemoryCategory.MEDIA }
        var n = 0
        while (book.all().size < MAX_MEMORIES) book.remember(kinds[n % kinds.size], "第 ${n++} 件", MemorySource.SAID)
        assertTrue(book.remember(MemoryCategory.TRAVEL, "再一件", MemorySource.SAID) is MemoryWrite.Refused)
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
        val tool = UserMemoryToolProvider { change -> change(book) }
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
    fun bothysPlacesKeepTheToolsWords() = runBlocking {
        val tool = UserMemoryToolProvider(BothyPlaces) { change -> change(book) }
        assertTrue(tool.listTools().single().description.contains("记住 / 修改 / 忘掉关于用户本人的长期信息（用户画像，设置 → 记忆）。"))
        val remembered = call(tool, """{"action":"remember","category":"identity","content":"叫我老王"}""")
        assertEquals(
            "已记住。用一句话告诉用户，复述记下或忘掉的内容；用户可以在 设置 → 记忆 查看和修改。",
            remembered["message"]?.jsonPrimitive?.content,
        )
        val drawer = ImageToolProvider(BothyPlaces) { _, _ -> error("nothing is drawn here") }
        val picture = drawer.listTools().single().description
        assertTrue(picture, picture.contains("按文字描述生成一张图片，并直接显示在屏幕的悬浮窗上。"))
        AppHost.install(places = BothyPlaces)
        try {
            val kinds = MemoryCategory.entries
            var n = 0
            while (book.all().size < MAX_MEMORIES) book.remember(kinds[n % kinds.size], "第 ${n++} 件", MemorySource.SAID)
            val full = book.remember(MemoryCategory.OTHER, "再一件", MemorySource.SAID) as MemoryWrite.Refused
            assertEquals("已经记了 $MAX_MEMORIES 件事，记满了：先在 设置 → 记忆 删掉一些", full.reason)
        } finally {
            AppHost.install(places = HostPlaces.NONE)
        }
    }

    @Test
    fun wordsAboutTheUserOfferTheToolOnTheFirstRound() {
        val tool = UserMemoryToolProvider { change -> change(book) }
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
        assertTrue(UserMemoryContext { emptyList() }.provide(request).blocks.isEmpty())
        val home = saved(book.remember(MemoryCategory.PLACES, "家在望京花园", MemorySource.SAID))
        book.remember(MemoryCategory.IDENTITY, "叫我老王", MemorySource.SAID)
        val contribution = UserMemoryContext { book.all() }.provide(request)
        assertEquals(listOf("user_memory.identity", "user_memory.places"), contribution.blocks.map { it.id })
        val fact = contribution.blocks.last().data.single()
        assertEquals(home.id, fact.id)
        assertEquals("常去地点" to "user_memory", fact.subject to fact.predicate)
        assertEquals(RuntimeContextScalar.Text("家在望京花园"), fact.value)
        book.update(home.id, "家在回龙观", null, MemorySource.SAID)
        val changed = UserMemoryContext { book.all() }.provide(request)
        assertTrue(changed.fingerprint != contribution.fingerprint)
    }

    @Test
    fun theProfileReachesTheEndToEndModelAndTheCompanion() {
        book.remember(MemoryCategory.IDENTITY, "叫我老王", MemorySource.SAID)
        book.remember(MemoryCategory.HOME, "开空调一般 22 度", MemorySource.SAID)
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
        val companion = CompanionConfig(enabled = true, mode = CompanionMode.ROUTE)
        assertFalse(decideCompanionRoute(companion, "记住我喜欢周杰伦", 0.95, null, false).toCompanion)
        assertFalse(decideCompanionRoute(companion, "以后叫我老王吧", 0.95, null, false).toCompanion)
    }

    private fun saved(result: MemoryWrite): UserMemory = (result as MemoryWrite.Saved).memory

    private suspend fun call(tool: UserMemoryToolProvider, arguments: String) =
        Json.parseToJsonElement(tool.callTool(USER_MEMORY_TOOL, arguments, ToolCallContext("s", "r", "q")).resultJson).jsonObject
}
