@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.mcp

import cc.noharry.bothy.kernel.api.mcp.McpClientProvider
import cc.noharry.bothy.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.bothy.kernel.api.ToolCallContext
import cc.noharry.bothy.kernel.api.ToolCallResult
import cc.noharry.bothy.kernel.api.ToolDescriptor
import cc.noharry.bothy.kernel.api.ToolInitializeResult
import cc.noharry.bothy.kernel.api.ToolProvider
import cc.noharry.bothy.kernel.api.ToolProviderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import cc.noharry.bothy.kernel.core.failed

class McpClientProviderAdapterTest {
    @Test
    fun mcpProviderIsToolProviderAndMapsServerId() {
        val provider = CompatibleMcpProvider()
        assertTrue(provider is ToolProvider)
        assertEquals("fixture", provider.serverId)
        assertEquals("fixture", provider.providerId)
        assertEquals(ProviderConcurrencyDeclaration.serialized(), provider.providerConcurrency)
    }

    @Test
    fun toolInitializeCompanionsRemainUsable() {
        val connected = ToolInitializeResult.connected("ok")
        val failed = ToolInitializeResult.failed("nope")
        assertEquals(ToolProviderStatus.CONNECTED, connected.status)
        assertEquals(ToolProviderStatus.FAILED, failed.status)
        assertEquals("nope", failed.message)
    }

    @Test
    fun existingMcpOverrideSurfaceWorksThroughToolProvider() = runBlocking {
        val provider: ToolProvider = CompatibleMcpProvider()
        val tools = provider.listTools()
        assertEquals(listOf("echo"), tools.map { it.name })

        val result = provider.callTool(
            toolName = "echo",
            argumentsJson = """{"q":1}""",
            context = ToolCallContext(sessionId = "s", runId = "r", requestId = "q"),
        )
        assertTrue(result.success)
        assertEquals("""{"ok":true}""", result.resultJson)
    }
}

private class CompatibleMcpProvider : McpClientProvider {
    override val serverId: String = "fixture"
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()

    override suspend fun listTools(): List<ToolDescriptor> =
        listOf(ToolDescriptor(name = "echo", inputSchemaJson = """{"type":"object"}"""))

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult = ToolCallResult(success = true, resultJson = """{"ok":true}""")
}
