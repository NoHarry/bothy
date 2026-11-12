package cc.noharry.agent.appserver.memory

import cc.noharry.agent.appserver.protocol.MAX_MEMORIES
import cc.noharry.agent.appserver.protocol.MemoryCategory
import cc.noharry.agent.appserver.protocol.MemorySource
import cc.noharry.agent.spi.ToolCallContext
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The memory tool names the host's memory page ([MemoryPlugin]'s `page`) only when it has one; here in the generic
 * persona's English (UserMemoryTest has the Chinese).
 */
class MemoryWordsTest {
    private val dir: File = Files.createTempDirectory("memory-words").toFile()

    @Test
    fun withoutAPageTheWordsLeaveItOut() = runBlocking {
        val book = UserMemoryBook(File(dir, "memories.json")) { 1_000L }
        val tool = UserMemoryToolProvider(null) { change -> change(book) }
        val description = tool.listTools().single().description
        assertTrue(description, description.contains("(the user's profile). Keep only"))
        assertEquals("Remembered. Tell the user in one sentence, restating what was remembered or forgotten.", remember(tool))
        assertEquals("$MAX_MEMORIES memories are kept, the most there can be: ask the user which to forget first", oneTooMany(book))
    }

    @Test
    fun secretsAreNotKeptInEnglishEither() {
        val book = UserMemoryBook(File(dir, "memories.json")) { 1_000L }
        val secrets = listOf(
            "My bank PIN is 4321", "the wifi password is hunter2", "Verification code 908172", "card CVV 123", "pin 4321",
            "PIN码是4321", "我的pin是1234", "银行OTP是908172", "one time code 5521", "my pin is 4321",
        )
        for (secret in secrets) {
            assertEquals(secret, MemoryRefusal.Secret, (book.remember(MemoryCategory.OTHER, secret, MemorySource.SAID) as MemoryWrite.Refused).why)
        }
        for (kept in listOf("I like spinach and pinball", "She collects enamel pins", "Pin the map card to the top")) {
            assertTrue(kept, book.remember(MemoryCategory.MEDIA, kept, MemorySource.SAID) is MemoryWrite.Saved)
        }
    }

    @Test
    fun theHostsPageIsNamed() = runBlocking {
        val book = UserMemoryBook(File(dir, "memories.json"), memoryPage = PAGE) { 1_000L }
        val tool = UserMemoryToolProvider(PAGE) { change -> change(book) }
        val description = tool.listTools().single().description
        assertTrue(description, description.contains("(the user's profile, Me → Memory). Keep only"))
        assertEquals(
            "Remembered. Tell the user in one sentence, restating what was remembered or forgotten; the user can see and change it on Me → Memory.",
            remember(tool),
        )
        assertEquals("$MAX_MEMORIES memories are kept, the most there can be: delete some on Me → Memory first", oneTooMany(book))
    }

    @Test
    fun theGenericPersonaReadsTheToolInEnglishAndItsCuesAreWholeWords() = runBlocking {
        val tool = UserMemoryToolProvider(null) { error("nothing is written here") }
        val words = listOf(tool.displayName) + tool.listTools().flatMap { listOf(it.title.orEmpty(), it.description, it.inputSchemaJson) }
        words.forEach { text -> assertFalse(text, text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) }
        assertFalse(MEMORY_CUES.heardIn("播放 Call Me Maybe"))
        assertTrue(MEMORY_CUES.heardIn("Call me Sam from now on"))
        assertTrue(MEMORY_CUES.heardIn("I don’t like jazz"))
        assertTrue(MEMORY_CUES.heardIn("记住我家在望京"))
    }

    private suspend fun remember(tool: UserMemoryToolProvider): String? {
        val arguments = """{"action":"remember","category":"identity","content":"Call me Sam"}"""
        val result = tool.callTool(USER_MEMORY_TOOL, arguments, ToolCallContext("s", "r", "q")).resultJson
        return Json.parseToJsonElement(result).jsonObject["message"]?.jsonPrimitive?.content
    }

    /** The reason a remember is refused once the book holds [MAX_MEMORIES]. */
    private fun oneTooMany(book: UserMemoryBook): String {
        val kinds = MemoryCategory.entries
        var n = 0
        while (book.all().size < MAX_MEMORIES) book.remember(kinds[n % kinds.size], "thing ${n++}", MemorySource.SAID)
        return (book.remember(MemoryCategory.OTHER, "one more", MemorySource.SAID) as MemoryWrite.Refused).reason
    }

    private companion object {
        const val PAGE = "Me → Memory"
    }
}
