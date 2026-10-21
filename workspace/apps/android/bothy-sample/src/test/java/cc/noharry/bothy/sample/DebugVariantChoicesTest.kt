package cc.noharry.bothy.sample

import org.junit.Assert.assertEquals
import org.junit.Test

/** The composer's 思考强度 menu: DeepSeek shows none chosen until another is picked; other models keep Default. */
class DebugVariantChoicesTest {
    private fun editor(provider: DebugProvider, model: String, variant: String, options: List<String>) =
        DebugProviderEditState("k", model, variant, "https://example.test", provider = provider, variantOptions = options)

    @Test
    fun deepSeekShowsNoneChosenWithoutASeparateDefault() {
        val options = listOf("none", "low", "medium", "high", "max")

        assertEquals(options to "none", editor(DebugProvider.ANTHROPIC, "deepseek-flash", "", options).variantChoices())
        assertEquals(options to "none", editor(DebugProvider.OPENAI, "deepseek-v4-pro", "", options).variantChoices())
        assertEquals(options to "high", editor(DebugProvider.ANTHROPIC, "deepseek-flash", "high", options).variantChoices())
    }

    @Test
    fun aModelWithoutADefaultOfItsOwnKeepsDefault() {
        val claude = listOf("high", "max")

        assertEquals(listOf("default", "high", "max") to "default", editor(DebugProvider.ANTHROPIC, "claude-sonnet-5", "", claude).variantChoices())
        assertEquals(listOf("default", "high", "max") to "max", editor(DebugProvider.ANTHROPIC, "claude-sonnet-5", "max", claude).variantChoices())
    }
}
