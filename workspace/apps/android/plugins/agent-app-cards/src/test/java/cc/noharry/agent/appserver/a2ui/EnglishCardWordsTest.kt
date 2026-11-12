package cc.noharry.agent.appserver.a2ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test

/** With the generic persona the card and offer tools read no Chinese. */
class EnglishCardWordsTest {
    @Test
    fun theGenericPersonaReadsNoChinese() = runBlocking {
        val provider = OfferToolProvider(BindableTools(emptyList()))
        (listOf(provider.displayName) + provider.listTools().flatMap { listOf(it.title.orEmpty(), it.description, it.inputSchemaJson) })
            .forEach { text -> assertFalse(text, text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) }
    }
}
