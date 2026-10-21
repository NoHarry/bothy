@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.model

import cc.noharry.bothy.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.bothy.kernel.api.ProviderConcurrencyMode
import org.junit.Assert.assertEquals
import org.junit.Test

class FirstPartyModelConcurrencyDeclarationTest {
    @Test
    fun openAiAndAnthropicDeclareBoundedThreadSafe() {
        val openAi = OpenAiModelClient(apiKeyProvider = { "test-key" })
        val anthropic = AnthropicModelClient(apiKeyProvider = { "test-key" })
        val expected = ProviderConcurrencyDeclaration.threadSafe()

        assertEquals(ProviderConcurrencyMode.RUNTIME_SHARED_THREAD_SAFE, openAi.providerConcurrency.mode)
        assertEquals(expected, openAi.providerConcurrency)
        assertEquals(expected, anthropic.providerConcurrency)
    }
}
