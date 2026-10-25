package cc.noharry.bothy.sample

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugRuntimeSessionDefaultsTest {
    @Test
    fun debugConsoleDefaultsToLiveModelMode() {
        assertFalse(DEFAULT_USE_SIMULATED_MODEL)
        assertEquals("live model", LIVE_MODEL_LABEL)
        assertEquals("simulated MCP", SIMULATED_MODEL_LABEL)
    }

    @Test
    fun debugConsoleDefaultsToStreamMode() {
        assertTrue(DEFAULT_STREAM_ENABLED)
        assertTrue(resolveDebugStreamEnabled(emptyMap()))
        assertFalse(resolveDebugStreamEnabled(mapOf("AGENT_RUNTIME_LIVE_PROVIDER_STREAM" to "0")))
        assertTrue(resolveDebugStreamEnabled(mapOf("AGENT_RUNTIME_MODEL_PROVIDER_STREAM" to "1")))
    }

    @Test
    fun debugConsoleDefaultsEveryFixedMcpProviderOff() {
        val providers = defaultDebugMcpProviders().associateBy { it.id }

        assertFalse(providers.getValue(DEBUG_MCP_PROVIDER_TOOLS_JSON).enabled)
        assertFalse(providers.getValue(DEBUG_MCP_PROVIDER_DEVICE_CALENDAR).enabled)
        assertFalse(providers.getValue(DEBUG_MCP_PROVIDER_DEVICE_NOTIFICATION).enabled)
        assertTrue(providers.getValue(DEBUG_MCP_PROVIDER_DEVICE_MEDIA).enabled)
    }
}
