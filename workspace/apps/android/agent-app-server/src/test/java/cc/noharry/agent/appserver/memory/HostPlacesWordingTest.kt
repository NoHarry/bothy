package cc.noharry.agent.appserver.memory

import cc.noharry.agent.appserver.image.ImageToolProvider
import cc.noharry.agent.appserver.protocol.MAX_MEMORIES
import cc.noharry.agent.appserver.protocol.MemoryCategory
import cc.noharry.agent.appserver.protocol.MemorySource
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.HostPlaces
import cc.noharry.agent.kernel.api.ToolCallContext
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The memory and picture tools name the host's places ([HostPlaces]) only when it has them. */
class HostPlacesWordingTest {
    private val dir: File = Files.createTempDirectory("host-places").toFile()
    private val book = UserMemoryBook(File(dir, "memories.json")) { 1_000L }
    private val places = HostPlaces(memoryPage = "我的 → 记忆", picturesShownOn = "对话里")

    @After
    fun reset() = AppHost.install(places = HostPlaces.NONE)

    @Test
    fun withoutPlacesTheWordsLeaveThemOut() = runBlocking {
        val tool = UserMemoryToolProvider(HostPlaces.NONE) { change -> change(book) }
        val description = tool.listTools().single().description
        assertTrue(description, description.contains("（用户画像）。"))
        assertEquals("已记住。用一句话告诉用户，复述记下或忘掉的内容。", remember(tool))
        val picture = pictureDescription(HostPlaces.NONE)
        assertTrue(picture, picture.contains("按文字描述生成一张图片。prompt"))
        assertEquals("已经记了 $MAX_MEMORIES 件事，记满了：先问用户要忘掉哪几条", oneTooMany())
    }

    @Test
    fun theHostsPlacesAreNamed() = runBlocking {
        AppHost.install(places = places)
        val tool = UserMemoryToolProvider(places) { change -> change(book) }
        val description = tool.listTools().single().description
        assertTrue(description, description.contains("（用户画像，我的 → 记忆）。"))
        assertEquals("已记住。用一句话告诉用户，复述记下或忘掉的内容；用户可以在 我的 → 记忆 查看和修改。", remember(tool))
        val picture = pictureDescription(places)
        assertTrue(picture, picture.contains("按文字描述生成一张图片，并直接显示在对话里。prompt"))
        assertEquals("已经记了 $MAX_MEMORIES 件事，记满了：先在 我的 → 记忆 删掉一些", oneTooMany())
    }

    private suspend fun remember(tool: UserMemoryToolProvider): String? {
        val arguments = """{"action":"remember","category":"identity","content":"叫我老王"}"""
        val result = tool.callTool(USER_MEMORY_TOOL, arguments, ToolCallContext("s", "r", "q")).resultJson
        return Json.parseToJsonElement(result).jsonObject["message"]?.jsonPrimitive?.content
    }

    private suspend fun pictureDescription(places: HostPlaces): String =
        ImageToolProvider(places) { _, _ -> error("nothing is drawn here") }.listTools().single().description

    /** The reason a remember is refused once the book holds [MAX_MEMORIES]. */
    private fun oneTooMany(): String {
        val kinds = MemoryCategory.entries
        var n = 0
        while (book.all().size < MAX_MEMORIES) book.remember(kinds[n % kinds.size], "第 ${n++} 件", MemorySource.SAID)
        return (book.remember(MemoryCategory.OTHER, "再一件", MemorySource.SAID) as MemoryWrite.Refused).reason
    }
}
