@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime

import cc.noharry.agent.appserver.a2ui.BindableTools
import cc.noharry.agent.appserver.a2ui.OfferToolProvider
import cc.noharry.agent.appserver.image.ImageToolProvider
import cc.noharry.agent.appserver.memory.UserMemoryToolProvider
import cc.noharry.agent.appserver.pages.PageToolProvider
import cc.noharry.agent.appserver.plans.PlanHost
import cc.noharry.agent.appserver.plans.PlanToolProvider
import cc.noharry.agent.appserver.skills.customSkillTemplate
import cc.noharry.agent.appserver.skills.subagentInput
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.runtime.SubagentSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The server's own tools as a persona without a language reads them: names, descriptions and schemas all in English.
 * Words the host supplies (its places, its persona) are its own; Bothy's tests read the Chinese.
 */
class EnglishToolWordsTest {
    @Test
    fun theGenericPersonaReadsNoChinese() = runBlocking {
        val providers: List<McpClientProvider> = listOf(
            OfferToolProvider(BindableTools(emptyList())),
            PageToolProvider(onScreen = true) { _, _ -> "" },
            ImageToolProvider(HostPlaces.NONE) { _, _ -> error("nothing is drawn here") },
            UserMemoryToolProvider(HostPlaces.NONE) { error("nothing is written here") },
            PlanToolProvider(PlanHost(null)),
        )
        for (provider in providers) {
            assertNoChinese(provider.displayName)
            provider.listTools().forEach { tool ->
                assertNoChinese(tool.title.orEmpty())
                assertNoChinese(tool.description)
                assertNoChinese(tool.inputSchemaJson)
            }
        }
        assertNoChinese(customSkillTemplate())
        assertNoChinese(subagentInput(SubagentSpec("sub-1", "s-sub-1", "s", "r", "weather", "Find tomorrow's weather", 1, null, false)))
    }

    private fun assertNoChinese(text: String) =
        assertFalse(text, text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN })
}
