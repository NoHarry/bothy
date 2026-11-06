package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.mcp.DEBUG_MCP_PROVIDER_DEVICE_CALENDAR
import cc.noharry.agent.appserver.mcp.DEBUG_MCP_PROVIDER_DEVICE_MEDIA
import cc.noharry.agent.appserver.mcp.DEBUG_MCP_PROVIDER_DEVICE_NOTIFICATION
import cc.noharry.agent.appserver.mcp.DEBUG_MCP_PROVIDER_TOOLS_JSON
import cc.noharry.agent.appserver.mcp.defaultMcpProviders
import cc.noharry.agent.appserver.model.resolveStreamEnabled
import cc.noharry.agent.appserver.protocol.view.LIVE_MODEL_LABEL
import cc.noharry.agent.appserver.protocol.view.SIMULATED_MODEL_LABEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSessionDefaultsTest {
    @Test
    fun consoleDefaultsToLiveModelMode() {
        assertFalse(DEFAULT_USE_SIMULATED_MODEL)
        assertEquals("live model", LIVE_MODEL_LABEL)
        assertEquals("simulated MCP", SIMULATED_MODEL_LABEL)
    }

    @Test
    fun consoleDefaultsToStreamMode() {
        assertTrue(DEFAULT_STREAM_ENABLED)
        assertTrue(resolveStreamEnabled(emptyMap()))
        assertFalse(resolveStreamEnabled(mapOf("AGENT_RUNTIME_LIVE_PROVIDER_STREAM" to "0")))
        assertTrue(resolveStreamEnabled(mapOf("AGENT_RUNTIME_MODEL_PROVIDER_STREAM" to "1")))
    }

    @Test
    fun consoleDefaultsEveryFixedMcpProviderOff() {
        assertFalse(defaultMcpProviders().any { it.id == DEBUG_MCP_PROVIDER_DEVICE_MEDIA })
        AppHost.install(builtIns = BuiltInFeatures(deviceMedia = true))
        val providers = try {
            defaultMcpProviders().associateBy { it.id }
        } finally {
            AppHost.install(builtIns = BuiltInFeatures.NONE)
        }

        assertFalse(providers.getValue(DEBUG_MCP_PROVIDER_TOOLS_JSON).enabled)
        assertFalse(providers.getValue(DEBUG_MCP_PROVIDER_DEVICE_CALENDAR).enabled)
        assertFalse(providers.getValue(DEBUG_MCP_PROVIDER_DEVICE_NOTIFICATION).enabled)
        assertTrue(providers.getValue(DEBUG_MCP_PROVIDER_DEVICE_MEDIA).enabled)
    }
}
