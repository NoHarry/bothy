package cc.noharry.bothy.app.model
import cc.noharry.bothy.app.runtime.usesDeepSeekNativeUpdates
import cc.noharry.bothy.app.session.ModelProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekNativeTest {
    private fun profile(baseUrl: String, endpoint: ProviderEndpoint = ProviderEndpoint.MESSAGES) =
        ProviderProfile("p", "p", ModelProvider.ANTHROPIC, "k", "deepseek-flash", baseUrl, endpoint)

    @Test fun onlyDeepSeeksOwnMessagesEndpointGetsNativeUpdates() {
        assertTrue(profile("https://api.deepseek.com/anthropic").usesDeepSeekNativeUpdates())
        // A gateway in front of DeepSeek drops defer_loading and tool_addition (measured 2026-10-17).
        assertFalse(profile("https://api.noharry.cc/v1").usesDeepSeekNativeUpdates())
        assertFalse(profile("https://api.deepseek.com.example.org/anthropic").usesDeepSeekNativeUpdates())
    }

    @Test fun thePromptTellsTheModelHowBackendToolsArriveInEachMode() {
        val callTool = appSystemPrompt("")
        val native = appSystemPrompt("", nativeTools = true)
        assertTrue(callTool.contains("via call_tool(name, arguments)"))
        assertFalse(native.contains("call_tool"))
        assertTrue(native.contains("loaded into your tool list right after the result; call them directly"))
        assertTrue(native.toByteArray().size <= callTool.toByteArray().size)
    }
}
