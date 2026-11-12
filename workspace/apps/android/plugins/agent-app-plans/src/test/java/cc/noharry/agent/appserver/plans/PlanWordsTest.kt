package cc.noharry.agent.appserver.plans

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plan tool as a persona without a language reads it: name, description and schema all in English; and its cues
 * are whole words in text without Chinese.
 */
class PlanWordsTest {
    @Test
    fun theGenericPersonaReadsNoChinese() = runBlocking {
        val provider = PlanToolProvider(PlanHost(null))
        assertNoChinese(provider.displayName)
        provider.listTools().forEach { tool ->
            assertNoChinese(tool.title.orEmpty())
            assertNoChinese(tool.description)
            assertNoChinese(tool.inputSchemaJson)
        }
    }

    @Test
    fun englishCuesAreWholeWordsInTextWithoutChinese() {
        assertFalse(PLAN_CUES.heardIn("播放 Daily Mix"))
        assertTrue(PLAN_CUES.heardIn("Remind me at five"))
        assertTrue(PLAN_CUES.heardIn("every weekday at 8"))
        assertFalse(PLAN_CUES.heardIn("the reminiscence podcast"))
    }

    @Test
    fun theHostsWordsReachThePlanTool() = runBlocking {
        val host = PlanHost(null, describe = { it.copy(description = it.description + " (host)") })
        assertTrue(PlanToolProvider(host).listTools().single().description.endsWith(" (host)"))
    }

    private fun assertNoChinese(text: String) =
        assertFalse(text, text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN })
}
