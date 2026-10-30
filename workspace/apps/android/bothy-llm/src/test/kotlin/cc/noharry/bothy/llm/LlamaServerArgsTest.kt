package cc.noharry.bothy.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaServerArgsTest {
    @Test
    fun npuRunOffloadsEveryLayerToTheHexagonDevice() {
        val args = llamaServerArgs("/lib/libllama_server.so", "/m.gguf", GEMMA_4_E4B, LlamaDevice.NPU, 4321, "k")

        assertEquals("/lib/libllama_server.so", args.first())
        assertTrue(args.containsAll(listOf("--device", "HTP0", "-ngl", "99")))
        assertEquals("HTP0", args[args.indexOf("--device") + 1])
    }

    @Test
    fun cpuRunKeepsEveryLayerOnTheCpu() {
        val args = llamaServerArgs("/x", "/m.gguf", GEMMA_4_E4B, LlamaDevice.CPU, 4321, "k")

        assertEquals("none", args[args.indexOf("--device") + 1])
        assertEquals("0", args[args.indexOf("-ngl") + 1])
    }

    @Test
    fun listensOnlyOnLoopbackBehindTheKeyWithOneSlot() {
        val args = llamaServerArgs("/x", "/m.gguf", GEMMA_4_E4B, LlamaDevice.NPU, 4321, "secret")

        assertEquals("127.0.0.1", args[args.indexOf("--host") + 1])
        assertEquals("4321", args[args.indexOf("--port") + 1])
        assertEquals("secret", args[args.indexOf("--api-key") + 1])
        assertEquals("1", args[args.indexOf("-np") + 1])
        assertEquals("${GEMMA_4_E4B.contextTokens}", args[args.indexOf("-c") + 1])
        assertTrue("--jinja" in args)
    }

    @Test
    fun gemmaRunsWithThinkingOff() {
        val args = llamaServerArgs("/x", "/m.gguf", GEMMA_4_E4B, LlamaDevice.NPU, 1, "k")

        assertEquals("""{"enable_thinking":false}""", args[args.indexOf("--chat-template-kwargs") + 1])
    }
}
