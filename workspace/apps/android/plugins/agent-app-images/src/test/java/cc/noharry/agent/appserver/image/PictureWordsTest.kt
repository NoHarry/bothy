package cc.noharry.agent.appserver.image

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picture tool names where the host shows a picture ([ImagesPlugin]'s `shownOn`) only when it says; here in the
 * generic persona's English (PictureBoardTest has the Chinese).
 */
class PictureWordsTest {
    @Test
    fun theHostsPlaceIsNamedOnlyWhenItHasOne() = runBlocking {
        val bare = pictureDescription(null)
        assertTrue(bare, bare.contains("from a text description. In prompt"))
        val shown = pictureDescription("in the conversation")
        assertTrue(shown, shown.contains("from a text description and shows it right away in the conversation. In prompt"))
    }

    @Test
    fun theGenericPersonaReadsTheToolInEnglish() = runBlocking {
        val tool = ImageToolProvider(null) { _, _ -> error("nothing is drawn here") }
        val words = listOf(tool.displayName) + tool.listTools().flatMap { listOf(it.title.orEmpty(), it.description, it.inputSchemaJson) }
        words.forEach { text -> assertFalse(text, text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) }
    }

    private suspend fun pictureDescription(shownOn: String?): String =
        ImageToolProvider(shownOn) { _, _ -> error("nothing is drawn here") }.listTools().single().description
}
