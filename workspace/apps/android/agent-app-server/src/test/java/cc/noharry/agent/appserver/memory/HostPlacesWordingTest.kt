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

/**
 * The memory and picture tools name the host's places ([HostPlaces]) only when it has them; here in the generic persona's
 * English (Bothy's tests have the Chinese).
 */
class HostPlacesWordingTest {
    private val dir: File = Files.createTempDirectory("host-places").toFile()
    private val book = UserMemoryBook(File(dir, "memories.json")) { 1_000L }
    private val places = HostPlaces(memoryPage = "Me → Memory", picturesShownOn = "in the conversation")

    @After
    fun reset() = AppHost.install(places = HostPlaces.NONE)

    @Test
    fun withoutPlacesTheWordsLeaveThemOut() = runBlocking {
        val tool = UserMemoryToolProvider(HostPlaces.NONE) { change -> change(book) }
        val description = tool.listTools().single().description
        assertTrue(description, description.contains("(the user's profile). Keep only"))
        assertEquals("Remembered. Tell the user in one sentence, restating what was remembered or forgotten.", remember(tool))
        val picture = pictureDescription(HostPlaces.NONE)
        assertTrue(picture, picture.contains("from a text description. In prompt"))
        assertEquals("$MAX_MEMORIES memories are kept, the most there can be: ask the user which to forget first", oneTooMany())
    }

    @Test
    fun theHostsPlacesAreNamed() = runBlocking {
        AppHost.install(places = places)
        val tool = UserMemoryToolProvider(places) { change -> change(book) }
        val description = tool.listTools().single().description
        assertTrue(description, description.contains("(the user's profile, Me → Memory). Keep only"))
        assertEquals(
            "Remembered. Tell the user in one sentence, restating what was remembered or forgotten; the user can see and change it on Me → Memory.",
            remember(tool),
        )
        val picture = pictureDescription(places)
        assertTrue(picture, picture.contains("from a text description and shows it right away in the conversation. In prompt"))
        assertEquals("$MAX_MEMORIES memories are kept, the most there can be: delete some on Me → Memory first", oneTooMany())
    }

    private suspend fun remember(tool: UserMemoryToolProvider): String? {
        val arguments = """{"action":"remember","category":"identity","content":"Call me Sam"}"""
        val result = tool.callTool(USER_MEMORY_TOOL, arguments, ToolCallContext("s", "r", "q")).resultJson
        return Json.parseToJsonElement(result).jsonObject["message"]?.jsonPrimitive?.content
    }

    private suspend fun pictureDescription(places: HostPlaces): String =
        ImageToolProvider(places) { _, _ -> error("nothing is drawn here") }.listTools().single().description

    /** The reason a remember is refused once the book holds [MAX_MEMORIES]. */
    private fun oneTooMany(): String {
        val kinds = MemoryCategory.entries
        var n = 0
        while (book.all().size < MAX_MEMORIES) book.remember(kinds[n % kinds.size], "thing ${n++}", MemorySource.SAID)
        return (book.remember(MemoryCategory.OTHER, "one more", MemorySource.SAID) as MemoryWrite.Refused).reason
    }
}
